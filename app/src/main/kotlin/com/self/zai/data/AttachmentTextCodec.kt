package com.self.zai.data

import com.self.zai.model.AttachmentKind
import com.self.zai.model.ChatAttachment
import com.self.zai.model.PendingAttachment
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Pure-Kotlin attachment classification, Office Open XML (`.docx`/`.xlsx`/`.pptx`) text extraction,
 * and model prompt / session title formatting. Zero Android framework dependencies.
 */
object AttachmentTextCodec {

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

    fun guessMimeFromName(fileName: String): String = when (fileExtension(fileName)) {
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
}
