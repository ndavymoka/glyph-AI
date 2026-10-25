package com.example.glyph

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Direct hardware microphone recorder using `android.media.AudioRecord`.
 *
 * Speed & Reliability Optimizations:
 * - Configurable silence pause window (`silenceTimeoutMs`, default 1050ms) for snappy auto-stop.
 * - Automatically trims trailing post-speech silence from the recorded PCM buffer before encoding
 *   to WAV, cutting payload size by 30-50% for faster upload to Whisper / Gemini / OpenAI.
 * - Skips first 180ms to avoid haptic motor buzz (`vibrateShortTick`) and physical button click.
 * - Hardware `AutomaticGainControl` + software 16-bit PCM peak normalization.
 */
class LockScreenAudioRecorder(
    private val scope: CoroutineScope,
    private val silenceTimeoutMs: Long = 1050L,
    private val isEnglish: Boolean = false,
    private val onRmsChanged: (Float) -> Unit,
    private val onRecordingComplete: (ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {
    private var recordingJob: Job? = null

    @Volatile
    private var shouldStopEarly: Boolean = false

    val isRecording: Boolean
        get() = recordingJob?.isActive == true

    @SuppressLint("MissingPermission")
    fun start() {
        stopCancel()
        shouldStopEarly = false

        recordingJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 16_000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                .coerceAtLeast(3200)
            val bufferSizeInBytes = minBufSize * 2

            var audioRecord: AudioRecord? = null
            var agc: AutomaticGainControl? = null
            var noiseSuppressor: NoiseSuppressor? = null

            var recordedPcmBytes: ByteArray? = null
            var lastVoiceBytePosition = 0
            var maxPeakObserved = 0
            var errorMessage: String? = null

            try {
                audioRecord = createInitializedAudioRecord(
                    sampleRate = sampleRate,
                    channelConfig = channelConfig,
                    audioFormat = audioFormat,
                    bufferSizeInBytes = bufferSizeInBytes
                )

                if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    errorMessage = if (isEnglish) {
                        "Failed to initialize microphone"
                    } else {
                        "Не удалось инициализировать микрофон"
                    }
                } else {
                    val sessionId = audioRecord.audioSessionId
                    try {
                        if (AutomaticGainControl.isAvailable()) {
                            agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                        }
                    } catch (_: Throwable) {
                    }
                    try {
                        if (NoiseSuppressor.isAvailable()) {
                            noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                        }
                    } catch (_: Throwable) {
                    }

                    audioRecord.startRecording()
                    if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        errorMessage = if (isEnglish) {
                            "Microphone access blocked by OS. Open the app once."
                        } else {
                            "Запись с микрофона заблокирована системой. Откройте приложение один раз."
                        }
                    } else {
                        val pcmStream = ByteArrayOutputStream(sampleRate * 2 * 5)
                        val shortBuffer = ShortArray(512) // 32ms per chunk at 16kHz for faster responsiveness
                        val byteChunk = ByteArray(shortBuffer.size * 2)

                        val startTime = System.currentTimeMillis()
                        val effectiveSilenceWindow = silenceTimeoutMs.coerceIn(600L, 2000L)
                        var speechStarted = false
                        var speechStartTime = 0L
                        var lastVoiceTimestamp = startTime
                        var consecutiveVoiceChunks = 0
                        var noiseFloorRms = 115.0

                        while (isActive && !shouldStopEarly) {
                            val readCount = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                            if (readCount <= 0) continue

                            val now = System.currentTimeMillis()
                            val elapsed = now - startTime

                            // Skip the first 180ms so the 35ms haptic vibration motor and physical
                            // Glyph Button click on the back of the phone are excluded.
                            if (elapsed < 180L) {
                                continue
                            }

                            var sumSquares = 0.0
                            var chunkPeak = 0
                            for (i in 0 until readCount) {
                                val sample = shortBuffer[i].toInt()
                                val absSample = abs(sample)
                                if (absSample > chunkPeak) chunkPeak = absSample
                                sumSquares += sample.toDouble() * sample.toDouble()
                                byteChunk[i * 2] = (sample and 0xFF).toByte()
                                byteChunk[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                            }

                            if (chunkPeak > maxPeakObserved) {
                                maxPeakObserved = chunkPeak
                            }
                            pcmStream.write(byteChunk, 0, readCount * 2)

                            val rms = sqrt(sumSquares / readCount)

                            // Smoothly adapt ambient noise floor without letting loud voice spike it
                            if (!speechStarted && elapsed < 500L && rms < 300.0) {
                                noiseFloorRms = (noiseFloorRms * 0.75) + (rms * 0.25)
                            } else if (rms < noiseFloorRms) {
                                noiseFloorRms = (noiseFloorRms * 0.85) + (rms * 0.15)
                            }
                            noiseFloorRms = noiseFloorRms.coerceIn(50.0, 250.0)

                            val onsetThreshold = (noiseFloorRms * 1.70).coerceIn(115.0, 380.0)
                            val sustainThreshold = (noiseFloorRms * 1.28).coerceIn(80.0, 250.0)

                            if (!speechStarted) {
                                if (rms > onsetThreshold || chunkPeak > 1050) {
                                    consecutiveVoiceChunks++
                                    if (consecutiveVoiceChunks >= 2) {
                                        speechStarted = true
                                        speechStartTime = now
                                        lastVoiceTimestamp = now
                                        lastVoiceBytePosition = pcmStream.size()
                                    }
                                } else {
                                    consecutiveVoiceChunks = 0
                                }
                            } else {
                                if (rms > sustainThreshold || chunkPeak > 700) {
                                    lastVoiceTimestamp = now
                                    lastVoiceBytePosition = pcmStream.size()
                                }
                            }

                            // Map RMS to -2..10 dB scale for the 25x25 LED matrix wave animation
                            val normalizedDb = if (rms > 10.0) {
                                ((20.0 * log10(rms / 120.0)).toFloat()).coerceIn(-2f, 10f)
                            } else {
                                -2f
                            }
                            onRmsChanged(normalizedDb)

                            // Fast auto-stop once the user pauses for silenceTimeoutMs (default ~1.05s)
                            if (
                                speechStarted &&
                                (now - lastVoiceTimestamp) > effectiveSilenceWindow &&
                                (now - speechStartTime) > 550L
                            ) {
                                break
                            }

                            // Wait up to 5.2s for the user to start speaking
                            if (!speechStarted && elapsed > 5200L) {
                                break
                            }

                            // Maximum total recording duration 9.5s
                            if (elapsed > 9_500L) {
                                break
                            }
                        }

                        try {
                            audioRecord.stop()
                        } catch (_: Throwable) {
                        }

                        recordedPcmBytes = pcmStream.toByteArray()
                    }
                }
            } catch (t: Throwable) {
                errorMessage = if (isEnglish) {
                    "Audio record error: ${t.localizedMessage ?: "unknown"}"
                } else {
                    "Ошибка записи звука: ${t.localizedMessage ?: "неизвестная ошибка"}"
                }
            } finally {
                try {
                    agc?.release()
                } catch (_: Throwable) {
                }
                try {
                    noiseSuppressor?.release()
                } catch (_: Throwable) {
                }
                try {
                    audioRecord?.release()
                } catch (_: Throwable) {
                }
            }

            if (errorMessage != null) {
                onError(errorMessage)
                return@launch
            }

            val rawPcm = recordedPcmBytes
            if (rawPcm == null || rawPcm.size < 3600 || maxPeakObserved < 75) {
                onError(
                    if (isEnglish) {
                        "No voice detected. Speak right after pressing the button."
                    } else {
                        "Голос не услышан. Произнесите вопрос после нажатия кнопки."
                    }
                )
                return@launch
            }

            // Trim trailing silence (keep up to 200ms = 6400 bytes after the last detected voice frame)
            // so we don't upload 1+ second of dead silence over the network.
            val trimmedPcm = if (lastVoiceBytePosition > 4800) {
                val keepBytes = min(rawPcm.size, ((lastVoiceBytePosition + 6400) / 2) * 2)
                if (keepBytes < rawPcm.size) rawPcm.copyOf(keepBytes) else rawPcm
            } else {
                rawPcm
            }

            val normalizedPcm = normalizePcm16Bit(trimmedPcm, maxPeakObserved)
            val wavBytes = encodePcmToWav(normalizedPcm, sampleRate, 1, 16)
            onRecordingComplete(wavBytes)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createInitializedAudioRecord(
        sampleRate: Int,
        channelConfig: Int,
        audioFormat: Int,
        bufferSizeInBytes: Int
    ): AudioRecord? {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        )
        for (source in sources) {
            try {
                val record = AudioRecord(
                    source,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSizeInBytes
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    return record
                }
                record.release()
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun normalizePcm16Bit(pcmBytes: ByteArray, observedPeak: Int): ByteArray {
        val peak = max(observedPeak, 1)
        if (peak >= 18_000) return pcmBytes

        val gain = (22_000.0 / peak.toDouble()).coerceIn(1.0, 7.5)
        if (gain <= 1.05) return pcmBytes

        val out = ByteArray(pcmBytes.size)
        val sampleCount = pcmBytes.size / 2
        for (i in 0 until sampleCount) {
            val lo = pcmBytes[i * 2].toInt() and 0xFF
            val hi = pcmBytes[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            val boosted = (sample * gain).toInt().coerceIn(-32767, 32767)
            out[i * 2] = (boosted and 0xFF).toByte()
            out[i * 2 + 1] = ((boosted shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun finishEarly() {
        shouldStopEarly = true
    }

    fun stopCancel() {
        shouldStopEarly = true
        recordingJob?.cancel()
        recordingJob = null
    }

    private fun encodePcmToWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val totalDataLen = pcmData.size + 36

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // Subchunk1Size for PCM
        header.putShort(1) // AudioFormat 1 = PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign)
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmData.size)

        val out = ByteArray(44 + pcmData.size)
        System.arraycopy(header.array(), 0, out, 0, 44)
        System.arraycopy(pcmData, 0, out, 44, pcmData.size)
        return out
    }
}
