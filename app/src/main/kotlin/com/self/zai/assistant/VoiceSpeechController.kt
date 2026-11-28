package com.self.zai.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.self.zai.model.TtsReadoutMode
import com.self.zai.model.VoiceListenState
import com.self.zai.permission.DevicePermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Clean Android speech controller wrapping:
 * 1. System Chinese ASR ([SpeechRecognizer]) — explicitly resolves a real system [RecognitionService]
 *    (e.g. `com.xiaomi.mibrain.speech` on HyperOS) so it never binds to Zai's own manifest stub.
 * 2. System TTS ([TextToSpeech]) — speaks final answers concisely according to [TtsReadoutMode],
 *    automatically respecting wired/Bluetooth headsets.
 */
class VoiceSpeechController(
    private val context: Context,
    private val onPartialTranscript: (String) -> Unit,
    private val onFinalTranscript: (String) -> Unit,
    private val onErrorMessage: (String) -> Unit,
    private val enableTts: Boolean = true,
) {
    private val appContext = context.applicationContext

    private val _listenState = MutableStateFlow(VoiceListenState.IDLE)
    val listenState: StateFlow<VoiceListenState> = _listenState.asStateFlow()

    private val _rmsDb = MutableStateFlow(0f)
    val rmsDb: StateFlow<Float> = _rmsDb.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false
    private var pendingSpeakText: String? = null
    private var latestPartial: String = ""
    private var heardSpeech: Boolean = false
    private var listenGeneration: Int = 0

    init {
        if (enableTts) {
            initTts()
        }
    }

    private fun initTts() {
        runCatching {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.let { engine ->
                        val res = engine.setLanguage(Locale.CHINA)
                        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                            engine.setLanguage(Locale.getDefault())
                        }
                        engine.setSpeechRate(1.06f)
                        engine.setOnUtteranceProgressListener(
                            object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) {
                                    _isSpeaking.value = true
                                }

                                override fun onDone(utteranceId: String?) {
                                    _isSpeaking.value = false
                                }

                                @Deprecated("Deprecated in Java")
                                override fun onError(utteranceId: String?) {
                                    _isSpeaking.value = false
                                }

                                override fun onError(utteranceId: String?, errorCode: Int) {
                                    _isSpeaking.value = false
                                }
                            },
                        )
                        ttsReady = true
                        val queued = pendingSpeakText
                        pendingSpeakText = null
                        if (!queued.isNullOrBlank()) {
                            speakNow(queued)
                        }
                    }
                }
            }
        }
    }

    fun startListening(): Boolean {
        stopSpeaking()
        val externalComponent = resolveExternalRecognitionService(appContext)
        ensureSystemVoiceRecognitionService(appContext, externalComponent)

        if (externalComponent == null && !SpeechRecognizer.isRecognitionAvailable(appContext)) {
            _listenState.value = VoiceListenState.ERROR
            onErrorMessage("未检测到可用的系统语音识别服务")
            return false
        }

        destroyRecognizer()
        // Prefer 1-arg SpeechRecognizer through system SpeechRecognitionManagerService whenever
        // voice_recognition_service points to a real external recognizer so Android 12-16 properly
        // proxies foreground RECORD_AUDIO attribution from the active Activity.
        val configuredRecognizer = currentConfiguredRecognitionService(appContext)
        val useSystemManagedPath = configuredRecognizer != null &&
            configuredRecognizer.packageName != appContext.packageName

        val sr = runCatching {
            if (useSystemManagedPath) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else if (externalComponent != null) {
                SpeechRecognizer.createSpeechRecognizer(context, externalComponent)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.getOrNull()

        if (sr == null) {
            _listenState.value = VoiceListenState.ERROR
            onErrorMessage("语音识别初始化失败")
            return false
        }

        latestPartial = ""
        heardSpeech = false
        val gen = ++listenGeneration
        sr.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (gen != listenGeneration) return
                    _listenState.value = VoiceListenState.LISTENING
                }

                override fun onBeginningOfSpeech() {
                    if (gen != listenGeneration) return
                    // Note: Xiaomi AsrService invokes onBeginningOfSpeech() immediately on mic open
                    // before any real speech; track actual speech via onPartialResults instead.
                    _listenState.value = VoiceListenState.LISTENING
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (gen != listenGeneration) return
                    _rmsDb.value = rmsdB.coerceIn(0f, 12f)
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    if (gen != listenGeneration) return
                    _rmsDb.value = 0f
                }

                override fun onError(error: Int) {
                    if (gen != listenGeneration) return
                    _rmsDb.value = 0f
                    val fallback = latestPartial.trim()
                    if (fallback.isNotEmpty() && (
                            error == SpeechRecognizer.ERROR_NO_MATCH ||
                                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                            )
                    ) {
                        _listenState.value = VoiceListenState.IDLE
                        latestPartial = ""
                        onFinalTranscript(fallback)
                        return
                    }
                    _listenState.value = VoiceListenState.IDLE
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "麦克风录音异常"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "请先授予麦克风权限"
                        SpeechRecognizer.ERROR_NETWORK -> "语音识别网络连接失败"
                        // Xiaomi's AsrService maps its 5s no-speech DIALOG_START timeout (50010004)
                        // to ERROR_NETWORK_TIMEOUT; stop quietly if the user hasn't spoken yet.
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> if (heardSpeech) "语音识别超时，请重试" else ""
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        SpeechRecognizer.ERROR_CLIENT,
                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                        -> "" // Silent stop on timeout, user cancel, or benign service rebind
                        else -> "语音识别失败 ($error)"
                    }
                    if (msg.isNotEmpty()) onErrorMessage(msg)
                }

                override fun onResults(results: Bundle?) {
                    if (gen != listenGeneration) return
                    _rmsDb.value = 0f
                    _listenState.value = VoiceListenState.IDLE
                    val best = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        ?.ifEmpty { latestPartial.trim() }
                        ?: latestPartial.trim()
                    latestPartial = ""
                    if (best.isNotEmpty()) {
                        onFinalTranscript(best)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (gen != listenGeneration) return
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (partial.isNotEmpty()) {
                        heardSpeech = true
                        latestPartial = partial
                        onPartialTranscript(partial)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }

        recognizer = sr
        _listenState.value = VoiceListenState.LISTENING
        return runCatching {
            sr.startListening(intent)
            true
        }.getOrElse {
            _listenState.value = VoiceListenState.ERROR
            onErrorMessage("无法启动语音识别：${it.message}")
            false
        }
    }

    fun stopListening() {
        _rmsDb.value = 0f
        if (_listenState.value == VoiceListenState.LISTENING) {
            runCatching { recognizer?.stopListening() }
            _listenState.value = VoiceListenState.IDLE
        }
    }

    fun cancelListening() {
        listenGeneration++
        _rmsDb.value = 0f
        latestPartial = ""
        _listenState.value = VoiceListenState.IDLE
        runCatching { recognizer?.cancel() }
    }

    fun shouldAutoSpeak(mode: TtsReadoutMode): Boolean = when (mode) {
        TtsReadoutMode.OFF -> false
        TtsReadoutMode.ALWAYS -> true
        TtsReadoutMode.HEADSET_ONLY -> isHeadsetConnected(appContext)
    }

    fun speakIfEligible(rawText: String, mode: TtsReadoutMode) {
        if (!shouldAutoSpeak(mode)) return
        speakNow(rawText)
    }

    fun speakNow(rawText: String) {
        val clean = sanitizeForSpeech(rawText)
        if (clean.isEmpty()) return
        if (!ttsReady) {
            pendingSpeakText = clean
            return
        }
        runCatching {
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "zai_${UUID.randomUUID()}")
        }
    }

    fun stopSpeaking() {
        pendingSpeakText = null
        _isSpeaking.value = false
        runCatching { tts?.stop() }
    }

    fun release() {
        cancelListening()
        destroyRecognizer()
        stopSpeaking()
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    private fun destroyRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    companion object {
        fun currentConfiguredRecognitionService(context: Context): ComponentName? =
            DevicePermissionHelper.currentConfiguredRecognitionService(context)

        fun ensureSystemVoiceRecognitionService(context: Context, externalComponent: ComponentName?) =
            DevicePermissionHelper.ensureSystemVoiceRecognitionService(context, externalComponent)

        fun resolveExternalRecognitionService(context: Context): ComponentName? =
            DevicePermissionHelper.resolveExternalRecognitionService(context)

        fun isHeadsetConnected(context: Context): Boolean {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            return outputs.any { dev ->
                when (dev.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    -> true
                    else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        (dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET || dev.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
                }
            }
        }

        /** Strips Markdown syntax and trims excessively long responses for crisp natural speech. */
        fun sanitizeForSpeech(raw: String): String {
            return raw
                .replace(Regex("```[\\s\\S]*?```"), "，代码片段已省略，")
                .replace(Regex("`([^`]+)`"), "$1")
                .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
                .replace(Regex("\\*([^*]+)\\*"), "$1")
                .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
                .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(450)
        }
    }
}
