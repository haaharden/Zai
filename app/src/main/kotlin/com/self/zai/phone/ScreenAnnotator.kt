package com.self.zai.phone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.StringReader
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws compact Set-of-Mark (SoM) numbered bounding boxes over interactive UI elements
 * and compresses the screenshot for fast, token-efficient vision model reasoning.
 */
object ScreenAnnotator {

    fun annotateAndEncodeJpeg(
        source: Bitmap,
        elements: List<UiElement>,
        maxLongEdge: Int = 840,
        jpegQuality: Int = 62,
    ): String {
        val origW = source.width
        val origH = source.height
        val scale = if (max(origW, origH) > maxLongEdge) {
            maxLongEdge.toFloat() / max(origW, origH).toFloat()
        } else {
            1f
        }
        val outW = (origW * scale).roundToInt().coerceAtLeast(1)
        val outH = (origH * scale).roundToInt().coerceAtLeast(1)

        val canvasBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawBitmap(source, null, Rect(0, 0, outW, outH), Paint(Paint.FILTER_BITMAP_FLAG))

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
            color = Color.argb(215, 35, 86, 232) // SignalCobalt
        }
        val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(232, 17, 20, 25) // CarbonInk
        }
        val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            isFakeBoldText = true
        }

        val fullArea = origW.toLong() * origH.toLong()
        for (el in elements) {
            if (!el.clickable && !el.editable) continue
            val area = el.bounds.width().toLong() * el.bounds.height().toLong()
            if (fullArea > 0 && area > fullArea * 0.65) continue // skip full-screen wrappers

            val left = el.bounds.left * scale
            val top = el.bounds.top * scale
            val right = el.bounds.right * scale
            val bottom = el.bounds.bottom * scale
            canvas.drawRect(left, top, right, bottom, boxPaint)

            val label = el.index.toString()
            val textW = tagTextPaint.measureText(label)
            val padX = 7f
            val tagH = 28f
            val tagW = textW + padX * 2
            val tagLeft = left.coerceIn(0f, (outW - tagW).coerceAtLeast(0f))
            val tagTop = (top - tagH * 0.35f).coerceIn(0f, (outH - tagH).coerceAtLeast(0f))
            canvas.drawRoundRect(
                RectF(tagLeft, tagTop, tagLeft + tagW, tagTop + tagH),
                6f,
                6f,
                tagBgPaint,
            )
            canvas.drawText(label, tagLeft + padX, tagTop + tagH - 7f, tagTextPaint)
        }

        val out = ByteArrayOutputStream()
        canvasBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        canvasBitmap.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

/**
 * Parses `uiautomator dump` XML into [UiElement] nodes so Zai can still read screens purely via Shizuku
 * even when AccessibilityService is disabled or killed by an aggressive OEM ROM.
 */
object UiAutomatorXmlParser {

    private val BOUNDS_REGEX = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")

    fun parse(xml: String, screenWidth: Int, screenHeight: Int): ScreenSnapshot {
        val elements = mutableListOf<UiElement>()
        val seen = HashSet<String>()
        var rootPackage = ""

        runCatching {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && elements.size < 100) {
                if (event == XmlPullParser.START_TAG && parser.name == "node") {
                    val pkg = parser.getAttributeValue(null, "package").orEmpty()
                    if (rootPackage.isEmpty() && pkg.isNotEmpty()) rootPackage = pkg

                    val boundsStr = parser.getAttributeValue(null, "bounds").orEmpty()
                    val m = BOUNDS_REGEX.find(boundsStr)
                    if (m != null) {
                        val rect = Rect(
                            m.groupValues[1].toInt(),
                            m.groupValues[2].toInt(),
                            m.groupValues[3].toInt(),
                            m.groupValues[4].toInt(),
                        )
                        val text = parser.getAttributeValue(null, "text").orEmpty().trim()
                        val desc = parser.getAttributeValue(null, "content-desc").orEmpty().trim()
                        val cls = parser.getAttributeValue(null, "class").orEmpty()
                        val resId = parser.getAttributeValue(null, "resource-id").orEmpty()
                        val clickable = parser.getAttributeValue(null, "clickable") == "true"
                        val scrollable = parser.getAttributeValue(null, "scrollable") == "true"
                        val checkable = parser.getAttributeValue(null, "checkable") == "true"
                        val checked = parser.getAttributeValue(null, "checked") == "true"
                        val password = parser.getAttributeValue(null, "password") == "true"
                        val editable = cls.contains("EditText", ignoreCase = true)

                        val meaningful = clickable || editable || scrollable || text.isNotEmpty() || desc.isNotEmpty()
                        if (rect.width() > 4 && rect.height() > 4 && meaningful) {
                            val key = "${rect.left},${rect.top},${rect.right},${rect.bottom}|$text|$desc"
                            if (seen.add(key)) {
                                elements += UiElement(
                                    index = elements.size + 1,
                                    text = text,
                                    contentDesc = desc,
                                    viewId = resId,
                                    className = cls,
                                    bounds = rect,
                                    clickable = clickable,
                                    scrollable = scrollable,
                                    editable = editable,
                                    checked = if (checkable) checked else null,
                                    isPassword = password,
                                )
                            }
                        }
                    }
                }
                event = parser.next()
            }
        }

        return ScreenSnapshot(
            packageName = rootPackage,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            elements = elements,
            source = "shizuku_uiautomator",
        )
    }
}
