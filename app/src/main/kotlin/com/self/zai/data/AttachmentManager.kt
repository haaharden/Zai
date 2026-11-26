package com.self.zai.data

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import com.self.zai.model.AttachmentKind
import com.self.zai.model.ChatAttachment
import com.self.zai.model.ChatSession
import com.self.zai.model.PendingAttachment
import com.self.zai.model.PromptImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Handles importing, compressing, extracting, and persisting chat image & file attachments:
 * - Images: EXIF-aware downsampling, JPEG encoding, persisted local thumbnails (never bloating session JSON).
 * - PDFs: native Android `PdfRenderer` rasterization (up to 4 pages) so scanned/styled PDFs work with vision models.
 * - Office Open XML (`.docx`, `.xlsx`, `.pptx`): dependency-free pure-Kotlin ZIP/XML text extraction.
 * - Plain text / code / logs / JSON / CSV / Markdown: UTF-8 reading with size & character caps.
 * - Clipboard image import & camera capture temp URIs.
 */
object AttachmentManager {

    const val MAX_ATTACHMENTS_PER_MESSAGE = 6
    private const val MAX_IMAGE_LONG_EDGE_PX = 1536
    private const val MAX_PDF_LONG_EDGE_PX = 1440
    private const val MAX_PDF_PAGES = 4
    private const val JPEG_QUALITY_DEFAULT = 82
    private const val JPEG_QUALITY_FALLBACK = 70
    private const val JPEG_RECOMPRESS_THRESHOLD_BYTES = 1_200_000
    private const val MAX_TEXT_RAW_BYTES = 512 * 1024
    private const val MAX_OFFICE_RAW_BYTES = 10 * 1024 * 1024
    private const val MAX_PDF_RAW_BYTES = 25 * 1024 * 1024
    const val MAX_EXTRACTED_CHARS = 60_000

