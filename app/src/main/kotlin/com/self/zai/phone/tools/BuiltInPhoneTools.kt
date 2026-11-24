package com.self.zai.phone.tools

import com.self.zai.pi.ToolExecutionResult
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

fun builtInPhoneTools(): List<PhoneTool> = listOf(
    ReadScreenTool,
    ScreenshotTool,
    TapTool,
    TapXyTool,
    LongPressTool,
    SwipeTool,
    InputTextTool,
    PressKeyTool,
    OpenAppTool,
    ListAppsTool,
    WaitTool,
)

private object ReadScreenTool : PhoneTool {
    override val name = "read_screen"
    override val label = "读取屏幕"
    override val description = "读取当前屏幕上的可见控件列表（带 [编号] 与位置）。如果 with_image=true 且模型支持看图，还会附带标注编号的截图。"

    override fun parametersSchema(): JSONObject = JSONObject().put(
        "with_image",
        JSONObject()
            .put("type", "boolean")
            .put("description", "是否同时附带带编号框的截图，默认 false"),
    )

    override fun humanLabel(args: JSONObject): String =
        if (args.optBoolean("with_image")) "读屏并截屏" else "读取屏幕控件"

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult =
        ctx.observeScreen(prefix = null, forceImage = args.optBoolean("with_image", false))
}

private object ScreenshotTool : PhoneTool {
    override val name = "screenshot"
    override val label = "截屏观察"
    override val description = "截取当前屏幕并绘制控件编号框，同时返回图像和控件列表，用于识别图标、网页、小程序或弹窗。"
    override val requiresVision = true

    override fun humanLabel(args: JSONObject): String = "截屏观察画面"

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult =
        ctx.observeScreen(prefix = null, forceImage = true)
}

private object TapTool : PhoneTool {
    override val name = "tap"
    override val label = "点击控件"
    override val description = "点击最新控件树中指定编号 [id] 的控件中心位置。"
    override val requiredParams = listOf("id")

    override fun parametersSchema(): JSONObject = JSONObject().put(
        "id",
        JSONObject().put("type", "integer").put("description", "控件编号"),
    )

    override fun humanLabel(args: JSONObject): String = "点击控件 #${args.optInt("id")}"

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val id = args.optInt("id", -1)
        val el = ctx.resolveElement(id)
            ?: return ToolExecutionResult("未找到编号 [$id] 的控件，请先调用 read_screen 刷新当前页面。", isError = true)
        val ok = ctx.channel.tap(el.centerX, el.centerY)
        if (!ok) return ToolExecutionResult("点击失败：请检查无障碍或 Shizuku 连接状态。", isError = true)
        delay(650L)
        return ctx.observeScreen(prefix = "已点击 [$id] ${el.displayLabel}")
    }
}

private object TapXyTool : PhoneTool {
    override val name = "tap_xy"
    override val label = "坐标点击"
    override val description = "按归一化坐标 (0~1000) 点击屏幕指定位置。x=0 为最左、1000 为最右；y=0 为最顶、1000 为最底。"
    override val requiredParams = listOf("x", "y")

    override fun parametersSchema(): JSONObject = JSONObject()
        .put("x", JSONObject().put("type", "integer").put("description", "横向坐标 0..1000"))
        .put("y", JSONObject().put("type", "integer").put("description", "纵向坐标 0..1000"))

    override fun humanLabel(args: JSONObject): String = "点击坐标 (${args.optInt("x")}, ${args.optInt("y")})"

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val (w, h) = ctx.channel.screenDimensions()
        val nx = args.optInt("x", 500).coerceIn(0, 1000)
        val ny = args.optInt("y", 500).coerceIn(0, 1000)
        val px = (nx / 1000f * w).toInt().coerceIn(1, w - 1)
        val py = (ny / 1000f * h).toInt().coerceIn(1, h - 1)
        val ok = ctx.channel.tap(px, py)
        if (!ok) return ToolExecutionResult("坐标点击失败：请检查无障碍或 Shizuku 状态。", isError = true)
        delay(650L)
        return ctx.observeScreen(prefix = "已点击坐标 ($nx, $ny)")
    }
}

private object LongPressTool : PhoneTool {
    override val name = "long_press"
    override val label = "长按"
    override val description = "长按控件编号 id，或按 0~1000 坐标 (x, y) 长按。"

