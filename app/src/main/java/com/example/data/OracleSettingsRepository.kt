package com.example.data

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AiProvider(
    val displayName: String,
    val endpointLabel: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val presetModels: List<String>,
    val isOpenAiCompatible: Boolean
) {
    GROQ(
        displayName = "Groq",
        endpointLabel = "api.groq.com/openai/v1",
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        defaultModel = "llama-3.3-70b-versatile",
        presetModels = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "meta-llama/llama-4-scout-17b-16e-instruct",
            "meta-llama/llama-4-maverick-17b-128e-instruct",
            "qwen/qwen3-32b",
            "deepseek-r1-distill-llama-70b",
            "gemma2-9b-it"
        ),
        isOpenAiCompatible = true
    ),
    GEMINI(
        displayName = "Gemini",
        endpointLabel = "generativelanguage.googleapis.com",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        defaultModel = "gemini-2.5-flash",
        presetModels = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-pro",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-3-flash-preview",
            "gemini-3.1-flash-lite-preview",
            "gemini-flash-latest",
            "gemini-pro-latest",
            "gemini-1.5-flash",
            "gemini-1.5-pro",
            "gemma-3-27b-it"
        ),
        isOpenAiCompatible = false
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        endpointLabel = "openrouter.ai/api/v1",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "openai/gpt-4o-mini",
        presetModels = listOf(
            "openai/gpt-4o-mini",
            "google/gemini-2.5-flash",
            "google/gemini-2.5-pro",
            "anthropic/claude-3.5-haiku",
            "meta-llama/llama-3.3-70b-instruct",
            "deepseek/deepseek-chat"
        ),
        isOpenAiCompatible = true
    ),
    OPENAI(
        displayName = "OpenAI",
        endpointLabel = "api.openai.com/v1",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        presetModels = listOf(
            "gpt-4o-mini",
            "gpt-4.1-mini",
            "gpt-4.1-nano",
            "gpt-4o"
        ),
        isOpenAiCompatible = true
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        endpointLabel = "api.deepseek.com/v1",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        presetModels = listOf(
            "deepseek-chat",
            "deepseek-reasoner"
        ),
        isOpenAiCompatible = true
    ),
    CUSTOM_OPENAI(
        displayName = "OpenAI-Совм.",
        endpointLabel = "Custom /v1 Endpoint",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultModel = "gemini-2.5-flash",
        presetModels = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "mistral-small-latest",
            "grok-3-mini",
            "llama-3.3-70b",
            "gpt-4o-mini"
        ),
        isOpenAiCompatible = true
    )
}

enum class VoiceCaptureMode(
    val titleRu: String,
    val subtitleRu: String,
    val titleEn: String,
    val subtitleEn: String
) {
    AUTO_HYBRID(
        titleRu = "Авто (Гибридный режим)",
        subtitleRu = "В приложении — системный распознаватель, в фоне и на заблокированном экране — прямая запись AudioRecord",
        titleEn = "Auto (Hybrid Mode)",
        subtitleEn = "Uses system recognizer in-app, and direct AudioRecord in background or on lock screen"
    ),
    ALWAYS_AUDIORECORD(
        titleRu = "Всегда прямая запись (AudioRecord)",
        subtitleRu = "Запись напрямую с микрофона и распознавание через AI API (Whisper / Gemini / OpenAI / OpenRouter)",
        titleEn = "Always Direct Mic (AudioRecord)",
        subtitleEn = "Direct microphone capture transcribed via AI API (Whisper / Gemini / OpenAI / OpenRouter)"
    );

    fun getTitle(isEnglish: Boolean): String = if (isEnglish) titleEn else titleRu
    fun getSubtitle(isEnglish: Boolean): String = if (isEnglish) subtitleEn else subtitleRu
}

data class OpenAiEndpointPreset(
    val name: String,
    val baseUrl: String,
    val sampleModel: String
)

