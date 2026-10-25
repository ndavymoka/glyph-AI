package com.example.network

import android.util.Base64
import com.example.data.AiProvider
import com.example.data.OracleSettings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class AiOracleResult(
    val question: String,
    val answer: String,
    val rawAnswer: String,
    val provider: AiProvider,
    val model: String,
    val latencyMs: Long
)

object AiOracleClient {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val jsonMediaType = "application/json".toMediaType()
    private val wavMediaType = "audio/wav".toMediaType()

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Pre-warms DNS + TCP + TLS 1.3 + HTTP/2 connection to the active AI provider while the user
     * is speaking their question into the microphone, eliminating 150-350ms of handshake latency.
     */
    suspend fun prewarmConnection(settings: OracleSettings) = withContext(Dispatchers.IO) {
        try {
            val provider = settings.selectedProvider
            val key = settings.getResolvedApiKey(provider)
            val baseUrl = settings.getBaseUrl(provider)
            val pingUrl = when (provider) {
                AiProvider.GEMINI -> "$baseUrl/models?pageSize=1&key=$key"
                else -> "$baseUrl/models"
            }
            val reqBuilder = Request.Builder()
                .url(pingUrl)
                .head()
            if (provider != AiProvider.GEMINI && key.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $key")
            }
            okHttpClient.newCall(reqBuilder.build()).execute().close()
        } catch (_: Throwable) {
            // Best-effort socket pre-warm
        }
    }

