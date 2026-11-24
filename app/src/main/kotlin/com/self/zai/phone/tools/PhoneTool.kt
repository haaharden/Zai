package com.self.zai.phone.tools

import com.self.zai.phone.AppDirectory
import com.self.zai.phone.ScreenSnapshot
import com.self.zai.phone.UiElement
import com.self.zai.phone.channel.PhoneDeviceChannel
import com.self.zai.pi.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Execution context passed to every registered [PhoneTool].
 */
interface PhoneToolContext {
    val channel: PhoneDeviceChannel
    val appDirectory: AppDirectory
    val lastSnapshot: ScreenSnapshot?

    fun resolveElement(id: Int): UiElement? = lastSnapshot?.elements?.firstOrNull { it.index == id }
    suspend fun observeScreen(prefix: String? = null, forceImage: Boolean = false): ToolExecutionResult
}

/**
 * Self-contained plugin contract for a phone automation tool.
 * Keeps schema definition, human-readable label, and execution logic together in one place.
 */
interface PhoneTool {
    val name: String
    val label: String
    val description: String
    val requiresVision: Boolean get() = false
    val requiredParams: List<String> get() = emptyList()

    fun parametersSchema(): JSONObject = JSONObject()
    fun humanLabel(args: JSONObject): String = label
    suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult

    fun toSpecJson(): JSONObject {
        val params = JSONObject()
            .put("type", "object")
            .put("properties", parametersSchema())
        if (requiredParams.isNotEmpty()) {
            params.put("required", JSONArray(requiredParams))
        }
        return JSONObject()
            .put("name", name)
            .put("label", label)
            .put("description", description)
            .put("parameters", params)
    }
}

/**
 * Extensible registry of [PhoneTool] plugins. Single source of truth for schema export and tool routing.
 */
class PhoneToolRegistry(tools: List<PhoneTool>) {
    private val ordered = LinkedHashMap<String, PhoneTool>().apply {
        for (tool in tools) put(tool.name, tool)
    }

    fun register(tool: PhoneTool) {
        ordered[tool.name] = tool
    }

    fun get(name: String): PhoneTool? = ordered[name]

    fun all(): List<PhoneTool> = ordered.values.toList()

    fun toolsJson(includeVisionTools: Boolean): JSONArray {
        val arr = JSONArray()
        for (tool in ordered.values) {
            if (tool.requiresVision && !includeVisionTools) continue
            arr.put(tool.toSpecJson())
        }
        return arr
    }

    fun humanLabel(toolName: String, args: JSONObject): String =
        ordered[toolName]?.humanLabel(args) ?: toolName

    companion object {
        fun default(): PhoneToolRegistry = PhoneToolRegistry(builtInPhoneTools())
    }
}