    private val TEXT_MIME_TYPES = setOf(
        "application/json",
        "application/ld+json",
        "application/xml",
        "application/xhtml+xml",
        "application/javascript",
        "application/x-javascript",
        "application/typescript",
        "application/yaml",
        "application/x-yaml",
        "application/toml",
        "application/sql",
        "application/x-sh",
        "application/csv",
        "application/x-python-code",
        "application/x-httpd-php",
    )

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "json", "jsonl", "ndjson", "yaml", "yml", "toml",
        "xml", "html", "htm", "css", "scss", "less", "js", "mjs", "cjs", "ts", "tsx", "jsx",
        "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cc", "cpp", "cxx", "hpp",
        "cs", "swift", "dart", "php", "sh", "bash", "zsh", "ps1", "bat", "cmd",
        "sql", "csv", "tsv", "log", "ini", "cfg", "conf", "properties", "env",
        "gitignore", "dockerfile", "makefile", "gradle", "svg", "tex", "rst", "org",
        "diff", "patch", "vue", "svelte", "proto", "graphql", "gql",
    )

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif",
    )

    private val XML_ENTITY_REGEX = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")
    private val DOCX_TAG_REGEX = Regex("<(/?)w:(p|br|tab|t)(?:\\s[^>]*)?(/?)>")
    private val PPTX_TAG_REGEX = Regex("<(/?)a:(p|br|t)(?:\\s[^>]*)?(/?)>")
    private val XLSX_SI_REGEX = Regex("<si\\b[^>]*>([\\s\\S]*?)</si>")
    private val XLSX_T_REGEX = Regex("<t\\b[^>]*>([\\s\\S]*?)</t>")
    private val XLSX_ROW_REGEX = Regex("<row\\b[^>]*>([\\s\\S]*?)</row>")
    private val XLSX_CELL_REGEX = Regex("<c\\b([^>]*)>([\\s\\S]*?)</c>")
    private val XLSX_V_REGEX = Regex("<v\\b[^>]*>([\\s\\S]*?)</v>")
    private val XLSX_TYPE_ATTR_REGEX = Regex("\\bt=\"([^\"]+)\"")
    private val SLIDE_NUM_REGEX = Regex("ppt/slides/slide(\\d+)\\.xml$", RegexOption.IGNORE_CASE)
    private val SHEET_NUM_REGEX = Regex("xl/worksheets/sheet(\\d+)\\.xml$", RegexOption.IGNORE_CASE)

    fun attachmentsDir(context: Context): File =
        File(context.filesDir, "zai_attachments").apply { mkdirs() }

    fun createCameraPhotoUri(context: Context): Pair<Uri, File> {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "cam_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return uri to file
    }

    fun hasClipboardImage(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        if (!cm.hasPrimaryClip()) return false
        val desc = cm.primaryClipDescription
        if (desc != null && (0 until desc.mimeTypeCount).any { desc.getMimeType(it).startsWith("image/", ignoreCase = true) }) {
            return true
        }
        val clip = cm.primaryClip ?: return false
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i)?.uri ?: continue
            val mime = context.contentResolver.getType(uri).orEmpty()
            if (mime.startsWith("image/", ignoreCase = true)) return true
        }
        false
    }.getOrDefault(false)

    suspend fun importFromClipboard(context: Context): Result<PendingAttachment> = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@withContext Result.failure(IllegalStateException("无法访问剪贴板"))
        val clip = cm.primaryClip
            ?: return@withContext Result.failure(IllegalStateException("剪贴板为空"))
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i)?.uri ?: continue
            val mime = context.contentResolver.getType(uri)
            if (mime == null || mime.startsWith("image/", ignoreCase = true)) {
                val res = importUri(
                    context = context,
                    uri = uri,
                    forcedName = "剪贴板图片.jpg",
                    forcedMime = mime?.takeIf { it.startsWith("image/", ignoreCase = true) } ?: "image/jpeg",
                )
                if (res.isSuccess) return@withContext res
            }
        }
        Result.failure(IllegalStateException("剪贴板里没有可读取的图片"))
    }

    suspend fun importUri(
        context: Context,
        uri: Uri,
        forcedName: String? = null,
        forcedMime: String? = null,
    ): Result<PendingAttachment> = withContext(Dispatchers.IO) {
        runCatching {
            val (queriedName, queriedSize) = queryDisplayNameAndSize(context, uri)
            val displayName = forcedName?.takeIf { it.isNotBlank() }
                ?: queriedName?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "未命名文件"
            val mimeType = (forcedMime?.takeIf { it.isNotBlank() }
                ?: context.contentResolver.getType(uri)
                ?: guessMimeFromName(displayName)).lowercase(Locale.ROOT)

            when {
                isImageFile(displayName, mimeType) ->
                    importImage(context, uri, displayName, mimeType)

                isPdfFile(displayName, mimeType) ->
                    importPdf(context, uri, displayName, queriedSize)

                officeDocumentType(displayName, mimeType) != null ->
                    importOffice(context, uri, displayName, mimeType, queriedSize, officeDocumentType(displayName, mimeType)!!)

                else ->
                    importTextOrFail(context, uri, displayName, mimeType, queriedSize)
            }
        }
    }

    private fun importImage(
        context: Context,
        uri: Uri,
        displayName: String,
        mimeType: String,
    ): PendingAttachment {
        val resolver = context.contentResolver
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
            ?: error("无法读取图片文件")
        require(boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) { "无法解码图片「$displayName」" }

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, MAX_IMAGE_LONG_EDGE_PX)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
            ?: error("解码图片「$displayName」失败")

        val oriented = applyExifOrientation(decoded, orientation)
        if (oriented !== decoded) decoded.recycle()

        val scaled = scaleBitmapToMaxEdge(oriented, MAX_IMAGE_LONG_EDGE_PX)
        if (scaled !== oriented) oriented.recycle()

        val jpegBytes = try {
            encodeJpegCompact(scaled)
        } finally {
            scaled.recycle()
        }

        val id = "att_${UUID.randomUUID().toString().take(8)}"
        val outFile = File(attachmentsDir(context), "$id.jpg")
        outFile.writeBytes(jpegBytes)

        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val attachment = ChatAttachment(
            id = id,
            kind = AttachmentKind.IMAGE,
            displayName = displayName,
            mimeType = "image/jpeg",
            sizeBytes = jpegBytes.size.toLong(),
            localPath = outFile.absolutePath,
            metaLabel = formatFileSize(jpegBytes.size.toLong()),
        )
        return PendingAttachment(
            attachment = attachment,
            promptImages = listOf(PromptImage(dataBase64 = b64, mimeType = "image/jpeg")),
        )
    }

    private fun importPdf(
        context: Context,
        uri: Uri,
        displayName: String,
        queriedSize: Long,
    ): PendingAttachment {
        val tempPdf = File(context.cacheDir, "zai_pdf_${UUID.randomUUID().toString().take(8)}.pdf")
        try {
            val totalBytes = copyUriToFileWithLimit(context, uri, tempPdf, MAX_PDF_RAW_BYTES)
            val id = "att_${UUID.randomUUID().toString().take(8)}"
            val dir = attachmentsDir(context)
            val pagePaths = mutableListOf<String>()
            val promptImages = mutableListOf<PromptImage>()
            var totalPages = 0

            ParcelFileDescriptor.open(tempPdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    totalPages = renderer.pageCount
                    require(totalPages > 0) { "PDF「$displayName」没有可读取的页面" }
                    val renderCount = totalPages.coerceAtMost(MAX_PDF_PAGES)
                    for (pageIdx in 0 until renderCount) {
                        renderer.openPage(pageIdx).use { page ->
                            val scale = (MAX_PDF_LONG_EDGE_PX.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1))
                                .coerceIn(1f, 2.5f)
                            val w = (page.width * scale).toInt().coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            try {
                                Canvas(bmp).drawColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val jpegBytes = encodeJpegCompact(bmp)
                                val pageFile = File(dir, "${id}_p${pageIdx + 1}.jpg")
                                pageFile.writeBytes(jpegBytes)
                                pagePaths += pageFile.absolutePath
                                promptImages += PromptImage(
                                    dataBase64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP),
                                    mimeType = "image/jpeg",
                                )
                            } finally {
                                bmp.recycle()
                            }
                        }
                    }
                }
            }

            val meta = if (totalPages > pagePaths.size) {
                "PDF · 共${totalPages}页（前${pagePaths.size}页）"
            } else {
                "PDF · ${totalPages}页"
            }
            val attachment = ChatAttachment(
                id = id,
                kind = AttachmentKind.FILE,
                displayName = displayName,
                mimeType = "application/pdf",
                sizeBytes = if (queriedSize > 0L) queriedSize else totalBytes,
                localPath = pagePaths.firstOrNull(),
                pageImagePaths = pagePaths,
                metaLabel = meta,
            )
            return PendingAttachment(
                attachment = attachment,
                promptImages = promptImages,
            )
        } finally {
            runCatching { tempPdf.delete() }
        }
    }

    private fun importOffice(
        context: Context,
        uri: Uri,
        displayName: String,
        mimeType: String,
        queriedSize: Long,
        docType: String,
    ): PendingAttachment {
        val rawBytes = readUriBytesWithLimit(context, uri, MAX_OFFICE_RAW_BYTES)
        val (text, truncated) = extractOfficeXmlText(rawBytes, docType, MAX_EXTRACTED_CHARS)
        require(text.isNotBlank()) { "未能从「$displayName」中提取到文本内容" }
        val finalText = if (truncated) "$text\n\n...[文档内容较长，已截取前 $MAX_EXTRACTED_CHARS 字符]" else text
        val size = if (queriedSize > 0L) queriedSize else rawBytes.size.toLong()
        val label = docType.uppercase(Locale.ROOT)
        val meta = buildString {
            append(label)
            append(" · ")
            append(formatFileSize(size))
            if (truncated) append(" · 已截断")
        }
        val attachment = ChatAttachment(
            id = "att_${UUID.randomUUID().toString().take(8)}",
            kind = AttachmentKind.FILE,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = size,
            metaLabel = meta,
        )
        return PendingAttachment(
            attachment = attachment,
            extractedText = finalText,
        )
    }

    private fun importTextOrFail(
        context: Context,
        uri: Uri,
        displayName: String,
        mimeType: String,
        queriedSize: Long,
    ): PendingAttachment {
        val (bytes, reachedByteCap) = readUriPrefixBytes(context, uri, MAX_TEXT_RAW_BYTES)
        val knownText = isSupportedTextFile(displayName, mimeType)
        require(knownText || looksLikeUtf8Text(bytes)) {
            "暂不支持读取「$displayName」，支持图片、PDF、Word/Excel/PPT 及各类文本/代码文件"
        }
        val rawText = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val truncatedByChars = rawText.length > MAX_EXTRACTED_CHARS
        val truncated = reachedByteCap || truncatedByChars
        val clipped = if (truncatedByChars) rawText.take(MAX_EXTRACTED_CHARS) else rawText
        val finalText = if (truncated) {
            "$clipped\n\n...[文件内容较长，已截取前 ${clipped.length} 字符]"
        } else {
            clipped
        }
        val size = if (queriedSize > 0L) queriedSize else bytes.size.toLong()
        val meta = buildString {
            append(formatFileSize(size))
            if (truncated) append(" · 已截断")
        }
        val attachment = ChatAttachment(
            id = "att_${UUID.randomUUID().toString().take(8)}",
            kind = AttachmentKind.FILE,
            displayName = displayName,
            mimeType = mimeType.ifBlank { "text/plain" },
            sizeBytes = size,
            metaLabel = meta,
        )
        return PendingAttachment(
            attachment = attachment,
            extractedText = finalText,
        )
    }

    fun deleteAttachmentFiles(context: Context, attachments: List<ChatAttachment>) {
        if (attachments.isEmpty()) return
        val rootCanonical = runCatching { attachmentsDir(context).canonicalPath }.getOrNull() ?: return
        for (att in attachments) {
            val paths = buildSet {
                att.localPath?.let { add(it) }
                addAll(att.pageImagePaths)
            }
            for (path in paths) {
                runCatching {
                    val file = File(path)
                    if (file.exists() && file.canonicalPath.startsWith(rootCanonical)) {
                        file.delete()
                    }
                }
            }
        }
    }

    fun cleanupSessionAttachments(context: Context, session: ChatSession) {
        val all = session.messages.flatMap { it.attachments }
        deleteAttachmentFiles(context, all)
    }

    fun loadThumbnailBitmap(path: String, maxSidePx: Int = 360): Bitmap? = runCatching {
        val file = File(path)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxSidePx)
        }
        BitmapFactory.decodeFile(path, opts)
    }.getOrNull()

    fun loadPreviewBitmap(path: String, maxSidePx: Int = 1600): Bitmap? =
        loadThumbnailBitmap(path, maxSidePx)

    // --- Pure, JVM-unit-testable helpers ---

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    fun fileExtension(fileName: String): String =
        fileName.substringAfterLast('.', missingDelimiterValue = "").trim().lowercase(Locale.ROOT)

    fun isImageFile(fileName: String, mimeType: String?): Boolean {
        val mime = mimeType?.lowercase(Locale.ROOT).orEmpty()
        if (mime.startsWith("image/")) return true
        return fileExtension(fileName) in IMAGE_EXTENSIONS
    }

    fun isPdfFile(fileName: String, mimeType: String?): Boolean {
        val mime = mimeType?.lowercase(Locale.ROOT).orEmpty()
        return mime == "application/pdf" || fileExtension(fileName) == "pdf"
    }

    fun officeDocumentType(fileName: String, mimeType: String?): String? {
        val mime = mimeType?.lowercase(Locale.ROOT).orEmpty()
        val ext = fileExtension(fileName)
        return when {
            ext == "docx" || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            ext == "xlsx" || mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            ext == "pptx" || mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
            else -> null
        }
    }

    fun isSupportedTextFile(fileName: String, mimeType: String?): Boolean {
        val mime = mimeType?.lowercase(Locale.ROOT).orEmpty()
        if (mime.startsWith("text/") || mime in TEXT_MIME_TYPES) return true
        val lowerName = fileName.lowercase(Locale.ROOT)
        if (lowerName == "makefile" || lowerName == "dockerfile" || lowerName == "readme" || lowerName == "license") {
            return true
        }
        return fileExtension(fileName) in TEXT_EXTENSIONS
    }

    fun looksLikeUtf8Text(sample: ByteArray): Boolean {
        if (sample.isEmpty()) return true
        val checkLen = sample.size.coerceAtMost(4096)
        var controlCount = 0
        for (i in 0 until checkLen) {
            val b = sample[i].toInt() and 0xFF
            if (b == 0) return false
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C) {
                controlCount++
            }
        }
        return controlCount * 100 / checkLen <= 10
    }

    fun extractOfficeXmlText(
        zipBytes: ByteArray,
        docType: String,
        maxChars: Int = MAX_EXTRACTED_CHARS,
    ): Pair<String, Boolean> {
        val entries = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name
                    val needed = when (docType) {
                        "docx" -> name.equals("word/document.xml", ignoreCase = true)
                        "pptx" -> SLIDE_NUM_REGEX.containsMatchIn(name)
                        "xlsx" -> name.equals("xl/sharedStrings.xml", ignoreCase = true) ||
                            SHEET_NUM_REGEX.containsMatchIn(name)
                        else -> false
                    }
                    if (needed) {
                        entries[name] = zis.readBytes().toString(Charsets.UTF_8)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val raw = when (docType) {
            "docx" -> entries.entries
                .firstOrNull { it.key.equals("word/document.xml", ignoreCase = true) }
                ?.value
                ?.let { parseWordDocumentXml(it) }
                .orEmpty()

            "pptx" -> entries.entries
                .mapNotNull { (k, v) ->
                    val n = SLIDE_NUM_REGEX.find(k)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    n to v
                }
                .sortedBy { it.first }
                .mapNotNull { (num, xml) ->
                    val body = parsePptSlideXml(xml).trim()
                    if (body.isEmpty()) null else "--- 第 $num 页 ---\n$body"
                }
                .joinToString("\n\n")

            "xlsx" -> {
                val sharedXml = entries.entries
                    .firstOrNull { it.key.equals("xl/sharedStrings.xml", ignoreCase = true) }
                    ?.value
                val sharedStrings = if (sharedXml != null) parseXlsxSharedStrings(sharedXml) else emptyList()
                entries.entries
                    .mapNotNull { (k, v) ->
                        val n = SHEET_NUM_REGEX.find(k)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                        n to v
                    }
                    .sortedBy { it.first }
                    .mapNotNull { (num, xml) ->
                        val sheetBody = parseXlsxSheetXml(xml, sharedStrings).trim()
                        if (sheetBody.isEmpty()) null else "--- 工作表 $num ---\n$sheetBody"
                    }
                    .joinToString("\n\n")
            }

            else -> ""
        }.trim()

        val truncated = raw.length > maxChars
        return (if (truncated) raw.take(maxChars) else raw) to truncated
    }

    fun buildPromptTextWithAttachments(
        userText: String,
        attachments: List<PendingAttachment>,
    ): String {
        if (attachments.isEmpty()) return userText
        return buildString {
            for (item in attachments) {
                val att = item.attachment
                val extracted = item.extractedText
                if (!extracted.isNullOrBlank()) {
                    val sizeNote = att.metaLabel.ifBlank { formatFileSize(att.sizeBytes) }
                    appendLine("[附件文件：${att.displayName} ($sizeNote)]")
                    appendLine(extracted.trimEnd())
                    appendLine("[附件结束]")
                    appendLine()
                } else if (att.kind == AttachmentKind.FILE && item.promptImages.isNotEmpty()) {
                    appendLine("[已附带文档「${att.displayName}」的渲染页面图片（${att.metaLabel.ifBlank { "${item.promptImages.size}页" }}）]")
                    appendLine()
                }
            }
            val trimmedUser = userText.trim()
            if (trimmedUser.isNotEmpty()) {
                append(trimmedUser)
            } else {
                append("请查看并分析以上附件内容。")
            }
        }.trim()
    }

    fun buildSessionTitleFromPrompt(
        userText: String,
        attachments: List<ChatAttachment>,
    ): String {
        val cleaned = userText.replace("\n", " ").trim()
        if (cleaned.isNotEmpty()) return cleaned.take(20)
        if (attachments.isEmpty()) return "新对话"
        val first = attachments.first()
        return when {
            attachments.size == 1 && first.kind == AttachmentKind.IMAGE -> "图片分析"
            attachments.all { it.kind == AttachmentKind.IMAGE } -> "图片 · ${attachments.size}张"
            else -> first.displayName.take(20)
        }
    }

    // --- Internal helpers ---

    private fun parseWordDocumentXml(xml: String): String {
        val sb = StringBuilder()
        var inText = false
        var lastEnd = 0
        for (match in DOCX_TAG_REGEX.findAll(xml)) {
            if (inText && match.range.first > lastEnd) {
                sb.append(decodeXmlEntities(xml.substring(lastEnd, match.range.first)))
            }
            val isClosing = match.groupValues[1] == "/"
            val tag = match.groupValues[2]
            val isSelfClosing = match.groupValues[3] == "/"
            when (tag) {
                "t" -> inText = !isClosing && !isSelfClosing
                "br" -> sb.append('\n')
                "tab" -> sb.append('\t')
                "p" -> if (isClosing) sb.append('\n')
            }
            lastEnd = match.range.last + 1
        }
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun parsePptSlideXml(xml: String): String {
        val sb = StringBuilder()
        var inText = false
        var lastEnd = 0
        for (match in PPTX_TAG_REGEX.findAll(xml)) {
            if (inText && match.range.first > lastEnd) {
                sb.append(decodeXmlEntities(xml.substring(lastEnd, match.range.first)))
            }
            val isClosing = match.groupValues[1] == "/"
            val tag = match.groupValues[2]
            val isSelfClosing = match.groupValues[3] == "/"
            when (tag) {
                "t" -> inText = !isClosing && !isSelfClosing
                "br" -> sb.append('\n')
                "p" -> if (isClosing) sb.append('\n')
            }
            lastEnd = match.range.last + 1
        }
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun parseXlsxSharedStrings(xml: String): List<String> =
        XLSX_SI_REGEX.findAll(xml).map { siMatch ->
            XLSX_T_REGEX.findAll(siMatch.groupValues[1])
                .joinToString("") { decodeXmlEntities(it.groupValues[1]) }
        }.toList()

    private fun parseXlsxSheetXml(xml: String, sharedStrings: List<String>): String {
        val lines = mutableListOf<String>()
        for (rowMatch in XLSX_ROW_REGEX.findAll(xml)) {
            val cells = mutableListOf<String>()
            for (cellMatch in XLSX_CELL_REGEX.findAll(rowMatch.groupValues[1])) {
                val attrs = cellMatch.groupValues[1]
                val body = cellMatch.groupValues[2]
                val cellType = XLSX_TYPE_ATTR_REGEX.find(attrs)?.groupValues?.getOrNull(1).orEmpty()
                val value = when (cellType) {
                    "s" -> {
                        val idx = XLSX_V_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim()?.toIntOrNull()
                        if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else ""
                    }
                    "inlineStr" -> XLSX_T_REGEX.findAll(body)
                        .joinToString("") { decodeXmlEntities(it.groupValues[1]) }
                    else -> XLSX_V_REGEX.find(body)?.groupValues?.getOrNull(1)
                        ?.let { decodeXmlEntities(it) }
                        .orEmpty()
                }.replace('\n', ' ').trim()
                if (value.isNotEmpty()) {
                    cells += value
                }
            }
            if (cells.isNotEmpty()) {
                lines += cells.joinToString("\t")
            }
        }
        return lines.joinToString("\n")
    }

    private fun decodeXmlEntities(raw: String): String =
        XML_ENTITY_REGEX.replace(raw) { m ->
            when (val token = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val code = if (token.startsWith("#x", ignoreCase = true)) {
                        token.substring(2).toIntOrNull(16)
                    } else if (token.startsWith("#")) {
                        token.substring(1).toIntOrNull(10)
                    } else {
                        null
                    }
                    if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
                }
            }
        }

    private fun guessMimeFromName(fileName: String): String = when (fileExtension(fileName)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "xml" -> "application/xml"
        "html", "htm" -> "text/html"
        else -> ""
    }

    private fun queryDisplayNameAndSize(context: Context, uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = 0L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    private fun copyUriToFileWithLimit(context: Context, uri: Uri, dest: File, maxBytes: Int): Long {
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    require(total <= maxBytes) { "文件过大（超过 ${formatFileSize(maxBytes.toLong())}）" }
                    out.write(buf, 0, n)
                }
            }
        } ?: error("无法读取文件内容")
        return total
    }

    private fun readUriBytesWithLimit(context: Context, uri: Uri, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                require(total <= maxBytes) { "文档过大（超过 ${formatFileSize(maxBytes.toLong())}）" }
                out.write(buf, 0, n)
            }
        } ?: error("无法读取文件内容")
        return out.toByteArray()
    }

    private fun readUriPrefixBytes(context: Context, uri: Uri, maxBytes: Int): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream()
        var reachedCap = false
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(8 * 1024)
            var remaining = maxBytes
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size, remaining))
                if (n <= 0) break
                out.write(buf, 0, n)
                remaining -= n
            }
            if (remaining == 0 && input.read() != -1) {
                reachedCap = true
            }
        } ?: error("无法读取文件内容")
        return out.toByteArray() to reachedCap
    }

    private fun computeInSampleSize(rawWidth: Int, rawHeight: Int, maxEdge: Int): Int {
        var sample = 1
        while ((rawWidth / sample) > maxEdge * 2 || (rawHeight / sample) > maxEdge * 2) {
            sample *= 2
        }
        return sample
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun scaleBitmapToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest.toFloat()
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun encodeJpegCompact(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY_DEFAULT, out)
        val first = out.toByteArray()
        if (first.size <= JPEG_RECOMPRESS_THRESHOLD_BYTES) return first
        out.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY_FALLBACK, out)
        return out.toByteArray()
    }
}
