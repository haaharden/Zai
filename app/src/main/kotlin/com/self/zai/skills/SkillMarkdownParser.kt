package com.self.zai.skills

import com.self.zai.model.SkillEntry
import java.util.UUID

/**
 * Parses and exports standard Agent Skills `SKILL.md` documents (YAML frontmatter + Markdown body),
 * and resolves `/skill-name` slash invocations in user messages.
 */
object SkillMarkdownParser {

    data class ParsedSkill(
        val name: String,
        val description: String,
        val instructions: String,
    )

    data class SlashMatch(
        val skill: SkillEntry,
        val remainingText: String,
    )

    /**
     * Parses a `SKILL.md` string with optional YAML frontmatter (`--- ... ---`).
     */
    fun parse(rawMarkdown: String, fallbackName: String = "custom-skill"): ParsedSkill? {
        val text = rawMarkdown.replace("\r\n", "\n").trim()
        if (text.isEmpty()) return null

        var name = ""
        var description = ""
        var body = text

        if (text.startsWith("---\n") || text.startsWith("--- ")) {
            val endIdx = text.indexOf("\n---", startIndex = 3)
            if (endIdx > 0) {
                val frontmatter = text.substring(3, endIdx).trim()
                val afterFence = text.substring(endIdx + 4)
                body = afterFence.removePrefix("\n").trim()

                var currentKey: String? = null
                val descLines = mutableListOf<String>()

                for (rawLine in frontmatter.lines()) {
                    val trimmed = rawLine.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    val colon = rawLine.indexOf(':')
                    val isIndented = rawLine.startsWith(" ") || rawLine.startsWith("\t")
                    if (!isIndented && colon > 0) {
                        val key = rawLine.substring(0, colon).trim().lowercase()
                        val value = unquoteYaml(rawLine.substring(colon + 1).trim())
                        currentKey = key
                        when (key) {
                            "name" -> name = value
                            "description" -> {
                                if (value != "|" && value != ">") {
                                    descLines += value
                                }
                            }
                        }
                    } else if (isIndented && currentKey == "description") {
                        descLines += trimmed
                    }
                }
                description = descLines.joinToString(" ").trim()
            }
        }

        if (name.isBlank()) {
            val heading = body.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("# ") }
                ?.removePrefix("# ")
                ?.trim()
            name = heading?.ifBlank { null } ?: fallbackName
        }
        if (description.isBlank()) {
            description = body.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?.take(80)
                ?: "自定义技能"
        }

        return ParsedSkill(
            name = normalizeSkillName(name),
            description = description,
            instructions = body.ifBlank { description },
        )
    }

    fun toSkillEntry(parsed: ParsedSkill, id: String = "sk_${UUID.randomUUID().toString().take(8)}"): SkillEntry =
        SkillEntry(
            id = id,
            name = parsed.name,
            description = parsed.description,
            instructions = parsed.instructions,
            enabled = true,
        )

    fun exportMarkdown(skill: SkillEntry): String = buildString {
        appendLine("---")
        appendLine("name: ${skill.name}")
        appendLine("description: ${skill.description.replace("\n", " ")}")
        appendLine("---")
        appendLine()
        append(skill.instructions.trim())
        appendLine()
    }

    /**
     * Checks whether a chat message starts with `/<skill-name>` for an enabled skill.
     */
    fun matchSlashInvocation(rawMessage: String, enabledSkills: List<SkillEntry>): SlashMatch? {
        val trimmed = rawMessage.trim()
        if (!trimmed.startsWith("/") || trimmed.length < 2) return null
        val firstSpace = trimmed.indexOfFirst { it.isWhitespace() }
        val token = (if (firstSpace < 0) trimmed.substring(1) else trimmed.substring(1, firstSpace)).trim()
        if (token.isEmpty()) return null
        val rest = if (firstSpace < 0) "" else trimmed.substring(firstSpace).trim()

        val matched = enabledSkills.firstOrNull {
            it.name.equals(token, ignoreCase = true) ||
                normalizeSkillName(it.name).equals(normalizeSkillName(token), ignoreCase = true)
        } ?: return null

        return SlashMatch(skill = matched, remainingText = rest)
    }

    fun normalizeSkillName(raw: String): String =
        raw.trim()
            .replace(Regex("\\s+"), "-")
            .replace("/", "-")
            .take(48)
            .ifBlank { "skill" }

    private fun unquoteYaml(value: String): String {
        if (value.length >= 2) {
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))
            ) {
                return value.substring(1, value.length - 1)
            }
        }
        return value
    }
}
