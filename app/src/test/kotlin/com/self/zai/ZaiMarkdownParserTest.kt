package com.self.zai

import com.self.zai.ui.components.MdBlock
import com.self.zai.ui.components.MdInline
import com.self.zai.ui.components.ZaiMarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZaiMarkdownParserTest {

    @Test
    fun parseBlocks_handlesHeadingsListsQuotesCodeAndStreamingFence() {
        val md = """
            # 今日日程
            
            > 提醒：下午有评审
            
            - 09:30 晨会
            - 14:00 产品评审
            1. 准备稿件
            2. 同步结论
            
            ---
            
            ```kotlin
            fun greet() = println("Hi Zai")
            ```
            
            ```bash
            echo "still streaming
        """.trimIndent()

        val blocks = ZaiMarkdownParser.parseBlocks(md)
        assertTrue(blocks[0] is MdBlock.Heading)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertEquals("今日日程", (blocks[0] as MdBlock.Heading).text)

        assertTrue(blocks[1] is MdBlock.Quote)
        assertEquals("提醒：下午有评审", (blocks[1] as MdBlock.Quote).text)

        assertTrue(blocks[2] is MdBlock.BulletItem)
        assertEquals("09:30 晨会", (blocks[2] as MdBlock.BulletItem).text)
        assertTrue(blocks[3] is MdBlock.BulletItem)

        assertTrue(blocks[4] is MdBlock.OrderedItem)
        assertEquals("1", (blocks[4] as MdBlock.OrderedItem).number)
        assertTrue(blocks[5] is MdBlock.OrderedItem)

        assertTrue(blocks[6] is MdBlock.HorizontalRule)

        val closedCode = blocks[7] as MdBlock.CodeBlock
        assertEquals("kotlin", closedCode.language)
        assertEquals("fun greet() = println(\"Hi Zai\")", closedCode.code)

        // Unclosed code fence during live streaming must still render gracefully without crashing
        val streamingCode = blocks[8] as MdBlock.CodeBlock
        assertEquals("bash", streamingCode.language)
        assertEquals("echo \"still streaming", streamingCode.code)
    }

    @Test
    fun parseInlines_handlesFormattingLinksAndPreservesMathAsterisks() {
        val spans = ZaiMarkdownParser.parseInlines(
            "使用 **Zai** 和 `adb` 访问 [主页](https://github.com/haaharden/Zai)，*斜体*，~~删除~~，计算 2 * 3 * 4。",
        )

        assertTrue(spans.any { it == MdInline.Bold("Zai") })
        assertTrue(spans.any { it == MdInline.Code("adb") })
        assertTrue(spans.any { it == MdInline.Link("主页", "https://github.com/haaharden/Zai") })
        assertTrue(spans.any { it == MdInline.Italic("斜体") })
        assertTrue(spans.any { it == MdInline.Strike("删除") })
        // Math expression `2 * 3 * 4` should remain plain text rather than italicized
        assertTrue(spans.filterIsInstance<MdInline.Text>().any { it.value.contains("2 * 3 * 4") })
    }
}