    override fun parametersSchema(): JSONObject = JSONObject()
        .put("id", JSONObject().put("type", "integer").put("description", "可选：控件编号"))
        .put("x", JSONObject().put("type", "integer").put("description", "可选：横向坐标 0..1000"))
        .put("y", JSONObject().put("type", "integer").put("description", "可选：纵向坐标 0..1000"))
        .put("duration_ms", JSONObject().put("type", "integer").put("description", "长按毫秒数，默认 700"))

    override fun humanLabel(args: JSONObject): String =
        if (args.has("id")) "长按控件 #${args.optInt("id")}" else "长按屏幕"

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val (w, h) = ctx.channel.screenDimensions()
        val duration = args.optInt("duration_ms", 700).coerceIn(300, 3000)
        val (px, py) = if (args.has("id")) {
            val el = ctx.resolveElement(args.optInt("id", -1))
                ?: return ToolExecutionResult("未找到对应控件，请先 read_screen。", isError = true)
            el.centerX to el.centerY
        } else {
            val nx = args.optInt("x", 500).coerceIn(0, 1000)
            val ny = args.optInt("y", 500).coerceIn(0, 1000)
            (nx / 1000f * w).toInt() to (ny / 1000f * h).toInt()
        }
        val ok = ctx.channel.longPress(px, py, duration)
        if (!ok) return ToolExecutionResult("长按失败：请检查无障碍或 Shizuku 连接。", isError = true)
        delay(600L)
        return ctx.observeScreen(prefix = "已长按 (${duration}ms)")
    }
}

private object SwipeTool : PhoneTool {
    override val name = "swipe"
    override val label = "滑动屏幕"
    override val description = "滑动屏幕。推荐直接传 direction (up/down/left/right)，也可传 0~1000 起止坐标 (x1,y1,x2,y2)。注意：direction='up' 表示手指向上滑（查看下方更多内容）。"

    override fun parametersSchema(): JSONObject = JSONObject()
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
        .put("duration_ms", JSONObject().put("type", "integer"))

    override fun humanLabel(args: JSONObject): String = when (args.optString("direction")) {
        "up" -> "向上滑动"
        "down" -> "向下滑动"
        "left" -> "向左滑动"
        "right" -> "向右滑动"
        else -> "滑动屏幕"
    }

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val (w, h) = ctx.channel.screenDimensions()
        val duration = args.optInt("duration_ms", 350).coerceIn(120, 1500)
        val dir = args.optString("direction", "").lowercase()
        val (x1, y1, x2, y2) = when (dir) {
            "up" -> intArrayOf(w / 2, (h * 0.76f).toInt(), w / 2, (h * 0.24f).toInt())
            "down" -> intArrayOf(w / 2, (h * 0.28f).toInt(), w / 2, (h * 0.78f).toInt())
            "left" -> intArrayOf((w * 0.82f).toInt(), h / 2, (w * 0.18f).toInt(), h / 2)
            "right" -> intArrayOf((w * 0.18f).toInt(), h / 2, (w * 0.82f).toInt(), h / 2)
            else -> intArrayOf(
                (args.optInt("x1", 500).coerceIn(0, 1000) / 1000f * w).toInt(),
                (args.optInt("y1", 700).coerceIn(0, 1000) / 1000f * h).toInt(),
                (args.optInt("x2", 500).coerceIn(0, 1000) / 1000f * w).toInt(),
                (args.optInt("y2", 300).coerceIn(0, 1000) / 1000f * h).toInt(),
            )
        }
        val ok = ctx.channel.swipe(x1, y1, x2, y2, duration)
        if (!ok) return ToolExecutionResult("滑动失败：请检查无障碍或 Shizuku 权限。", isError = true)
        delay(650L)
        return ctx.observeScreen(prefix = "已滑动屏幕${if (dir.isNotEmpty()) " ($dir)" else ""}")
    }
}

private object InputTextTool : PhoneTool {
    override val name = "input_text"
    override val label = "输入文字"
    override val description = "向输入框输入中文或英文文本。如果传了 id 会先点击该输入框再输入。"
    override val requiredParams = listOf("text")

    override fun parametersSchema(): JSONObject = JSONObject()
        .put("text", JSONObject().put("type", "string").put("description", "要输入的文本"))
        .put("id", JSONObject().put("type", "integer").put("description", "可选：目标输入框编号"))
        .put("press_enter", JSONObject().put("type", "boolean").put("description", "输入后是否按回车/搜索键"))

