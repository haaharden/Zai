package com.self.zai.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class BulletItem(val text: String, val indent: Int = 0) : MdBlock
    data class OrderedItem(val number: String, val text: String, val indent: Int = 0) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class CodeBlock(val language: String, val code: String) : MdBlock
    data object HorizontalRule : MdBlock
}

sealed interface MdInline {
    data class Text(val value: String) : MdInline
    data class Bold(val value: String) : MdInline
    data class Italic(val value: String) : MdInline
    data class BoldItalic(val value: String) : MdInline
    data class Code(val value: String) : MdInline
    data class Strike(val value: String) : MdInline
    data class Link(val label: String, val url: String) : MdInline
}

/**
 * Fast, streaming-safe, zero-dependency Markdown parser designed for mobile LLM output.
 */
object ZaiMarkdownParser {

    private val headingRegex = Regex("^(#{1,4})\\s+(.+)$")
    private val bulletRegex = Regex("^(\\s*)[-*+•]\\s+(.+)$")
    private val orderedRegex = Regex("^(\\s*)(\\d+)[.)]\\s+(.+)$")
    private val hrRegex = Regex("^([-*_])(\\s*\\1){2,}$")

    fun parseBlocks(markdown: String): List<MdBlock> {
        if (markdown.isEmpty()) return emptyList()
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<MdBlock>()
        val paragraphLines = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraphLines.isNotEmpty()) {
                blocks += MdBlock.Paragraph(paragraphLines.joinToString("\n"))
                paragraphLines.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // Fenced code block (``` or ~~~) — tolerates unclosed fence during streaming
            val fenceMarker = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            if (fenceMarker != null) {
                flushParagraph()
                val lang = trimmed.removePrefix(fenceMarker).trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size) {
                    if (lines[i].trim().startsWith(fenceMarker)) {
                        i++
                        break
                    }
                    codeLines += lines[i]
                    i++
                }
                blocks += MdBlock.CodeBlock(language = lang, code = codeLines.joinToString("\n"))
                continue
            }

            if (trimmed.isEmpty()) {
                flushParagraph()
                i++
                continue
            }

            if (hrRegex.matches(trimmed)) {
                flushParagraph()
                blocks += MdBlock.HorizontalRule
                i++
                continue
            }

            headingRegex.matchEntire(trimmed)?.let { m ->
                flushParagraph()
                val level = m.groupValues[1].length
                val title = m.groupValues[2].trimEnd('#').trim()
                blocks += MdBlock.Heading(level = level, text = title)
                i++
                continue
            }

            if (trimmed.startsWith(">")) {
                flushParagraph()
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoteLines += lines[i].trim().removePrefix(">").trimStart()
                    i++
                }
                blocks += MdBlock.Quote(quoteLines.joinToString("\n"))
                continue
            }

            bulletRegex.matchEntire(line)?.let { m ->
                flushParagraph()
                val indent = (m.groupValues[1].length / 2).coerceIn(0, 3)
                blocks += MdBlock.BulletItem(text = m.groupValues[2], indent = indent)
                i++
                continue
            }

            orderedRegex.matchEntire(line)?.let { m ->
                flushParagraph()
                val indent = (m.groupValues[1].length / 2).coerceIn(0, 3)
                blocks += MdBlock.OrderedItem(
                    number = m.groupValues[2],
                    text = m.groupValues[3],
                    indent = indent,
                )
                i++
                continue
            }

            paragraphLines += line
            i++
        }

        flushParagraph()
        return blocks
    }

    fun parseInlines(text: String): List<MdInline> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<MdInline>()
        val plain = StringBuilder()
        val len = text.length
        var i = 0

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                result += MdInline.Text(plain.toString())
                plain.setLength(0)
            }
        }

        while (i < len) {
            val ch = text[i]

            // Inline code `...`
            if (ch == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    flushPlain()
                    result += MdInline.Code(text.substring(i + 1, end))
                    i = end + 1
                    continue
                }
            }

            // Link [label](url)
            if (ch == '[') {
                val closeBracket = text.indexOf(']', i + 1)
                if (closeBracket > i + 1 && closeBracket + 1 < len && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen > closeBracket + 2) {
                        val label = text.substring(i + 1, closeBracket)
                        val url = text.substring(closeBracket + 2, closeParen).trim()
                        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
                            flushPlain()
                            result += MdInline.Link(label = label, url = url)
                            i = closeParen + 1
                            continue
                        }
                    }
                }
            }

            // Bold + Italic ***...***
            if (ch == '*' && i + 2 < len && text[i + 1] == '*' && text[i + 2] == '*') {
                val end = text.indexOf("***", i + 3)
                if (end > i + 3) {
                    flushPlain()
                    result += MdInline.BoldItalic(text.substring(i + 3, end))
                    i = end + 3
                    continue
                }
            }

            // Bold **...** or __...__
            if ((ch == '*' || ch == '_') && i + 1 < len && text[i + 1] == ch) {
                val marker = if (ch == '*') "**" else "__"
                val end = text.indexOf(marker, i + 2)
                if (end > i + 2 && !text[i + 2].isWhitespace()) {
                    flushPlain()
                    result += MdInline.Bold(text.substring(i + 2, end))
                    i = end + 2
                    continue
                }
            }

            // Strikethrough ~~...~~
            if (ch == '~' && i + 1 < len && text[i + 1] == '~') {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    result += MdInline.Strike(text.substring(i + 2, end))
                    i = end + 2
                    continue
                }
            }

            // Italic *...* (avoid breaking math like "2 * 3 * 4")
            if (ch == '*' && i + 1 < len && !text[i + 1].isWhitespace() && text[i + 1] != '*') {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1 && !text[end - 1].isWhitespace()) {
                    flushPlain()
                    result += MdInline.Italic(text.substring(i + 1, end))
                    i = end + 1
                    continue
                }
            }

            plain.append(ch)
            i++
        }

        flushPlain()
        return result
    }
}