val POPULAR_OPENAI_COMPATIBLE_ENDPOINTS = listOf(
    OpenAiEndpointPreset("Google Gemini (/v1beta/openai)", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
    OpenAiEndpointPreset("ProxyAPI (OpenAI)", "https://api.proxyapi.ru/openai/v1", "gpt-4o-mini"),
    OpenAiEndpointPreset("Mistral AI", "https://api.mistral.ai/v1", "mistral-small-latest"),
    OpenAiEndpointPreset("xAI Grok", "https://api.x.ai/v1", "grok-3-mini"),
    OpenAiEndpointPreset("Together AI", "https://api.together.xyz/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo"),
    OpenAiEndpointPreset("Cerebras", "https://api.cerebras.ai/v1", "llama-3.3-70b")
)

val POPULAR_GEMINI_ENDPOINTS = listOf(
    OpenAiEndpointPreset("Google Official (v1beta)", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash"),
    OpenAiEndpointPreset("Google Official (v1alpha)", "https://generativelanguage.googleapis.com/v1alpha", "gemini-2.5-flash"),
    OpenAiEndpointPreset("ProxyAPI (Google RU)", "https://api.proxyapi.ru/google/v1beta", "gemini-2.5-flash")
)

fun resolveSystemSpeechLanguage(): String {
    val sysLang = Locale.getDefault().language.lowercase(Locale.ROOT)
    return if (sysLang.startsWith("ru")) "ru-RU" else "en-US"
}

data class OracleSettings(
    val selectedProvider: AiProvider = AiProvider.GROQ,
    val groqModel: String = AiProvider.GROQ.defaultModel,
    val geminiModel: String = AiProvider.GEMINI.defaultModel,
    val geminiBaseUrl: String = AiProvider.GEMINI.defaultBaseUrl,
    val openRouterModel: String = AiProvider.OPENROUTER.defaultModel,
    val openAiModel: String = AiProvider.OPENAI.defaultModel,
    val deepSeekModel: String = AiProvider.DEEPSEEK.defaultModel,
    val customOpenAiModel: String = AiProvider.CUSTOM_OPENAI.defaultModel,
    val customOpenAiBaseUrl: String = AiProvider.CUSTOM_OPENAI.defaultBaseUrl,
    val customGroqModels: List<String> = emptyList(),
    val customGeminiModels: List<String> = emptyList(),
    val customOpenRouterModels: List<String> = emptyList(),
    val customOpenAiModels: List<String> = emptyList(),
    val customDeepSeekModels: List<String> = emptyList(),
    val customCompatibleModels: List<String> = emptyList(),
    val customGroqKey: String = "",
    val customGeminiKey: String = "",
    val customOpenRouterKey: String = "",
    val customOpenAiKey: String = "",
    val customDeepSeekKey: String = "",
    val customCompatibleKey: String = "",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT_RU,
    val maxWordsLimit: Int = 2,
    val matrixBrightness: Int = 255,
    val scrollSpeedMs: Int = 75,
    val silenceTimeoutMs: Int = 1050,
    val speechLanguage: String = resolveSystemSpeechLanguage(),
    val voiceCaptureMode: VoiceCaptureMode = VoiceCaptureMode.AUTO_HYBRID
) {
    val isEnglish: Boolean
        get() = speechLanguage.startsWith("en", ignoreCase = true)

    val hasActiveApiKey: Boolean
        get() = getResolvedApiKey(selectedProvider).isNotBlank()

    fun getRawModelInput(provider: AiProvider = selectedProvider): String = when (provider) {
        AiProvider.GROQ -> groqModel
        AiProvider.GEMINI -> geminiModel
        AiProvider.OPENROUTER -> openRouterModel
        AiProvider.OPENAI -> openAiModel
        AiProvider.DEEPSEEK -> deepSeekModel
        AiProvider.CUSTOM_OPENAI -> customOpenAiModel
    }

    fun getActiveModel(provider: AiProvider = selectedProvider): String = when (provider) {
        AiProvider.GROQ -> groqModel.trim().ifBlank { AiProvider.GROQ.defaultModel }
        AiProvider.GEMINI -> geminiModel.trim().ifBlank { AiProvider.GEMINI.defaultModel }
        AiProvider.OPENROUTER -> openRouterModel.trim().ifBlank { AiProvider.OPENROUTER.defaultModel }
        AiProvider.OPENAI -> openAiModel.trim().ifBlank { AiProvider.OPENAI.defaultModel }
        AiProvider.DEEPSEEK -> deepSeekModel.trim().ifBlank { AiProvider.DEEPSEEK.defaultModel }
        AiProvider.CUSTOM_OPENAI -> customOpenAiModel.trim().ifBlank { AiProvider.CUSTOM_OPENAI.defaultModel }
    }

    fun getBaseUrl(provider: AiProvider = selectedProvider): String = when (provider) {
        AiProvider.GEMINI -> geminiBaseUrl.trim()
            .trimEnd('/')
            .ifBlank { AiProvider.GEMINI.defaultBaseUrl }
        AiProvider.CUSTOM_OPENAI -> customOpenAiBaseUrl.trim()
            .trimEnd('/')
            .ifBlank { AiProvider.CUSTOM_OPENAI.defaultBaseUrl }
        else -> provider.defaultBaseUrl
    }

    fun getCustomModelsForProvider(provider: AiProvider = selectedProvider): List<String> = when (provider) {
        AiProvider.GROQ -> customGroqModels
        AiProvider.GEMINI -> customGeminiModels
        AiProvider.OPENROUTER -> customOpenRouterModels
        AiProvider.OPENAI -> customOpenAiModels
        AiProvider.DEEPSEEK -> customDeepSeekModels
        AiProvider.CUSTOM_OPENAI -> customCompatibleModels
    }

    fun getAllModelsForProvider(provider: AiProvider = selectedProvider): List<String> {
        return (getCustomModelsForProvider(provider) + provider.presetModels).distinct()
    }

    fun getRawApiKeyInput(provider: AiProvider = selectedProvider): String = when (provider) {
        AiProvider.GROQ -> customGroqKey
        AiProvider.GEMINI -> customGeminiKey
        AiProvider.OPENROUTER -> customOpenRouterKey
        AiProvider.OPENAI -> customOpenAiKey
        AiProvider.DEEPSEEK -> customDeepSeekKey
        AiProvider.CUSTOM_OPENAI -> customCompatibleKey
    }

    fun getResolvedApiKey(provider: AiProvider = selectedProvider): String {
        return cleanApiKeyString(getRawApiKeyInput(provider))
    }

    private fun cleanApiKeyString(raw: String): String {
        return raw.trim()
            .removePrefix("Bearer ")
            .removePrefix("bearer ")
            .replace("\"", "")
            .replace("'", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT_RU =
            "Отвечай ультра-кратко: строго 1–2 слова в верхнем регистре, без точек и пояснений.\n" +
                "Правила:\n" +
                "1) Да/нет -> ДА или НЕТ.\n" +
                "2) Число или расчёт -> только число (42, 100%).\n" +
                "3) Выбор «А или Б» -> только лучший вариант (RTX 5070)."

        const val DEFAULT_SYSTEM_PROMPT_EN =
            "Answer ultra-briefly: strictly 1–2 UPPERCASE words, no periods or explanations.\n" +
                "Rules:\n" +
                "1) Yes/no -> YES or NO.\n" +
                "2) Number or math -> only the number (42, 100%).\n" +
                "3) Choice \"A or B\" -> only the better option (RTX 5070)."

        const val DEFAULT_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT_RU
    }
}

class OracleSettingsRepository private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("glyph_oracle_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadFromPrefs())
    val settings: StateFlow<OracleSettings> = _settings.asStateFlow()

    private fun loadFromPrefs(): OracleSettings {
        val providerName = prefs.getString("selected_provider", AiProvider.GROQ.name) ?: AiProvider.GROQ.name
        val provider = runCatching { AiProvider.valueOf(providerName) }.getOrDefault(AiProvider.GROQ)

        val modeName = prefs.getString("voice_capture_mode", VoiceCaptureMode.AUTO_HYBRID.name)
            ?: VoiceCaptureMode.AUTO_HYBRID.name
        val voiceMode = runCatching { VoiceCaptureMode.valueOf(modeName) }
            .getOrDefault(VoiceCaptureMode.AUTO_HYBRID)

        val rawGeminiModel = prefs.getString("gemini_model", AiProvider.GEMINI.defaultModel)
            ?: AiProvider.GEMINI.defaultModel
        val validGeminiModel = if (rawGeminiModel == "gemini-3.5-flash") {
            AiProvider.GEMINI.defaultModel
        } else {
            rawGeminiModel
        }

        val savedLang = prefs.getString("speech_language", null)
        val effectiveLang = if (savedLang.isNullOrBlank()) {
            val detected = resolveSystemSpeechLanguage()
            prefs.edit().putString("speech_language", detected).apply()
            detected
        } else {
            savedLang
        }

        val isEn = effectiveLang.startsWith("en", ignoreCase = true)
        val defaultPrompt = if (isEn) {
            OracleSettings.DEFAULT_SYSTEM_PROMPT_EN
        } else {
            OracleSettings.DEFAULT_SYSTEM_PROMPT_RU
        }

        val savedPrompt = prefs.getString("system_prompt", defaultPrompt) ?: defaultPrompt
        val isStandardPrompt = savedPrompt == OracleSettings.DEFAULT_SYSTEM_PROMPT_RU ||
            savedPrompt == OracleSettings.DEFAULT_SYSTEM_PROMPT_EN ||
            savedPrompt.contains("светодиодной матрицы 25x25", ignoreCase = true) ||
            savedPrompt.contains("25x25 LED Glyph Matrix", ignoreCase = true)

        val migratedPrompt = if (isStandardPrompt) {
            defaultPrompt
        } else {
            savedPrompt
        }

        return OracleSettings(
            selectedProvider = provider,
            groqModel = prefs.getString("groq_model", AiProvider.GROQ.defaultModel) ?: AiProvider.GROQ.defaultModel,
            geminiModel = validGeminiModel,
            geminiBaseUrl = prefs.getString("gemini_base_url", AiProvider.GEMINI.defaultBaseUrl) ?: AiProvider.GEMINI.defaultBaseUrl,
            openRouterModel = prefs.getString("openrouter_model", AiProvider.OPENROUTER.defaultModel) ?: AiProvider.OPENROUTER.defaultModel,
            openAiModel = prefs.getString("openai_model", AiProvider.OPENAI.defaultModel) ?: AiProvider.OPENAI.defaultModel,
            deepSeekModel = prefs.getString("deepseek_model", AiProvider.DEEPSEEK.defaultModel) ?: AiProvider.DEEPSEEK.defaultModel,
            customOpenAiModel = prefs.getString("custom_openai_model", AiProvider.CUSTOM_OPENAI.defaultModel) ?: AiProvider.CUSTOM_OPENAI.defaultModel,
            customOpenAiBaseUrl = prefs.getString("custom_openai_base_url", AiProvider.CUSTOM_OPENAI.defaultBaseUrl) ?: AiProvider.CUSTOM_OPENAI.defaultBaseUrl,
            customGroqModels = parseList(prefs.getString("custom_groq_models", "")),
            customGeminiModels = parseList(prefs.getString("custom_gemini_models", "")),
            customOpenRouterModels = parseList(prefs.getString("custom_openrouter_models", "")),
            customOpenAiModels = parseList(prefs.getString("custom_openai_models", "")),
            customDeepSeekModels = parseList(prefs.getString("custom_deepseek_models", "")),
            customCompatibleModels = parseList(prefs.getString("custom_compatible_models", "")),
            customGroqKey = prefs.getString("custom_groq_key", "") ?: "",
            customGeminiKey = prefs.getString("custom_gemini_key", "") ?: "",
            customOpenRouterKey = prefs.getString("custom_openrouter_key", "") ?: "",
            customOpenAiKey = prefs.getString("custom_openai_key", "") ?: "",
            customDeepSeekKey = prefs.getString("custom_deepseek_key", "") ?: "",
            customCompatibleKey = prefs.getString("custom_compatible_key", "") ?: "",
            systemPrompt = migratedPrompt,
            maxWordsLimit = prefs.getInt("max_words_limit", 2).coerceIn(1, 5),
            matrixBrightness = prefs.getInt("matrix_brightness", 255).coerceIn(40, 255),
            scrollSpeedMs = prefs.getInt("scroll_speed_ms", 75).coerceIn(35, 160),
            silenceTimeoutMs = prefs.getInt("silence_timeout_ms", 1050).coerceIn(600, 2000),
            speechLanguage = effectiveLang,
            voiceCaptureMode = voiceMode
        )
    }

    fun updateSettings(transform: (OracleSettings) -> OracleSettings) {
        val updated = transform(_settings.value)
        prefs.edit()
            .putString("selected_provider", updated.selectedProvider.name)
            .putString("groq_model", updated.groqModel)
            .putString("gemini_model", updated.geminiModel)
            .putString("gemini_base_url", updated.geminiBaseUrl)
            .putString("openrouter_model", updated.openRouterModel)
            .putString("openai_model", updated.openAiModel)
            .putString("deepseek_model", updated.deepSeekModel)
            .putString("custom_openai_model", updated.customOpenAiModel)
            .putString("custom_openai_base_url", updated.customOpenAiBaseUrl)
            .putString("custom_groq_models", serializeList(updated.customGroqModels))
            .putString("custom_gemini_models", serializeList(updated.customGeminiModels))
            .putString("custom_openrouter_models", serializeList(updated.customOpenRouterModels))
            .putString("custom_openai_models", serializeList(updated.customOpenAiModels))
            .putString("custom_deepseek_models", serializeList(updated.customDeepSeekModels))
            .putString("custom_compatible_models", serializeList(updated.customCompatibleModels))
            .putString("custom_groq_key", updated.customGroqKey)
            .putString("custom_gemini_key", updated.customGeminiKey)
            .putString("custom_openrouter_key", updated.customOpenRouterKey)
            .putString("custom_openai_key", updated.customOpenAiKey)
            .putString("custom_deepseek_key", updated.customDeepSeekKey)
            .putString("custom_compatible_key", updated.customCompatibleKey)
            .putString("system_prompt", updated.systemPrompt)
            .putInt("max_words_limit", updated.maxWordsLimit)
            .putInt("matrix_brightness", updated.matrixBrightness)
            .putInt("scroll_speed_ms", updated.scrollSpeedMs)
            .putInt("silence_timeout_ms", updated.silenceTimeoutMs)
            .putString("speech_language", updated.speechLanguage)
            .putString("voice_capture_mode", updated.voiceCaptureMode.name)
            .apply()
        _settings.value = updated
    }

    fun addCustomModel(provider: AiProvider, modelId: String) {
        val clean = modelId.trim()
        if (clean.isEmpty()) return
        updateSettings { current ->
            when (provider) {
                AiProvider.GROQ -> current.copy(
                    groqModel = clean,
                    customGroqModels = (listOf(clean) + current.customGroqModels).distinct()
                )
                AiProvider.GEMINI -> current.copy(
                    geminiModel = clean,
                    customGeminiModels = (listOf(clean) + current.customGeminiModels).distinct()
                )
                AiProvider.OPENROUTER -> current.copy(
                    openRouterModel = clean,
                    customOpenRouterModels = (listOf(clean) + current.customOpenRouterModels).distinct()
                )
                AiProvider.OPENAI -> current.copy(
                    openAiModel = clean,
                    customOpenAiModels = (listOf(clean) + current.customOpenAiModels).distinct()
                )
                AiProvider.DEEPSEEK -> current.copy(
                    deepSeekModel = clean,
                    customDeepSeekModels = (listOf(clean) + current.customDeepSeekModels).distinct()
                )
                AiProvider.CUSTOM_OPENAI -> current.copy(
                    customOpenAiModel = clean,
                    customCompatibleModels = (listOf(clean) + current.customCompatibleModels).distinct()
                )
            }
        }
    }

    fun removeCustomModel(provider: AiProvider, modelId: String) {
        val clean = modelId.trim()
        if (clean.isEmpty()) return
        updateSettings { current ->
            when (provider) {
                AiProvider.GROQ -> {
                    val nextList = current.customGroqModels.filterNot { it == clean }
                    val nextActive = if (current.groqModel == clean) AiProvider.GROQ.defaultModel else current.groqModel
                    current.copy(groqModel = nextActive, customGroqModels = nextList)
                }
                AiProvider.GEMINI -> {
                    val nextList = current.customGeminiModels.filterNot { it == clean }
                    val nextActive = if (current.geminiModel == clean) AiProvider.GEMINI.defaultModel else current.geminiModel
                    current.copy(geminiModel = nextActive, customGeminiModels = nextList)
                }
                AiProvider.OPENROUTER -> {
                    val nextList = current.customOpenRouterModels.filterNot { it == clean }
                    val nextActive = if (current.openRouterModel == clean) AiProvider.OPENROUTER.defaultModel else current.openRouterModel
                    current.copy(openRouterModel = nextActive, customOpenRouterModels = nextList)
                }
                AiProvider.OPENAI -> {
                    val nextList = current.customOpenAiModels.filterNot { it == clean }
                    val nextActive = if (current.openAiModel == clean) AiProvider.OPENAI.defaultModel else current.openAiModel
                    current.copy(openAiModel = nextActive, customOpenAiModels = nextList)
                }
                AiProvider.DEEPSEEK -> {
                    val nextList = current.customDeepSeekModels.filterNot { it == clean }
                    val nextActive = if (current.deepSeekModel == clean) AiProvider.DEEPSEEK.defaultModel else current.deepSeekModel
                    current.copy(deepSeekModel = nextActive, customDeepSeekModels = nextList)
                }
                AiProvider.CUSTOM_OPENAI -> {
                    val nextList = current.customCompatibleModels.filterNot { it == clean }
                    val nextActive = if (current.customOpenAiModel == clean) AiProvider.CUSTOM_OPENAI.defaultModel else current.customOpenAiModel
                    current.copy(customOpenAiModel = nextActive, customCompatibleModels = nextList)
                }
            }
        }
    }

    fun addFetchedModels(provider: AiProvider, models: List<String>) {
        val cleaned = models.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        updateSettings { current ->
            when (provider) {
                AiProvider.GROQ -> current.copy(
                    customGroqModels = (cleaned + current.customGroqModels).distinct()
                )
                AiProvider.GEMINI -> current.copy(
                    customGeminiModels = (cleaned + current.customGeminiModels).distinct()
                )
                AiProvider.OPENROUTER -> current.copy(
                    customOpenRouterModels = (cleaned + current.customOpenRouterModels).distinct()
                )
                AiProvider.OPENAI -> current.copy(
                    customOpenAiModels = (cleaned + current.customOpenAiModels).distinct()
                )
                AiProvider.DEEPSEEK -> current.copy(
                    customDeepSeekModels = (cleaned + current.customDeepSeekModels).distinct()
                )
                AiProvider.CUSTOM_OPENAI -> current.copy(
                    customCompatibleModels = (cleaned + current.customCompatibleModels).distinct()
                )
            }
        }
    }

    fun resetSystemPrompt() {
        updateSettings { current ->
            val defaultPrompt = if (current.isEnglish) {
                OracleSettings.DEFAULT_SYSTEM_PROMPT_EN
            } else {
                OracleSettings.DEFAULT_SYSTEM_PROMPT_RU
            }
            current.copy(systemPrompt = defaultPrompt, maxWordsLimit = 2)
        }
    }

    fun clearAllApiKeys() {
        updateSettings { current ->
            current.copy(
                customGroqKey = "",
                customGeminiKey = "",
                customOpenRouterKey = "",
                customOpenAiKey = "",
                customDeepSeekKey = "",
                customCompatibleKey = ""
            )
        }
    }

    private fun parseList(raw: String?): List<String> {
        return raw.orEmpty()
            .split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun serializeList(list: List<String>): String {
        return list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("|")
    }

    companion object {
        @Volatile
        private var INSTANCE: OracleSettingsRepository? = null

        fun getInstance(context: Context): OracleSettingsRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = OracleSettingsRepository(context)
                INSTANCE = instance
                instance
            }
        }
    }
}
