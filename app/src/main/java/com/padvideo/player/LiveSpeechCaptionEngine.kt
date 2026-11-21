package com.padvideo.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Continuous real-time speech-to-text listener for unsubtitled videos ("实时语音识别同传").
 */
class LiveSpeechCaptionEngine(
    private val context: Context,
    private val onSegmentRecognized: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var shouldContinueListening = false
    private var recognitionLangTag: String = "en-US"

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    fun start(sourceLanguageCode: String, offline: Boolean) {
        stop()
        recognitionLangTag = when (sourceLanguageCode.lowercase()) {
            "auto", "en" -> "en-US"
            "ja" -> "ja-JP"
            "ko" -> "ko-KR"
            "zh-cn", "zh" -> "zh-CN"
            "fr" -> "fr-FR"
            "de" -> "de-DE"
            "es" -> "es-ES"
            "ru" -> "ru-RU"
            else -> sourceLanguageCode
        }
        val canRecognize = if (offline) {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } else {
            SpeechRecognizer.isRecognitionAvailable(context)
        }
        if (!canRecognize) {
            _statusMessage.value = if (offline) "此设备没有可用的端侧语音识别服务，仍可离线翻译字幕"
            else "当前设备没有可用的系统语音识别服务"
            return
        }

        recognizer = try {
            val instance = if (offline && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            instance.apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onError(error: Int) {
                        if (recognizer !== instance || !shouldContinueListening) return
                        _partialTranscript.value = ""
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                mainHandler.postDelayed({ listen() }, 450L)
                            else -> {
                                stop()
                                _statusMessage.value = if (offline) "端侧语音识别失败，请检查系统语音识别服务和已安装的语音模型"
                                else "语音识别失败，请检查麦克风权限和系统语音识别服务"
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        if (recognizer !== instance || !shouldContinueListening) return
                        val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        _partialTranscript.value = ""
                        if (best.isNotEmpty()) onSegmentRecognized(best)
                        mainHandler.postDelayed({ listen() }, 180L)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        if (recognizer !== instance || !shouldContinueListening) return
                        _partialTranscript.value = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                    }
                })
            }
        } catch (error: RuntimeException) {
            _statusMessage.value = "无法启动系统语音识别服务"
            return
        }
        shouldContinueListening = true
        _isListening.value = true
        _statusMessage.value = "正在通过麦克风识别语音"
        listen()
    }

    fun stop() {
        shouldContinueListening = false
        mainHandler.removeCallbacksAndMessages(null)
        _isListening.value = false
        _partialTranscript.value = ""
        _statusMessage.value = null
        val previous = recognizer
        recognizer = null
        runCatching { previous?.cancel() }
        runCatching { previous?.destroy() }
    }

    private fun listen() {
        if (!shouldContinueListening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLangTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { recognizer?.startListening(intent) }.onFailure {
            stop()
            _statusMessage.value = "无法启动语音识别，请检查系统语音识别服务"
        }
    }
}