    override fun humanLabel(args: JSONObject): String = "输入「${args.optString("text").take(18)}」"

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val text = args.optString("text")
        val target = if (args.has("id")) ctx.resolveElement(args.optInt("id", -1)) else null
        if (target != null) {
            ctx.channel.tap(target.centerX, target.centerY)
            delay(350L)
        }
        val ok = ctx.channel.inputText(text, target?.bounds)
        if (!ok) {
            return ToolExecutionResult(
                "输入文本失败：未能定位可编辑输入框，请先点击输入框或确认已开启无障碍服务。",
                isError = true,
            )
        }
        if (args.optBoolean("press_enter", false)) {
            delay(200L)
            ctx.channel.pressKey("enter")
        }
        delay(550L)
        return ctx.observeScreen(prefix = "已输入文本「${text.take(40)}」")
    }
}

private object PressKeyTool : PhoneTool {
    override val name = "press_key"
    override val label = "系统按键"
    override val description = "按下系统按键：back（返回）、home（回桌面）、recents（多任务）、enter（回车）。"
    override val requiredParams = listOf("key")

    override fun parametersSchema(): JSONObject = JSONObject().put(
        "key",
        JSONObject()
            .put("type", "string")
            .put("enum", JSONArray(listOf("back", "home", "recents", "enter"))),
    )

    override fun humanLabel(args: JSONObject): String = when (args.optString("key")) {
        "back" -> "按下返回键"
        "home" -> "回到桌面"
        "recents" -> "打开多任务"
        "enter" -> "按下回车"
        else -> "按下按键"
    }

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val key = args.optString("key", "back")
        val ok = ctx.channel.pressKey(key)
        if (!ok) return ToolExecutionResult("按键失败：请检查无障碍或 Shizuku 权限。", isError = true)
        delay(600L)
        return ctx.observeScreen(prefix = "已按下 $key")
    }
}

private object OpenAppTool : PhoneTool {
    override val name = "open_app"
    override val label = "打开应用"
    override val description = "按中文名、英文名或包名直接启动手机上的应用，例如「微信」「设置」「高德地图」「哔哩哔哩」。"
    override val requiredParams = listOf("app_name")

    override fun parametersSchema(): JSONObject = JSONObject().put(
        "app_name",
        JSONObject().put("type", "string").put("description", "应用名称或包名"),
    )

    override fun humanLabel(args: JSONObject): String = "打开 ${args.optString("app_name")}"

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val query = args.optString("app_name").trim()
        if (query.isEmpty()) return ToolExecutionResult("请提供应用名称或包名", isError = true)
        val match = ctx.appDirectory.findLaunchableApp(query)
            ?: return ToolExecutionResult("未找到名为「$query」的已安装应用，可先调用 list_apps 查看列表。", isError = true)
        val launched = ctx.channel.launchPackage(match.first)
        if (!launched) {
            return ToolExecutionResult("启动 ${match.second} (${match.first}) 失败。", isError = true)
        }
        delay(1_100L)
        return ctx.observeScreen(prefix = "已打开 ${match.second} (${match.first})")
    }
}

private object ListAppsTool : PhoneTool {
    override val name = "list_apps"
    override val label = "查找应用"
    override val description = "按关键词搜索手机已安装的可启动应用，返回名称与包名。"

    override fun parametersSchema(): JSONObject = JSONObject().put(
        "query",
        JSONObject().put("type", "string").put("description", "可选关键词"),
    )

    override fun humanLabel(args: JSONObject): String = "搜索已装应用"

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val query = args.optString("query").trim()
        val apps = ctx.appDirectory.queryLaunchableApps(query).take(40)
        return if (apps.isEmpty()) {
            ToolExecutionResult("未找到匹配「$query」的可启动应用。")
        } else {
            val text = apps.joinToString("\n") { (pkg, label) -> "$label ($pkg)" }
            ToolExecutionResult("找到 ${apps.size} 个应用：\n$text")
        }
    }
}

private object WaitTool : PhoneTool {
    override val name = "wait"
    override val label = "等待加载"
    override val description = "等待页面加载 1~5 秒后返回最新屏幕状态。"

    override fun parametersSchema(): JSONObject = JSONObject().put(
        "seconds",
        JSONObject().put("type", "number").put("description", "等待秒数，1~5"),
    )

    override fun humanLabel(args: JSONObject): String = "等待页面加载"

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val sec = args.optDouble("seconds", 1.5).coerceIn(0.5, 5.0)
        delay((sec * 1000).toLong())
        return ctx.observeScreen(prefix = "已等待 ${sec}s")
    }
}
