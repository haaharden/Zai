package com.self.zai

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.self.zai.assistant.VoiceSpeechController
import com.self.zai.data.AttachmentManager
import com.self.zai.model.PendingAttachment
import com.self.zai.model.VoiceListenState
import com.self.zai.permission.DevicePermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Activity-scoped delegate encapsulating attachment picking, camera capture, clipboard paste,
 * incoming Android Share intents, and chat voice input — keeping [MainActivity] slim and focused.
 */
class MainComposerInputHost(private val activity: ComponentActivity) {

    val chatInputText = mutableStateOf("")
    val pendingAttachments = mutableStateOf<List<PendingAttachment>>(emptyList())
    val importingAttachments = mutableStateOf(false)
    val voiceControllerState = mutableStateOf<VoiceSpeechController?>(null)

    private var pendingCameraFile: File? = null

    private val audioPermLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            ensureVoiceController().startListening()
        } else {
            Toast.makeText(activity, "需要麦克风权限才能语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickImagesLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE),
    ) { uris ->
        if (uris.isNotEmpty()) {
            importIncomingUris(uris, forcedMime = null)
        }
    }

    private val pickFilesLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            importIncomingUris(uris, forcedMime = null)
        }
    }

    private val takePhotoLauncher = activity.registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { captured ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (!captured || file == null || !file.exists() || file.length() == 0L) {
            runCatching { file?.delete() }
            return@registerForActivityResult
        }
        importIncomingUris(
            uris = listOf(Uri.fromFile(file)),
            forcedMime = "image/jpeg",
            forcedName = "拍照_${System.currentTimeMillis() % 10000}.jpg",
            cleanupAfterImport = file,
        )
    }

    fun launchImagePicker() {
        if (pendingAttachments.value.size >= AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE) {
            Toast.makeText(activity, "单条消息最多附加 ${AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE} 个附件", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            pickImagesLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }.onFailure {
            runCatching { pickFilesLauncher.launch(arrayOf("image/*")) }.onFailure { err ->
                Toast.makeText(activity, "无法打开相册：${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun launchCameraCapture() {
        if (pendingAttachments.value.size >= AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE) {
            Toast.makeText(activity, "单条消息最多附加 ${AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE} 个附件", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val (uri, file) = AttachmentManager.createCameraPhotoUri(activity)
            pendingCameraFile = file
            takePhotoLauncher.launch(uri)
        }.onFailure { err ->
            pendingCameraFile?.let { runCatching { it.delete() } }
            pendingCameraFile = null
            Toast.makeText(activity, "无法调用相机：${err.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchFilePicker() {
        if (pendingAttachments.value.size >= AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE) {
            Toast.makeText(activity, "单条消息最多附加 ${AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE} 个附件", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            pickFilesLauncher.launch(arrayOf("*/*"))
        }.onFailure { err ->
            Toast.makeText(activity, "无法打开文件选择器：${err.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun pasteClipboardImage() {
        if (pendingAttachments.value.size >= AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE) {
            Toast.makeText(activity, "单条消息最多附加 ${AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE} 个附件", Toast.LENGTH_SHORT).show()
            return
        }
        activity.lifecycleScope.launch {
            importingAttachments.value = true
            val res = AttachmentManager.importFromClipboard(activity)
            importingAttachments.value = false
            res.onSuccess { item ->
                pendingAttachments.value = pendingAttachments.value + item
            }.onFailure { err ->
                Toast.makeText(activity, err.message ?: "剪贴板里没有图片", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun removePendingAttachment(attachmentId: String) {
        val current = pendingAttachments.value
        val target = current.firstOrNull { it.attachment.id == attachmentId } ?: return
        pendingAttachments.value = current.filterNot { it.attachment.id == attachmentId }
        activity.lifecycleScope.launch(Dispatchers.IO) {
            AttachmentManager.deleteAttachmentFiles(activity.applicationContext, listOf(target.attachment))
        }
    }

    fun clearPendingAttachments() {
        pendingAttachments.value = emptyList()
    }

    fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val uris = mutableListOf<Uri>()
        if (action == Intent.ACTION_SEND) {
            val singleUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (singleUri != null) uris += singleUri
        } else {
            val multiUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (!multiUris.isNullOrEmpty()) uris += multiUris
        }

        if (sharedText.isNotEmpty()) {
            chatInputText.value = if (chatInputText.value.isBlank()) {
                sharedText
            } else {
                "${chatInputText.value}\n$sharedText"
            }
        }
        if (uris.isNotEmpty()) {
            val mime = intent.type?.takeIf { !it.contains('*') }
            importIncomingUris(uris, forcedMime = mime, notifyAddedCount = true)
        }
        // Clear action so configuration changes / resume won't re-import the same share
        intent.action = Intent.ACTION_MAIN
    }

    fun toggleChatVoiceInput() {
        val current = voiceControllerState.value
        if (current != null && current.listenState.value == VoiceListenState.LISTENING) {
            current.stopListening()
            return
        }
        if (DevicePermissionHelper.hasAudioPermission(activity)) {
            ensureVoiceController().startListening()
        } else {
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun cancelChatVoiceInput() {
        voiceControllerState.value?.cancelListening()
    }

    fun release() {
        voiceControllerState.value?.release()
        voiceControllerState.value = null
    }

    private fun ensureVoiceController(): VoiceSpeechController {
        voiceControllerState.value?.let { return it }
        return VoiceSpeechController(
            context = activity,
            enableTts = false,
            onPartialTranscript = { partial -> chatInputText.value = partial },
            onFinalTranscript = { finalText -> chatInputText.value = finalText },
            onErrorMessage = { msg -> Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show() },
        ).also { created ->
            voiceControllerState.value = created
        }
    }

    private fun importIncomingUris(
        uris: List<Uri>,
        forcedMime: String? = null,
        forcedName: String? = null,
        cleanupAfterImport: File? = null,
        notifyAddedCount: Boolean = false,
    ) {
        val remaining = AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE - pendingAttachments.value.size
        if (remaining <= 0) {
            runCatching { cleanupAfterImport?.delete() }
            Toast.makeText(activity, "单条消息最多附加 ${AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE} 个附件", Toast.LENGTH_SHORT).show()
            return
        }
        val toImport = uris.take(remaining)
        val skippedOverflow = uris.size - toImport.size
        activity.lifecycleScope.launch {
            importingAttachments.value = true
            val imported = mutableListOf<PendingAttachment>()
            var firstError: String? = null
            try {
                for (uri in toImport) {
                    val res = AttachmentManager.importUri(
                        context = activity,
                        uri = uri,
                        forcedName = forcedName,
                        forcedMime = forcedMime,
                    )
                    res.onSuccess { imported += it }
                        .onFailure { err -> if (firstError == null) firstError = err.message }
                }
            } finally {
                importingAttachments.value = false
                runCatching { cleanupAfterImport?.delete() }
            }
            if (imported.isNotEmpty()) {
                pendingAttachments.value = pendingAttachments.value + imported
                if (notifyAddedCount) {
                    Toast.makeText(activity, "已导入 ${imported.size} 个附件", Toast.LENGTH_SHORT).show()
                }
            }
            if (firstError != null) {
                Toast.makeText(activity, firstError, Toast.LENGTH_LONG).show()
            } else if (skippedOverflow > 0) {
                Toast.makeText(activity, "超出数量上限，已忽略 $skippedOverflow 个附件", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
