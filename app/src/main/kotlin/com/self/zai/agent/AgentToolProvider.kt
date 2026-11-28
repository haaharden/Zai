package com.self.zai.agent

import com.self.zai.model.AppSettings
import com.self.zai.pi.BeforeToolVerdict
import com.self.zai.pi.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pluggable namespace contract for [AgentToolDispatcher].
 * Adding a new tool category only requires implementing [AgentToolProvider] and registering it —
 * without editing `when` branches across [AgentToolDispatcher] or [AgentCoordinator].
 */
interface AgentToolProvider {
    fun appendToolSpecs(settings: AppSettings, includeVisionTools: Boolean, target: JSONArray)
    fun handles(toolName: String): Boolean
    fun isPhoneNamespace(): Boolean = false
    fun isScreenOperating(toolName: String): Boolean = false
    fun humanLabel(toolName: String, args: JSONObject): String
    fun runningStatusLabel(toolName: String, args: JSONObject): String = "正在${humanLabel(toolName, args)}…"
    suspend fun beforeTool(
        sessionId: String,
        callId: String,
        tool: String,
        args: JSONObject,
    ): BeforeToolVerdict? = null

    suspend fun execute(
        sessionId: String,
        callId: String,
        tool: String,
        args: JSONObject,
    ): ToolExecutionResult

    fun cancel(sessionId: String, callId: String) = Unit
}
