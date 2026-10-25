package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AiProvider
import com.example.data.OracleDatabase
import com.example.data.OracleHistoryItem
import com.example.data.OracleHistoryRepository
import com.example.data.OracleSettings
import com.example.data.OracleSettingsRepository
import com.example.data.VoiceCaptureMode
import com.example.data.resolveSystemSpeechLanguage
import com.example.glyph.GlyphOracleCoordinator
import com.example.glyph.ToyOracleState
import com.example.network.AiOracleClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OracleViewModel(application: Application) : AndroidViewModel(application) {

    private val coordinator = GlyphOracleCoordinator.getInstance(application)
    private val settingsRepository = OracleSettingsRepository.getInstance(application)
    private val historyRepository = OracleHistoryRepository(
        OracleDatabase.getInstance(application).historyDao()
    )

    val oracleState: StateFlow<ToyOracleState> = coordinator.oracleState
    val matrixFrame: StateFlow<IntArray> = coordinator.matrixFrame
    val isHardwareConnected: StateFlow<Boolean> = coordinator.isHardwareConnected
    val isToyServiceBound: StateFlow<Boolean> = coordinator.isToyServiceBound

    val settings: StateFlow<OracleSettings> = settingsRepository.settings

    private val _apiDiagnosticMessage = MutableStateFlow<String?>(null)
    val apiDiagnosticMessage: StateFlow<String?> = _apiDiagnosticMessage.asStateFlow()

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

    val history: StateFlow<List<OracleHistoryItem>> = historyRepository.allHistory
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun triggerGlyphLongPress() {
        coordinator.onGlyphButtonLongPress()
    }

    fun stopVoiceListening() {
        coordinator.stopVoiceListening()
    }

    fun submitTextQuestion(question: String) {
        coordinator.submitQuestion(question)
    }

    fun replayOnMatrix(item: OracleHistoryItem) {
        coordinator.displayAnswerOnMatrix(
            question = item.question,
            answer = item.answer,
            providerName = item.provider,
            latencyMs = item.latencyMs
        )
    }

    fun previewAnswerDirectly(label: String, sampleAnswer: String) {
        coordinator.displayAnswerOnMatrix(
            question = label,
            answer = sampleAnswer
        )
    }

    fun selectProvider(provider: AiProvider) {
        _apiDiagnosticMessage.value = null
        settingsRepository.updateSettings { it.copy(selectedProvider = provider) }
    }

    fun updateActiveModel(model: String) {
        settingsRepository.updateSettings { current ->
            when (current.selectedProvider) {
                AiProvider.GROQ -> current.copy(groqModel = model)
                AiProvider.GEMINI -> current.copy(geminiModel = model)
                AiProvider.OPENROUTER -> current.copy(openRouterModel = model)
                AiProvider.OPENAI -> current.copy(openAiModel = model)
                AiProvider.DEEPSEEK -> current.copy(deepSeekModel = model)
                AiProvider.CUSTOM_OPENAI -> current.copy(customOpenAiModel = model)
            }
        }
    }

    fun updateCustomOpenAiBaseUrl(baseUrl: String) {
        settingsRepository.updateSettings { current ->
            current.copy(customOpenAiBaseUrl = baseUrl)
        }
    }

    fun applyOpenAiEndpointPreset(baseUrl: String, sampleModel: String) {
        settingsRepository.updateSettings { current ->
            current.copy(
                selectedProvider = AiProvider.CUSTOM_OPENAI,
                customOpenAiBaseUrl = baseUrl,
                customOpenAiModel = sampleModel,
                customCompatibleModels = (listOf(sampleModel) + current.customCompatibleModels).distinct()
            )
        }
    }

    fun addCustomModel(modelId: String) {
        val current = settings.value
        val currentProvider = current.selectedProvider
        settingsRepository.addCustomModel(currentProvider, modelId)
        _apiDiagnosticMessage.value = if (current.isEnglish) {
            "Model «${modelId.trim()}» added and selected for ${currentProvider.displayName}"
        } else {
            "Модель «${modelId.trim()}» добавлена и выбрана для ${currentProvider.displayName}"
        }
    }

    fun removeCustomModel(modelId: String) {
        val current = settings.value
        val currentProvider = current.selectedProvider
        settingsRepository.removeCustomModel(currentProvider, modelId)
        _apiDiagnosticMessage.value = if (current.isEnglish) {
            "Model «${modelId.trim()}» removed"
        } else {
            "Модель «${modelId.trim()}» удалена из списка"
        }
    }

    fun fetchModelsFromApi() {
        val current = settings.value
        val provider = current.selectedProvider
        val key = current.getResolvedApiKey(provider)
        val baseUrl = current.getBaseUrl(provider)
        val isEn = current.isEnglish

        if (key.isBlank()) {
            _apiDiagnosticMessage.value = if (isEn) {
                "Enter an API key for ${provider.displayName} first"
            } else {
                "Сначала введите API-ключ для ${provider.displayName}"
            }
            return
        }

        _isFetchingModels.value = true
        _apiDiagnosticMessage.value = if (isEn) {
            "Fetching models from ${provider.displayName}..."
        } else {
            "Загрузка списка моделей с сервера ${provider.displayName}..."
        }
        viewModelScope.launch {
            val result = AiOracleClient.fetchAvailableModels(provider, key, baseUrl, isEn)
            _isFetchingModels.value = false
            result.fold(
                onSuccess = { models ->
                    settingsRepository.addFetchedModels(provider, models)
                    _apiDiagnosticMessage.value = if (isEn) {
                        "Loaded ${models.size} models (${provider.displayName}). Tap any chip below."
                    } else {
                        "Загружено моделей (${provider.displayName}): ${models.size}. Выберите любую из списка ниже."
                    }
                },
                onFailure = { err ->
                    _apiDiagnosticMessage.value = if (isEn) {
                        "Error loading models: ${err.message}"
                    } else {
                        "Ошибка загрузки моделей: ${err.message}"
                    }
                }
            )
        }
    }

    fun testActiveProviderConnection() {
        val current = settings.value
        val isEn = current.isEnglish
        if (!current.hasActiveApiKey) {
            _apiDiagnosticMessage.value = if (isEn) {
                "Enter an API key for ${current.selectedProvider.displayName} first"
            } else {
                "Сначала введите API-ключ для ${current.selectedProvider.displayName}"
            }
            return
        }

        _apiDiagnosticMessage.value = if (isEn) {
            "Testing ${current.selectedProvider.displayName} (${current.getActiveModel()})..."
        } else {
            "Проверка ${current.selectedProvider.displayName} (${current.getActiveModel()})..."
        }
        viewModelScope.launch {
            val testPrompt = if (isEn) "What is 2+2?" else "Сколько будет 2+2?"
            val res = AiOracleClient.askOracle(testPrompt, current)
            res.fold(
                onSuccess = { ok ->
                    _apiDiagnosticMessage.value = if (isEn) {
                        "SUCCESS (${ok.provider.displayName} • ${ok.model}): «${ok.answer}» in ${ok.latencyMs} ms"
                    } else {
                        "УСПЕШНО (${ok.provider.displayName} • ${ok.model}): ответ «${ok.answer}» за ${ok.latencyMs} мс"
                    }
                    coordinator.displayAnswerOnMatrix(
                        question = if (isEn) "API Test" else "Тест API",
                        answer = ok.answer,
                        providerName = ok.provider.displayName,
                        latencyMs = ok.latencyMs
                    )
                },
                onFailure = { err ->
                    _apiDiagnosticMessage.value = if (isEn) {
                        "ERROR: ${err.message}"
                    } else {
                        "ОШИБКА: ${err.message}"
                    }
                }
            )
        }
    }

    fun updateCustomApiKey(provider: AiProvider, key: String) {
        settingsRepository.updateSettings { current ->
            when (provider) {
                AiProvider.GROQ -> current.copy(customGroqKey = key)
                AiProvider.GEMINI -> current.copy(customGeminiKey = key)
                AiProvider.OPENROUTER -> current.copy(customOpenRouterKey = key)
                AiProvider.OPENAI -> current.copy(customOpenAiKey = key)
                AiProvider.DEEPSEEK -> current.copy(customDeepSeekKey = key)
                AiProvider.CUSTOM_OPENAI -> current.copy(customCompatibleKey = key)
            }
        }
    }

    fun clearAllApiKeys() {
        settingsRepository.clearAllApiKeys()
        val isEn = settings.value.isEnglish
        _apiDiagnosticMessage.value = if (isEn) {
            "All saved API keys cleared from device."
        } else {
            "Все сохраненные API-ключи очищены с устройства."
        }
    }

    fun updateSystemPrompt(prompt: String) {
        settingsRepository.updateSettings { it.copy(systemPrompt = prompt) }
    }

    fun resetSystemPrompt() {
        settingsRepository.resetSystemPrompt()
    }

    fun updateMaxWordsLimit(limit: Int) {
        settingsRepository.updateSettings { it.copy(maxWordsLimit = limit.coerceIn(1, 5)) }
    }

    fun updateMatrixBrightness(brightness: Int) {
        settingsRepository.updateSettings { it.copy(matrixBrightness = brightness.coerceIn(40, 255)) }
        coordinator.refreshBrightnessPreview()
    }

    fun updateScrollSpeedMs(speedMs: Int) {
        settingsRepository.updateSettings { it.copy(scrollSpeedMs = speedMs.coerceIn(35, 160)) }
    }

    fun updateSilenceTimeoutMs(timeoutMs: Int) {
        settingsRepository.updateSettings { it.copy(silenceTimeoutMs = timeoutMs.coerceIn(600, 2000)) }
    }

    fun updateSpeechLanguage(lang: String) {
        settingsRepository.updateSettings { current ->
            val switchingToEn = lang.startsWith("en", ignoreCase = true)
            val updatedPrompt = when {
                switchingToEn && current.systemPrompt == OracleSettings.DEFAULT_SYSTEM_PROMPT_RU ->
                    OracleSettings.DEFAULT_SYSTEM_PROMPT_EN
                !switchingToEn && current.systemPrompt == OracleSettings.DEFAULT_SYSTEM_PROMPT_EN ->
                    OracleSettings.DEFAULT_SYSTEM_PROMPT_RU
                else -> current.systemPrompt
            }
            current.copy(
                speechLanguage = lang,
                systemPrompt = updatedPrompt
            )
        }
    }

    fun updateVoiceCaptureMode(mode: VoiceCaptureMode) {
        settingsRepository.updateSettings { it.copy(voiceCaptureMode = mode) }
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch {
            historyRepository.deleteById(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            historyRepository.clearAll()
        }
    }
}