/**
 * Clean Anthropic-styled Markdown renderer supporting headings, bold/italic/strike, inline code,
 * tonal code blocks with one-tap copy, quotes, lists, clickable links, and text selection.
 */
@Composable
fun ZaiMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onBackground,
    baseTextStyle: TextStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp),
    selectable: Boolean = true,
) {
    val blocks = remember(markdown) { ZaiMarkdownParser.parseBlocks(markdown) }
    val codeBg = MaterialTheme.colorScheme.surfaceContainerLow
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val linkColor = MaterialTheme.colorScheme.primary
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outlineVariant

    val content: @Composable () -> Unit = {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Heading -> {
                        val headingStyle = when (block.level) {
                            1 -> baseTextStyle.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                            2 -> baseTextStyle.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                            else -> baseTextStyle.copy(fontSize = 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            text = rememberInlineAnnotated(block.text, inlineCodeBg, linkColor),
                            style = headingStyle,
                            color = textColor,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    is MdBlock.Paragraph -> {
                        Text(
                            text = rememberInlineAnnotated(block.text, inlineCodeBg, linkColor),
                            style = baseTextStyle,
                            color = textColor,
                        )
                    }

                    is MdBlock.BulletItem -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (block.indent * 14).dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "•",
                                style = baseTextStyle.copy(fontWeight = FontWeight.Bold),
                                color = mutedColor,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(
                                text = rememberInlineAnnotated(block.text, inlineCodeBg, linkColor),
                                style = baseTextStyle,
                                color = textColor,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    is MdBlock.OrderedItem -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (block.indent * 14).dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "${block.number}.",
                                style = baseTextStyle.copy(fontWeight = FontWeight.Medium),
                                color = mutedColor,
                                modifier = Modifier.padding(end = 7.dp),
                            )
                            Text(
                                text = rememberInlineAnnotated(block.text, inlineCodeBg, linkColor),
                                style = baseTextStyle,
                                color = textColor,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    is MdBlock.Quote -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(2.5.dp)
                                    .height(20.dp)
                                    .align(Alignment.CenterVertically)
                                    .clip(CircleShape)
                                    .background(linkColor.copy(alpha = 0.55f)),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = rememberInlineAnnotated(block.text, inlineCodeBg, linkColor),
                                style = baseTextStyle.copy(fontSize = (baseTextStyle.fontSize.value - 0.5f).sp),
                                color = mutedColor,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    is MdBlock.CodeBlock -> {
                        MarkdownCodeBlock(
                            language = block.language,
                            code = block.code,
                            containerColor = codeBg,
                            borderColor = outlineColor,
                        )
                    }

                    MdBlock.HorizontalRule -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .height(0.5.dp)
                                .background(outlineColor),
                        )
                    }
                }
            }
        }
    }

    if (selectable) {
        SelectionContainer {
            content()
        }
    } else {
        content()
    }
}

@Composable
private fun rememberInlineAnnotated(
    text: String,
    inlineCodeBg: Color,
    linkColor: Color,
): AnnotatedString = remember(text, inlineCodeBg, linkColor) {
    val spans = ZaiMarkdownParser.parseInlines(text)
    buildAnnotatedString {
        spans.forEach { span ->
            when (span) {
                is MdInline.Text -> append(span.value)
                is MdInline.Bold -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                    append(span.value)
                    pop()
                }
                is MdInline.Italic -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(span.value)
                    pop()
                }
                is MdInline.BoldItalic -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic))
                    append(span.value)
                    pop()
                }
                is MdInline.Code -> {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.5.sp,
                            background = inlineCodeBg,
                        ),
                    )
                    append(" ${span.value} ")
                    pop()
                }
                is MdInline.Strike -> {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    append(span.value)
                    pop()
                }
                is MdInline.Link -> {
                    pushLink(
                        LinkAnnotation.Url(
                            url = span.url,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                        ),
                    )
                    append(span.label)
                    pop()
                }
            }
        }
    }
}

@Composable
private fun MarkdownCodeBlock(
    language: String,
    code: String,
    containerColor: Color,
    borderColor: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .border(0.5.dp, borderColor, RoundedCornerShape(14.dp)),
    ) {
        DisableSelection {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                ZaiCopyIconButton(
                    textToCopy = code,
                    clipLabel = "Zai Code",
                    iconSizeDp = 14,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(borderColor.copy(alpha = 0.7f)),
        )
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 19.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/**
 * Restrained one-tap copy icon that briefly confirms with a quiet check mark.
 */
@Composable
fun ZaiCopyIconButton(
    textToCopy: String,
    clipLabel: String = "Zai Reply",
    iconSizeDp: Int = 15,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var copied by remember(textToCopy) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400L)
            copied = false
        }
    }

    Box(
        modifier = modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                copyPlainText(context, clipLabel, textToCopy)
                copied = true
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = if (copied) "已复制" else "复制",
            tint = if (copied) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            },
            modifier = Modifier.size(iconSizeDp.dp),
        )
    }
}

fun copyPlainText(context: Context, label: String, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
