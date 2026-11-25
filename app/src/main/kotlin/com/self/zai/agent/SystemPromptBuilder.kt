package com.self.zai.agent

import com.self.zai.model.AppSettings
import com.self.zai.phone.PhoneToolCatalog

/**
 * Composes Zai's layered system prompt transparently:
 * 1. Base phone-automation prompt ([PhoneToolCatalog.buildSystemPrompt])
 * 2. Optional user custom system prompt ([AppSettings.userSystemPrompt])
 * 3. Progressive-disclosure catalog of enabled Skills (only names & descriptions; full body loaded via `read_skill`)
 * 4. Concise MCP tool integration note when remote MCP servers are enabled
 */
object SystemPromptBuilder {

    fun build(settings: AppSettings, supportsImages: Boolean): String = buildString {
        append(PhoneToolCatalog.buildSystemPrompt(supportsImages).trimEnd())

        val customPrompt = settings.userSystemPrompt.trim()
        if (customPrompt.isNotEmpty()) {
            append("\n\n## 用户自定义指令\n")
            append(customPrompt)
        }

        val enabledSkills = settings.skills.filter { it.enabled }
        if (enabledSkills.isNotEmpty()) {
            append("\n\n## 可用技能 (Skills)\n")
            append("当用户任务与以下技能相关时，请先调用 `read_skill(name)` 加载完整技能说明，再严格按其中的指引执行：\n")
            for (skill in enabledSkills) {
                append("- ")
                append(skill.name)
                if (skill.description.isNotBlank()) {
                    append(": ")
                    append(skill.description.replace("\n", " ").trim())
                }
                append('\n')
            }
        }

        val enabledMcpServers = settings.mcpServers.filter { it.enabled && it.tools.isNotEmpty() }
        if (enabledMcpServers.isNotEmpty()) {
            val serverList = enabledMcpServers.joinToString("、") { "${it.name}(${it.tools.size}个工具)" }
            append("\n\n## 已接入 MCP 外部工具\n")
            append("以下远程 MCP 工具已注册并可直接调用：$serverList。外部 MCP 工具无需切离当前应用，可与手机操控工具组合完成复杂任务。")
        }
    }

    /**
     * Deterministic fingerprint covering prompt + enabled skills + enabled MCP tools + compaction config
     * so Pi session configuration auto-refreshes whenever any capability changes.
     */
    fun configFingerprint(settings: AppSettings, supportsImages: Boolean): String {
        val sb = StringBuilder()
        sb.append(supportsImages).append('|')
        sb.append(settings.compactionMode.name).append(':').append(settings.compactionThresholdPercent).append('|')
        sb.append(settings.userSystemPrompt.trim().hashCode()).append('|')
        for (sk in settings.skills.filter { it.enabled }) {
            sb.append(sk.id).append(':')
                .append(sk.name).append(':')
                .append(sk.description.hashCode()).append(':')
                .append(sk.instructions.hashCode()).append(';')
        }
        sb.append('|')
        for (srv in settings.mcpServers.filter { it.enabled }) {
            sb.append(srv.id).append(':').append(srv.url).append(':')
            for (t in srv.tools) {
                sb.append(t.name).append('#').append(t.inputSchemaJson.hashCode()).append(',')
            }
            sb.append(';')
        }
        return sb.toString()
    }
}
