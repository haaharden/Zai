package com.self.zai.phone

import com.self.zai.phone.tools.PhoneToolRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Prompt & tool-schema facade backed by [PhoneToolRegistry] as the single source of truth.
 */
object PhoneToolCatalog {

    const val SCREEN_NOW = "\n\nScreen now:\n"

    private val defaultRegistry = PhoneToolRegistry.default()

    fun buildSystemPrompt(supportsImages: Boolean): String = buildString {
        appendLine("你是 Zai，运行在用户 Android 手机上的智能操控助手。始终使用与用户相同的语言简洁回复。")
        appendLine("你可以调用工具读取屏幕并执行点击、滑动、输入和打开应用等操作，帮助用户在手机上完成任务。")
        appendLine()
        appendLine("工作原则：")
        appendLine("1. 每次操作前先确认当前页面状态。每次执行动作后工具会自动返回操作后的最新控件树。")
        appendLine("2. 优先使用 tap(id) 点击控件树里的编号 [id]，准确度最高。")
        if (supportsImages) {
            appendLine("3. 如果页面是网页、小程序、图片按钮，或控件树信息不足，请调用 screenshot 查看带编号框的画面；若目标不在编号框里，再用 tap_xy(x, y)（0~1000 归一化坐标）点击。")
        } else {
            appendLine("3. 当前模型不支持看图，请充分利用控件树和 open_app / press_key 完成操作。")
        }
        appendLine("4. 如果当前在前台的是 Zai 自己，先调用 open_app 或 press_key(home) 离开后再操作目标应用。")
        appendLine("5. 遇到支付密码、转账确认、短信验证码等高度敏感步骤时，停下来说明情况并请用户亲自完成。")
        appendLine("6. 任务完成或确认无法继续时，停止调用工具，直接用简短清晰的话向用户汇报结果。")
    }

    fun toolsJson(
        includeScreenshot: Boolean,
        registry: PhoneToolRegistry = defaultRegistry,
    ): JSONArray = registry.toolsJson(includeVisionTools = includeScreenshot)

    fun humanLabel(
        tool: String,
        args: JSONObject,
        registry: PhoneToolRegistry = defaultRegistry,
    ): String = registry.humanLabel(tool, args)
}
