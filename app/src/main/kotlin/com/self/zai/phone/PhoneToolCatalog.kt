package com.self.zai.phone

import org.json.JSONArray
import org.json.JSONObject

object PhoneToolCatalog {

    const val SCREEN_NOW = "\n\nScreen now:\n"

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

    fun toolsJson(includeScreenshot: Boolean): JSONArray {
        val arr = JSONArray()
        arr.put(
            tool(
                name = "read_screen",
                label = "读取屏幕",
                description = "读取当前屏幕上的可见控件列表（带 [编号] 与位置）。如果 with_image=true 且模型支持看图，还会附带标注编号的截图。",
                properties = JSONObject()
                    .put(
                        "with_image",
                        JSONObject()
                            .put("type", "boolean")
                            .put("description", "是否同时附带带编号框的截图，默认 false"),
                    ),
            ),
        )
        if (includeScreenshot) {
            arr.put(
                tool(
                    name = "screenshot",
                    label = "截屏观察",
                    description = "截取当前屏幕并绘制控件编号框，同时返回图像和控件列表，用于识别图标、网页、小程序或弹窗。",
                    properties = JSONObject(),
                ),
            )
        }
        arr.put(
            tool(
                name = "tap",
                label = "点击控件",
                description = "点击最新控件树中指定编号 [id] 的控件中心位置。",
                properties = JSONObject()
                    .put("id", JSONObject().put("type", "integer").put("description", "控件编号")),
                required = listOf("id"),
            ),
        )
        arr.put(
            tool(
                name = "tap_xy",
                label = "坐标点击",
                description = "按归一化坐标 (0~1000) 点击屏幕指定位置。x=0 为最左、1000 为最右；y=0 为最顶、1000 为最底。",
                properties = JSONObject()
                    .put("x", JSONObject().put("type", "integer").put("description", "横向坐标 0..1000"))
                    .put("y", JSONObject().put("type", "integer").put("description", "纵向坐标 0..1000")),
                required = listOf("x", "y"),
            ),
        )
        arr.put(
            tool(
                name = "long_press",
                label = "长按",
                description = "长按控件编号 id，或按 0~1000 坐标 (x, y) 长按。",
                properties = JSONObject()
                    .put("id", JSONObject().put("type", "integer").put("description", "可选：控件编号"))
                    .put("x", JSONObject().put("type", "integer").put("description", "可选：横向坐标 0..1000"))
                    .put("y", JSONObject().put("type", "integer").put("description", "可选：纵向坐标 0..1000"))
                    .put("duration_ms", JSONObject().put("type", "integer").put("description", "长按毫秒数，默认 700")),
            ),
        )
        arr.put(
            tool(
                name = "swipe",
                label = "滑动屏幕",
                description = "滑动屏幕。推荐直接传 direction (up/down/left/right)，也可传 0~1000 起止坐标 (x1,y1,x2,y2)。注意：direction='up' 表示手指向上滑（查看下方更多内容）。",
                properties = JSONObject()
                    .put(
                        "direction",
                        JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray(listOf("up", "down", "left", "right"))),
                    )
                    .put("x1", JSONObject().put("type", "integer"))
                    .put("y1", JSONObject().put("type", "integer"))
                    .put("x2", JSONObject().put("type", "integer"))
                    .put("y2", JSONObject().put("type", "integer"))
                    .put("duration_ms", JSONObject().put("type", "integer")),
            ),
        )
        arr.put(
            tool(
                name = "input_text",
                label = "输入文字",
                description = "向输入框输入中文或英文文本。如果传了 id 会先点击该输入框再输入。",
                properties = JSONObject()
                    .put("text", JSONObject().put("type", "string").put("description", "要输入的文本"))
                    .put("id", JSONObject().put("type", "integer").put("description", "可选：目标输入框编号"))
                    .put("press_enter", JSONObject().put("type", "boolean").put("description", "输入后是否按回车/搜索键")),
                required = listOf("text"),
            ),
        )
        arr.put(
            tool(
                name = "press_key",
                label = "系统按键",
                description = "按下系统按键：back（返回）、home（回桌面）、recents（多任务）、enter（回车）。",
                properties = JSONObject()
                    .put(
                        "key",
                        JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray(listOf("back", "home", "recents", "enter"))),
                    ),
                required = listOf("key"),
            ),
        )
        arr.put(
            tool(
                name = "open_app",
                label = "打开应用",
                description = "按中文名、英文名或包名直接启动手机上的应用，例如「微信」「设置」「高德地图」「哔哩哔哩」。",
                properties = JSONObject()
                    .put("app_name", JSONObject().put("type", "string").put("description", "应用名称或包名")),
                required = listOf("app_name"),
            ),
        )
        arr.put(
            tool(
                name = "list_apps",
                label = "查找应用",
                description = "按关键词搜索手机已安装的可启动应用，返回名称与包名。",
                properties = JSONObject()
                    .put("query", JSONObject().put("type", "string").put("description", "可选关键词")),
            ),
        )
        arr.put(
            tool(
                name = "wait",
                label = "等待加载",
                description = "等待页面加载 1~5 秒后返回最新屏幕状态。",
                properties = JSONObject()
                    .put("seconds", JSONObject().put("type", "number").put("description", "等待秒数，1~5")),
            ),
        )
        return arr
    }

    fun humanLabel(tool: String, args: JSONObject): String = when (tool) {
        "read_screen" -> if (args.optBoolean("with_image")) "读屏并截屏" else "读取屏幕控件"
        "screenshot" -> "截屏观察画面"
        "tap" -> "点击控件 #${args.optInt("id")}"
        "tap_xy" -> "点击坐标 (${args.optInt("x")}, ${args.optInt("y")})"
        "long_press" -> if (args.has("id")) "长按控件 #${args.optInt("id")}" else "长按屏幕"
        "swipe" -> {
            val dir = when (args.optString("direction")) {
                "up" -> "向上滑动"
                "down" -> "向下滑动"
                "left" -> "向左滑动"
                "right" -> "向右滑动"
                else -> "滑动屏幕"
            }
            dir
        }
        "input_text" -> "输入「${args.optString("text").take(18)}」"
        "press_key" -> when (args.optString("key")) {
            "back" -> "按下返回键"
            "home" -> "回到桌面"
            "recents" -> "打开多任务"
            "enter" -> "按下回车"
            else -> "按下按键"
        }
        "open_app" -> "打开 ${args.optString("app_name")}"
        "list_apps" -> "搜索已装应用"
        "wait" -> "等待页面加载"
        else -> tool
    }

    private fun tool(
        name: String,
        label: String,
        description: String,
        properties: JSONObject,
        required: List<String> = emptyList(),
    ): JSONObject {
        val params = JSONObject()
            .put("type", "object")
            .put("properties", properties)
        if (required.isNotEmpty()) {
            params.put("required", JSONArray(required))
        }
        return JSONObject()
            .put("name", name)
            .put("label", label)
            .put("description", description)
            .put("parameters", params)
    }
}