    /**
     * Fetches the list of available models from the selected AI provider's API
     * (`GET {baseUrl}/models` for OpenAI-compatible providers, `/v1beta/models` for Gemini).
     */
    suspend fun fetchAvailableModels(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String = provider.defaultBaseUrl,
        isEnglish: Boolean = false
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException(
                    if (isEnglish) "Enter an API key for ${provider.displayName} first"
                    else "Сначала введите API-ключ для ${provider.displayName}"
                )
            )
        }

        val cleanBase = baseUrl.trim().trimEnd('/').ifBlank { provider.defaultBaseUrl }

        try {
            val models = when (provider) {
                AiProvider.GEMINI -> {
                    val req = Request.Builder()
                        .url("$cleanBase/models?key=$cleanKey")
                        .header("Accept", "application/json")
                        .get()
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("Gemini HTTP ${resp.code}: ${extractApiErrorMessage(body)}")
                        }
                        val arr = json.parseToJsonElement(body).jsonObject["models"]?.jsonArray
                        arr?.mapNotNull { el ->
                            el.jsonObject["name"]?.jsonPrimitive?.content?.removePrefix("models/")
                        }?.filter { it.startsWith("gemini") } ?: emptyList()
                    }
                }

                else -> {
                    val req = Request.Builder()
                        .url("$cleanBase/models")
                        .header("Authorization", "Bearer $cleanKey")
                        .header("Accept", "application/json")
                        .get()
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("${provider.displayName} HTTP ${resp.code}: ${extractApiErrorMessage(body)}")
                        }
                        val arr = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                        val list = arr?.mapNotNull { el ->
                            el.jsonObject["id"]?.jsonPrimitive?.content
                        }?.filter { id ->
                            !id.contains("whisper", ignoreCase = true) &&
                                !id.contains("guard", ignoreCase = true) &&
                                !id.contains("tts", ignoreCase = true) &&
                                !id.contains("embedding", ignoreCase = true) &&
                                !id.contains("moderation", ignoreCase = true) &&
                                !id.contains("dall-e", ignoreCase = true)
                        } ?: emptyList()

                        if (provider == AiProvider.OPENROUTER) list.take(45) else list.sorted()
                    }
                }
            }
            if (models.isEmpty()) {
                Result.failure(
                    IllegalStateException(
                        if (isEnglish) "Server returned an empty model list"
                        else "Сервер вернул пустой список моделей"
                    )
                )
            } else {
                Result.success(models)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fast-path single-round-trip Audio -> Oracle Answer for Gemini when Gemini is the active provider
     * and no separate Groq Whisper key is configured. Sends the WAV audio and system prompt in ONE
     * HTTP request instead of two sequential requests, cutting latency in half!
     */
    suspend fun processAudioAndAskOracle(
        wavBytes: ByteArray,
        settings: OracleSettings
    ): Result<AiOracleResult> = withContext(Dispatchers.IO) {
        val totalStart = System.currentTimeMillis()
        val groqKey = settings.getResolvedApiKey(AiProvider.GROQ)
        val geminiKey = settings.getResolvedApiKey(AiProvider.GEMINI)

        // Single-round-trip optimization when Gemini is the selected provider and Groq Whisper isn't overriding
        if (settings.selectedProvider == AiProvider.GEMINI && geminiKey.isNotBlank() && groqKey.isBlank()) {
            try {
                val singleTrip = askGeminiAudioSingleTrip(wavBytes, geminiKey, settings, totalStart)
                if (singleTrip != null) {
                    return@withContext Result.success(singleTrip)
                }
            } catch (_: Exception) {
                // Fall back to 2-step transcribe + askOracle below
            }
        }

        val sttRes = transcribeAudioWav(wavBytes, settings)
        val recognizedQuestion = sttRes.getOrElse { err ->
            return@withContext Result.failure(err)
        }.trim()

        if (recognizedQuestion.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException(
                    if (settings.isEnglish) "Speech not recognized. Try speaking louder."
                    else "Речь не распознана. Попробуйте сказать громче."
                )
            )
        }

        askOracle(recognizedQuestion, settings)
    }

    private fun askGeminiAudioSingleTrip(
        wavBytes: ByteArray,
        geminiKey: String,
        settings: OracleSettings,
        startTimeMs: Long
    ): AiOracleResult? {
        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val userModel = settings.getActiveModel(AiProvider.GEMINI).removePrefix("models/").trim()
        val candidates = linkedSetOf(
            userModel.ifBlank { "gemini-2.5-flash" },
            "gemini-2.5-flash",
            "gemini-flash-latest"
        )

        val promptText = if (settings.isEnglish) {
            "${settings.systemPrompt.trim()}\n" +
                "Listen to the user's spoken question in this audio. " +
                "Output strictly in 2 lines:\n" +
                "Q: <transcribed question>\n" +
                "A: <ultra-short answer, max ${settings.maxWordsLimit} words>"
        } else {
            "${settings.systemPrompt.trim()}\n" +
                "Прослушай голосовой вопрос пользователя в этом аудио. " +
                "Выведи ответ строго в 2 строки:\n" +
                "Q: <распознанный текст вопроса>\n" +
                "A: <ультра-краткий ответ максимум из ${settings.maxWordsLimit} слов>"
        }

        for (model in candidates) {
            try {
                val url = "${settings.getBaseUrl(AiProvider.GEMINI)}/models/$model:generateContent?key=${geminiKey.trim()}"
                val payload = buildJsonObject {
                    putJsonArray("contents") {
                        add(
                            buildJsonObject {
                                putJsonArray("parts") {
                                    add(buildJsonObject { put("text", promptText) })
                                    add(
                                        buildJsonObject {
                                            putJsonObject("inlineData") {
                                                put("mimeType", "audio/wav")
                                                put("data", base64Audio)
                                            }
                                        }
                                    )
                                }
                            }
                        )
                    }
                    putJsonObject("generationConfig") {
                        put("temperature", 0.1)
                        put("maxOutputTokens", 50)
                    }
                }.toString()

                val request = Request.Builder()
                    .url(url)
                    .post(payload.toByteArray(Charsets.UTF_8).toRequestBody(jsonMediaType))
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()
                    if (!response.isSuccessful) continue
                    val root = json.parseToJsonElement(bodyString).jsonObject
                    val rawText = root["candidates"]?.jsonArray
                        ?.firstOrNull()?.jsonObject
                        ?.get("content")?.jsonObject
                        ?.get("parts")?.jsonArray
                        ?.firstOrNull()?.jsonObject
                        ?.get("text")?.jsonPrimitive?.content
                        ?.trim()
                        .orEmpty()

                    if (rawText.isNotBlank()) {
                        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
                        val qLine = lines.firstOrNull { it.startsWith("Q:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim()
                        val aLine = lines.firstOrNull { it.startsWith("A:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim()
                            ?: lines.lastOrNull()

                        if (!qLine.isNullOrBlank() && !aLine.isNullOrBlank()) {
                            val cleanQ = filterSttHallucinations(qLine)
                            if (cleanQ.isBlank()) return null
                            val cleanA = sanitizeUltraShortAnswer(aLine, settings.maxWordsLimit)
                            val latency = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
                            return AiOracleResult(
                                question = cleanQ,
                                answer = cleanA,
                                rawAnswer = aLine,
                                provider = AiProvider.GEMINI,
                                model = model,
                                latencyMs = latency
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Transcribes 16kHz mono WAV audio recorded by `LockScreenAudioRecorder`.
     * Supports Groq Whisper (`whisper-large-v3-turbo`), OpenAI Whisper (`whisper-1`),
     * Gemini Audio, OpenRouter Audio, and Custom OpenAI-compatible `/audio/transcriptions`.
     */
    suspend fun transcribeAudioWav(
        wavBytes: ByteArray,
        settings: OracleSettings
    ): Result<String> = withContext(Dispatchers.IO) {
        val groqKey = settings.getResolvedApiKey(AiProvider.GROQ)
        val openAiKey = settings.getResolvedApiKey(AiProvider.OPENAI)
        val geminiKey = settings.getResolvedApiKey(AiProvider.GEMINI)
        val openRouterKey = settings.getResolvedApiKey(AiProvider.OPENROUTER)
        val customKey = settings.getResolvedApiKey(AiProvider.CUSTOM_OPENAI)
        val langIso = if (settings.isEnglish) "en" else "ru"

        val errors = mutableListOf<String>()

        val providerOrder = linkedSetOf<AiProvider>().apply {
            if (groqKey.isNotBlank()) add(AiProvider.GROQ)
            add(settings.selectedProvider)
            add(AiProvider.OPENAI)
            add(AiProvider.GEMINI)
            add(AiProvider.OPENROUTER)
            add(AiProvider.CUSTOM_OPENAI)
            add(AiProvider.GROQ)
        }

        for (provider in providerOrder) {
            when (provider) {
                AiProvider.GROQ -> if (groqKey.isNotBlank()) {
                    try {
                        val transcript = transcribeViaOpenAiWhisperEndpoint(
                            wavBytes = wavBytes,
                            endpointUrl = "https://api.groq.com/openai/v1/audio/transcriptions",
                            apiKey = groqKey,
                            models = listOf("whisper-large-v3-turbo", "whisper-large-v3"),
                            langIso = langIso
                        )
                        if (transcript.isNotBlank()) return@withContext Result.success(transcript)
                    } catch (e: Exception) {
                        errors.add("Groq STT: ${e.message}")
                    }
                }

                AiProvider.OPENAI -> if (openAiKey.isNotBlank()) {
                    try {
                        val transcript = transcribeViaOpenAiWhisperEndpoint(
                            wavBytes = wavBytes,
                            endpointUrl = "https://api.openai.com/v1/audio/transcriptions",
                            apiKey = openAiKey,
                            models = listOf("gpt-4o-mini-transcribe", "whisper-1"),
                            langIso = langIso
                        )
                        if (transcript.isNotBlank()) return@withContext Result.success(transcript)
                    } catch (e: Exception) {
                        errors.add("OpenAI STT: ${e.message}")
                    }
                }

                AiProvider.GEMINI -> if (geminiKey.isNotBlank()) {
                    try {
                        val transcript = transcribeViaGeminiAudio(wavBytes, geminiKey, settings)
                        if (transcript.isNotBlank()) return@withContext Result.success(transcript)
                    } catch (e: Exception) {
                        errors.add("Gemini STT: ${e.message}")
                    }
                }

                AiProvider.OPENROUTER -> if (openRouterKey.isNotBlank()) {
                    try {
                        val transcript = transcribeViaOpenRouterAudio(wavBytes, openRouterKey)
                        if (transcript.isNotBlank()) return@withContext Result.success(transcript)
                    } catch (e: Exception) {
                        errors.add("OpenRouter STT: ${e.message}")
                    }
                }

                AiProvider.CUSTOM_OPENAI -> if (customKey.isNotBlank()) {
                    try {
                        val base = settings.getBaseUrl(AiProvider.CUSTOM_OPENAI)
                        val transcript = transcribeViaOpenAiWhisperEndpoint(
                            wavBytes = wavBytes,
                            endpointUrl = "$base/audio/transcriptions",
                            apiKey = customKey,
                            models = listOf("whisper-large-v3-turbo", "whisper-1"),
                            langIso = langIso
                        )
                        if (transcript.isNotBlank()) return@withContext Result.success(transcript)
                    } catch (e: Exception) {
                        errors.add("Custom STT: ${e.message}")
                    }
                }

                AiProvider.DEEPSEEK -> {
                    // DeepSeek does not host an audio/transcriptions endpoint; handled by fallback providers
                }
            }
        }

        val errSummary = if (errors.isNotEmpty()) {
            errors.joinToString("; ")
        } else {
            if (settings.isEnglish) {
                "Enter an API key in the 'AI & PROMPT' tab first (Groq / Gemini / OpenAI / OpenRouter)"
            } else {
                "Укажите API-ключ на вкладке 'AI & ПРОМПТ' (Groq / Gemini / OpenAI / OpenRouter)"
            }
        }
        Result.failure(IllegalStateException(errSummary))
    }

    private fun transcribeViaOpenAiWhisperEndpoint(
        wavBytes: ByteArray,
        endpointUrl: String,
        apiKey: String,
        models: List<String>,
        langIso: String
    ): String {
        var lastError: Exception? = null

        for (whisperModel in models) {
            try {
                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        "question.wav",
                        wavBytes.toRequestBody(wavMediaType)
                    )
                    .addFormDataPart("model", whisperModel)
                    .addFormDataPart("language", langIso)
                    .addFormDataPart("temperature", "0")
                    .addFormDataPart("response_format", "json")
                    .build()

                val request = Request.Builder()
                    .url(endpointUrl)
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .post(multipartBody)
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}: ${extractApiErrorMessage(bodyString)}")
                    }
                    val obj = json.parseToJsonElement(bodyString).jsonObject
                    val rawText = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
                    val cleaned = filterSttHallucinations(rawText)
                    if (cleaned.isNotBlank()) return cleaned
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (lastError != null) throw lastError
        return ""
    }

    private fun transcribeViaGeminiAudio(
        wavBytes: ByteArray,
        geminiKey: String,
        settings: OracleSettings
    ): String {
        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val userModel = settings.getActiveModel(AiProvider.GEMINI)
            .removePrefix("models/")
            .trim()
        val candidateModels = linkedSetOf(
            userModel.ifBlank { "gemini-2.5-flash" },
            "gemini-2.5-flash",
            "gemini-flash-latest",
            "gemini-2.0-flash"
        )

        var lastError: Exception? = null
        for (model in candidateModels) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${geminiKey.trim()}"

                val payload = buildJsonObject {
                    putJsonArray("contents") {
                        add(
                            buildJsonObject {
                                putJsonArray("parts") {
                                    add(
                                        buildJsonObject {
                                            put(
                                                "text",
                                                "Transcribe the spoken question from this audio verbatim. Output ONLY the transcribed words without quotes. If there is only silence, output an empty string."
                                            )
                                        }
                                    )
                                    add(
                                        buildJsonObject {
                                            putJsonObject("inlineData") {
                                                put("mimeType", "audio/wav")
                                                put("data", base64Audio)
                                            }
                                        }
                                    )
                                }
                            }
                        )
                    }
                    putJsonObject("generationConfig") {
                        put("temperature", 0.0)
                        put("maxOutputTokens", 35)
                    }
                }.toString()

                val request = Request.Builder()
                    .url(url)
                    .post(payload.toByteArray(Charsets.UTF_8).toRequestBody(jsonMediaType))
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code} ($model): ${extractApiErrorMessage(bodyString)}")
                    }
                    val root = json.parseToJsonElement(bodyString).jsonObject
                    val rawText = root["candidates"]?.jsonArray
                        ?.firstOrNull()?.jsonObject
                        ?.get("content")?.jsonObject
                        ?.get("parts")?.jsonArray
                        ?.firstOrNull()?.jsonObject
                        ?.get("text")?.jsonPrimitive?.content
                        ?.trim()
                        .orEmpty()
                    val cleaned = filterSttHallucinations(rawText)
                    if (cleaned.isNotBlank()) return cleaned
                }
            } catch (e: Exception) {
                lastError = e
                val msg = e.message.orEmpty()
                if (msg.contains("400") && msg.contains("API_KEY_INVALID", ignoreCase = true)) {
                    throw e
                }
            }
        }
        if (lastError != null) throw lastError
        return ""
    }

    private fun transcribeViaOpenRouterAudio(
        wavBytes: ByteArray,
        openRouterKey: String
    ): String {
        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val payload = buildJsonObject {
            put("model", "google/gemini-2.5-flash")
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put(
                                        "text",
                                        "Transcribe this spoken question verbatim. Output ONLY the transcribed words, nothing else. If there is no speech, output nothing."
                                    )
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("type", "input_audio")
                                    putJsonObject("input_audio") {
                                        put("data", base64Audio)
                                        put("format", "wav")
                                    }
                                }
                            )
                        }
                    }
                )
            }
            put("temperature", 0.0)
            put("max_tokens", 35)
        }.toString()

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer ${openRouterKey.trim()}")
            .header("Accept", "application/json")
            .post(payload.toByteArray(Charsets.UTF_8).toRequestBody(jsonMediaType))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${extractApiErrorMessage(bodyString)}")
            }
            val root = json.parseToJsonElement(bodyString).jsonObject
            val rawText = root["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.content
                ?.trim()
                .orEmpty()
            return filterSttHallucinations(rawText)
        }
    }

    private fun filterSttHallucinations(raw: String): String {
        val trimmed = raw.trim().trim('"', '\'', '«', '»')
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()
        val hallucinations = listOf(
            "субтитры сделал",
            "субтитры создавал",
            "редактор субтитров",
            "продолжение следует",
            "подпишись на канал",
            "спасибо за просмотр",
            "dimatorzok",
            "amara.org",
            "subtitles by",
            "thank you for watching"
        )
        if (hallucinations.any { lower.contains(it) }) {
            return ""
        }
        return trimmed
    }

    suspend fun askOracle(
        question: String,
        settings: OracleSettings
    ): Result<AiOracleResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val provider = settings.selectedProvider
        val requestedModel = settings.getActiveModel(provider)
        val apiKey = settings.getResolvedApiKey(provider)
        val baseUrl = settings.getBaseUrl(provider)

        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException(
                    if (settings.isEnglish) {
                        "Missing API key for ${provider.displayName}. Enter your key in the 'AI & PROMPT' tab."
                    } else {
                        "Не задан API-ключ для ${provider.displayName}. Введите свой ключ на вкладке 'AI & ПРОМПТ'."
                    }
                )
            )
        }

        val fullSystemInstruction = if (settings.isEnglish) {
            buildString {
                append(settings.systemPrompt.trim())
                append("\nStrict answer length limit: maximum ")
                append(settings.maxWordsLimit)
                append(" word(s). Output ONLY the answer in English with no quotes or trailing period.")
            }
        } else {
            buildString {
                append(settings.systemPrompt.trim())
                append("\nЖесткий лимит длины ответа: максимум ")
                append(settings.maxWordsLimit)
                append(" слов(а). Выводи ТОЛЬКО сам ответ без кавычек и без точки в конце.")
            }
        }

        try {
            val (rawText, usedModel) = when (provider) {
                AiProvider.GEMINI -> {
                    callGeminiWithFallback(
                        requestedModel = requestedModel,
                        apiKey = apiKey,
                        systemInstruction = fullSystemInstruction,
                        question = question
                    )
                }

                AiProvider.GROQ -> {
                    callGroqWithFallback(
                        requestedModel = requestedModel,
                        apiKey = apiKey,
                        systemInstruction = fullSystemInstruction,
                        question = question
                    )
                }

                AiProvider.OPENROUTER,
                AiProvider.OPENAI,
                AiProvider.DEEPSEEK,
                AiProvider.CUSTOM_OPENAI -> {
                    callOpenCompatibleChatApi(
                        url = "$baseUrl/chat/completions",
                        apiKey = apiKey,
                        model = requestedModel,
                        systemInstruction = fullSystemInstruction,
                        question = question,
                        isOpenRouter = provider == AiProvider.OPENROUTER
                    ) to requestedModel
                }
            }

            val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
            val sanitized = sanitizeUltraShortAnswer(rawText, settings.maxWordsLimit)

            Result.success(
                AiOracleResult(
                    question = question,
                    answer = sanitized,
                    rawAnswer = rawText.trim(),
                    provider = provider,
                    model = usedModel,
                    latencyMs = latency
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun callGroqWithFallback(
        requestedModel: String,
        apiKey: String,
        systemInstruction: String,
        question: String
    ): Pair<String, String> {
        val candidateModels = linkedSetOf(
            requestedModel.trim().ifBlank { "llama-3.3-70b-versatile" },
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant"
        )

        var firstException: Exception? = null
        for (modelCandidate in candidateModels) {
            try {
                val reply = callOpenCompatibleChatApi(
                    url = "https://api.groq.com/openai/v1/chat/completions",
                    apiKey = apiKey,
                    model = modelCandidate,
                    systemInstruction = systemInstruction,
                    question = question,
                    isOpenRouter = false
                )
                return reply to modelCandidate
            } catch (e: Exception) {
                if (firstException == null) firstException = e
                val msg = e.message.orEmpty()
                if (msg.contains("401") || msg.contains("invalid_api_key", ignoreCase = true)) {
                    throw e
                }
            }
        }
        throw firstException ?: IllegalStateException("Groq API error")
    }

    private fun callOpenCompatibleChatApi(
        url: String,
        apiKey: String,
        model: String,
        systemInstruction: String,
        question: String,
        isOpenRouter: Boolean
    ): String {
        val isReasoningModel = model.contains("r1", ignoreCase = true) ||
            model.contains("reasoner", ignoreCase = true) ||
            model.startsWith("o1", ignoreCase = true) ||
            model.startsWith("o3", ignoreCase = true) ||
            model.contains("qwen3", ignoreCase = true)

        val payload = buildJsonObject {
            put("model", model.trim())
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", systemInstruction)
                    }
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", question)
                    }
                )
            }
            if (!isReasoningModel) {
                put("temperature", 0.1)
                put("max_tokens", 24)
            }
        }.toString()

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Accept", "application/json")

        if (isOpenRouter) {
            requestBuilder
                .header("HTTP-Referer", "https://nothing.tech/glyph-oracle")
                .header("X-Title", "Glyph Oracle Nothing Phone 3")
        }

        val request = requestBuilder
            .post(payload.toByteArray(Charsets.UTF_8).toRequestBody(jsonMediaType))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detailedError = extractApiErrorMessage(bodyString)
                throw IllegalStateException(
                    "HTTP ${response.code} ($model): ${detailedError.ifBlank { response.message }}"
                )
            }

            val root = json.parseToJsonElement(bodyString).jsonObject
            val content = root["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.content

            if (content.isNullOrBlank()) {
                throw IllegalStateException("Empty response from server ($model)")
            }
            return content
        }
    }

    private fun callGeminiWithFallback(
        requestedModel: String,
        apiKey: String,
        systemInstruction: String,
        question: String
    ): Pair<String, String> {
        val cleanRequested = requestedModel.trim().removePrefix("models/").ifBlank { "gemini-2.5-flash" }
        val candidates = linkedSetOf(
            cleanRequested,
            "gemini-2.5-flash",
            "gemini-flash-latest",
            "gemini-2.0-flash"
        )
        var firstError: Exception? = null
        for (modelCandidate in candidates) {
            try {
                val reply = callGeminiRestApi(
                    model = modelCandidate,
                    apiKey = apiKey,
                    systemInstruction = systemInstruction,
                    question = question
                )
                return reply to modelCandidate
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                val msg = e.message.orEmpty()
                if (msg.contains("API_KEY_INVALID", ignoreCase = true) || msg.contains("403")) {
                    throw e
                }
            }
        }
        throw firstError ?: IllegalStateException("Gemini API error")
    }

    private fun callGeminiRestApi(
        model: String,
        apiKey: String,
        systemInstruction: String,
        question: String
    ): String {
        val cleanModel = model.trim().removePrefix("models/").ifBlank { "gemini-2.5-flash" }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=${apiKey.trim()}"

        val payload = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", systemInstruction) })
                }
            }
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", question) })
                        }
                    }
                )
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.1)
                put("maxOutputTokens", 28)
            }
        }.toString()

        val request = Request.Builder()
            .url(url)
            .post(payload.toByteArray(Charsets.UTF_8).toRequestBody(jsonMediaType))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detailedError = extractApiErrorMessage(bodyString)
                throw IllegalStateException(
                    "Gemini HTTP ${response.code} ($cleanModel): ${detailedError.ifBlank { response.message }}"
                )
            }

            val root = json.parseToJsonElement(bodyString).jsonObject
            val text = root["candidates"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content

            if (text.isNullOrBlank()) {
                throw IllegalStateException("Empty Gemini API response ($cleanModel)")
            }
            return text
        }
    }

    private fun extractApiErrorMessage(errorBody: String): String {
        if (errorBody.isBlank()) return ""
        return try {
            val obj = json.parseToJsonElement(errorBody).jsonObject
            val errorElement = obj["error"]
            when {
                errorElement != null -> {
                    val errObj = runCatching { errorElement.jsonObject }.getOrNull()
                    errObj?.get("message")?.jsonPrimitive?.content
                        ?: runCatching { errorElement.jsonPrimitive.content }.getOrNull()
                        ?: errorBody.take(180)
                }
                else -> errorBody.take(180)
            }
        } catch (_: Exception) {
            errorBody.take(180)
        }
    }

    fun sanitizeUltraShortAnswer(raw: String, maxWords: Int): String {
        val withoutThink = raw.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .trim()

        val firstLine = withoutThink
            .lines()
            .firstOrNull { it.isNotBlank() }
            ?: withoutThink

        val cleaned = firstLine
            .replace("*", "")
            .replace("\"", "")
            .replace("«", "")
            .replace("»", "")
            .replace("'", "")
            .replace("`", "")
            .trim()
            .trimEnd('.', '!', ',', ';')
            .trim()

        if (cleaned.isEmpty()) return "?"

        val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        val limited = words.take(maxWords.coerceIn(1, 5)).joinToString(" ")
        return limited.uppercase()
    }
}
