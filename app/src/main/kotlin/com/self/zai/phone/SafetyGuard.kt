package com.self.zai.phone

import com.self.zai.model.AppSettings
import com.self.zai.model.PendingConfirmation
import com.self.zai.pi.BeforeToolVerdict
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Policy guard executed prior to every phone tool call:
 * 1. Step-budget circuit breaker against infinite loops.
 * 2. Self-interaction guard preventing Zai from tapping inside its own UI.
 * 3. Sensitive-action & password-field confirmation flow with user approval timeout.
 */
class SafetyGuard(
    private val confirmationTimeoutMs: Long = 45_000L,
) {
    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    private val confirmationWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun resolveConfirmation(callId: String, allow: Boolean) {
        confirmationWaiters.remove(callId)?.complete(allow)
        if (_pendingConfirmation.value?.callId == callId) {
            _pendingConfirmation.value = null
        }
    }

    fun clearPendingConfirmations() {
        for ((id, waiter) in confirmationWaiters) {
            confirmationWaiters.remove(id)
            waiter.complete(false)
        }
        _pendingConfirmation.value = null
    }

    suspend fun evaluate(
        callId: String,
        toolName: String,
        args: JSONObject,
        humanLabel: String,
        stepNumber: Int,
        settings: AppSettings,
        activePackage: String,
        ownPackage: String,
        targetElement: UiElement?,
        currentSnapshot: ScreenSnapshot? = null,
    ): BeforeToolVerdict? {
        if (stepNumber > settings.maxStepsPerRun) {
            return BeforeToolVerdict(
                block = true,
                terminate = true,
                reason = "已达到单轮最大操作步数 (${settings.maxStepsPerRun} 步)，已自动停止以防死循环。",
            )
        }

        if (activePackage == ownPackage && toolName in SELF_GUARDED_TOOLS) {
            return BeforeToolVerdict(
                block = true,
                terminate = false,
                reason = "当前前台是 Zai 应用本身。请先调用 open_app 打开目标应用，或调用 press_key(home) 回到桌面。",
            )
        }

        if (settings.confirmSensitiveActions && toolName in SENSITIVE_CHECKED_TOOLS) {
            val targetLabel = targetElement?.displayLabel.orEmpty()
            val textArg = args.optString("text")
            val queryArg = args.optString("query")
            val uriArg = args.optString("uri")
            val sensitiveHit = findSensitiveKeyword(targetLabel, textArg, queryArg)
                ?: if (toolName == "open_uri") findSensitiveUriReason(uriArg) else null
            val isPasswordField = targetElement?.isPassword == true
            val isCoordinateAction = toolName == "tap_xy" || (toolName == "long_press" && !args.has("id"))
            val targetHasExplicitLabel = targetElement != null &&
                (targetElement.text.isNotBlank() || targetElement.contentDesc.isNotBlank())

            val screenFallbackReason = if (
                sensitiveHit == null &&
                !isPasswordField &&
                isCoordinateAction &&
                !targetHasExplicitLabel
            ) {
                findSensitiveScreenContext(currentSnapshot)
            } else {
                null
            }

            if (sensitiveHit != null || isPasswordField || screenFallbackReason != null) {
                val title = when {
                    isPasswordField -> "涉及密码输入，需要你确认"
                    sensitiveHit != null -> "检测到敏感操作「$sensitiveHit」"
                    else -> "敏感页面坐标点击，需要你确认"
                }
                val detail = when {
                    sensitiveHit != null || isPasswordField ->
                        humanLabel + if (targetLabel.isNotBlank()) "（目标：$targetLabel）" else ""
                    else ->
                        "$humanLabel（当前页面包含「$screenFallbackReason」，坐标目标无法可靠识别，请核对画面后确认）"
                }
                val allowed = askUserConfirmation(callId, title, detail)
                if (!allowed) {
                    return BeforeToolVerdict(
                        block = true,
                        terminate = false,
                        reason = "用户拒绝了该敏感操作（$detail），请停止敏感步骤或征询用户意见。",
                    )
                }
            }
        }
        return null
    }

    private fun findSensitiveKeyword(vararg candidates: String): String? =
        SENSITIVE_KEYWORDS.firstOrNull { kw ->
            candidates.any { it.contains(kw, ignoreCase = true) }
        }

    private fun findSensitiveUriReason(uri: String): String? {
        if (uri.isBlank()) return null
        val lower = uri.trim().lowercase()
        return when {
            lower.startsWith("tel:") -> "拨打电话"
            lower.startsWith("sms:") || lower.startsWith("smsto:") -> "发送短信"
            lower.startsWith("alipays://") || lower.contains("wxpay") ||
                lower.contains("pay?") || lower.contains("/pay") || lower.contains("transfer") -> "支付或转账链接"
            else -> null
        }
    }

    private fun findSensitiveScreenContext(snapshot: ScreenSnapshot?): String? {
        if (snapshot == null) return null
        if (snapshot.elements.any { it.isPassword }) return "密码输入区"
        for (el in snapshot.elements) {
            val hit = findSensitiveKeyword(el.text, el.contentDesc, el.displayLabel)
            if (hit != null) return hit
        }
        return null
    }

    private suspend fun askUserConfirmation(callId: String, title: String, detail: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        confirmationWaiters[callId] = deferred
        _pendingConfirmation.value = PendingConfirmation(callId = callId, title = title, detail = detail)
        val result = withTimeoutOrNull(confirmationTimeoutMs) { deferred.await() } ?: false
        confirmationWaiters.remove(callId)
        if (_pendingConfirmation.value?.callId == callId) {
            _pendingConfirmation.value = null
        }
        return result
    }

    companion object {
        private val SELF_GUARDED_TOOLS = setOf("tap", "tap_xy", "long_press", "swipe", "input_text", "scroll_find_tap", "run_actions")
        private val SENSITIVE_CHECKED_TOOLS = setOf("tap", "tap_xy", "input_text", "long_press", "scroll_find_tap", "open_uri")

        private val SENSITIVE_KEYWORDS = listOf(
            "确认支付", "立即支付", "付款", "转账", "确认转账", "支付密码", "输入密码",
            "卸载", "删除账号", "注销账号", "清除数据", "格式化", "pay now", "confirm payment",
        )
    }
}
