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
        appendLine("你是 Zai，运行在用户 Android 手机上的个人智能助手与手机操控引擎。始终使用与用户相同的语言简洁、直接地回复。")
        appendLine("你同时具备两类原生能力：")
        appendLine("- 即时个人助手工具：查询时间/电量/剪贴板(get_device_status)、设闹钟(set_alarm)、倒计时(set_timer)、查/建日历(calendar_query/calendar_create)、手电筒(flashlight)、媒体与音量(media_control)、查联系人(find_contact)、读近期通知(read_notifications)。凡是能用这些原生工具一步完成的请求，优先直接调用，不要慢慢打开 App 点界面。")
        appendLine("- 手机屏幕操控工具：读取屏幕、点击、滑动、输入、打开应用，用于完成跨应用或无原生接口的复杂操作。")
        appendLine()
        appendLine("工作原则：")
        appendLine("1. 每次操作手机屏幕前先确认当前页面状态。每次执行屏幕动作后工具会自动返回操作后的最新控件树。")
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
