package com.padvideo.player

import android.content.Context
import android.content.Intent
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

    fun start(sourceLanguageCode: String = "en-US") {
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
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _statusMessage.value = "当前设备未安装系统语音识别服务，建议使用在线字幕搜索"
                _isListening.value = false
                return@post
            }
            shouldContinueListening = true
            _isListening.value = true
            _statusMessage.value = "实时语音同传监听中 ($recognitionLangTag)…"
            ensureRecognizerAndListen()
        }
    }

    fun stop() {
        shouldContinueListening = false
        mainHandler.post {
            _isListening.value = false
            _partialTranscript.value = ""
            _statusMessage.value = null
            runCatching {
                recognizer?.stopListening()
                recognizer?.cancel()
                recognizer?.destroy()
            }
            recognizer = null
        }
    }

    private fun ensureRecognizerAndListen() {
        if (!shouldContinueListening) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _statusMessage.value = "正在聆听视频原声…"
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        if (shouldContinueListening) {
                            mainHandler.postDelayed({ ensureRecognizerAndListen() }, 450L)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val best = matches?.firstOrNull()?.trim().orEmpty()
                        _partialTranscript.value = ""
                        if (best.isNotEmpty()) {
                            onSegmentRecognized(best)
                        }
                        if (shouldContinueListening) {
                            mainHandler.postDelayed({ ensureRecognizerAndListen() }, 180L)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull()?.trim().orEmpty()
                        if (partial.isNotEmpty()) {
                            _partialTranscript.value = partial
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLangTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { recognizer?.startListening(intent) }
    }
}
