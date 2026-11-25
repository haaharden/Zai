package com.self.zai.phone.tools

import com.self.zai.phone.ScreenSnapshot
import com.self.zai.phone.UiElement
import com.self.zai.pi.ToolExecutionResult
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun builtInPhoneTools(): List<PhoneTool> = listOf(
    ReadScreenTool,
    ScreenshotTool,
    TapTool,
    TapXyTool,
    LongPressTool,
    SwipeTool,
    InputTextTool,
    ScrollFindTapTool,
    RunActionsTool,
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
        val ok = ctx.channel.tapNode(el.bounds, el.centerX, el.centerY)
        if (!ok) return ToolExecutionResult("点击失败：请检查无障碍或 Shizuku 连接状态。", isError = true)
        ctx.channel.waitForUiStable(minWaitMs = 120L, quietWindowMs = 160L, maxWaitMs = 800L)
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
        ctx.channel.waitForUiStable(minWaitMs = 120L, quietWindowMs = 160L, maxWaitMs = 800L)
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
        ctx.channel.waitForUiStable(minWaitMs = 120L, quietWindowMs = 150L, maxWaitMs = 750L)
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
        val ok = performSwipeArgs(ctx, args)
        if (!ok) return ToolExecutionResult("滑动失败：请检查无障碍或 Shizuku 权限。", isError = true)
        ctx.channel.waitForUiStable(minWaitMs = 140L, quietWindowMs = 160L, maxWaitMs = 750L)
        val dir = args.optString("direction", "").lowercase()
        return ctx.observeScreen(prefix = "已滑动屏幕${if (dir.isNotEmpty()) " ($dir)" else ""}")
    }
}

private object InputTextTool : PhoneTool {
    override val name = "input_text"
    override val label = "输入文字"
    override val description = "向输入框输入中文或英文文本。如果传了 id 会直接聚焦该输入框并输入；press_enter=true 时一并提交。"
    override val requiredParams = listOf("text")

    override fun parametersSchema(): JSONObject = JSONObject()
        .put("text", JSONObject().put("type", "string").put("description", "要输入的文本"))
        .put("id", JSONObject().put("type", "integer").put("description", "可选：目标输入框编号"))
        .put("press_enter", JSONObject().put("type", "boolean").put("description", "输入后是否按回车/搜索键"))

    override fun humanLabel(args: JSONObject): String = "输入「${args.optString("text").take(18)}」"

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val text = args.optString("text")
        val target = if (args.has("id")) ctx.resolveElement(args.optInt("id", -1)) else null
        val ok = performFastInputText(ctx, text, target, args.optBoolean("press_enter", false))
        if (!ok) {
            return ToolExecutionResult(
                "输入文本失败：未能定位可编辑输入框，请先点击输入框或确认已开启无障碍服务。",
                isError = true,
            )
        }
        ctx.channel.waitForUiStable(minWaitMs = 120L, quietWindowMs = 160L, maxWaitMs = 800L)
        return ctx.observeScreen(prefix = "已输入文本「${text.take(40)}」")
    }
}

/**
 * Local fast loop: searches the live accessibility tree for matching text/description, scrolling
 * automatically up to [max_scrolls] times without round-tripping to the cloud LLM on every swipe.
 */
private object ScrollFindTapTool : PhoneTool {
    override val name = "scroll_find_tap"
    override val label = "滚动查找并点击"
    override val description = "在当前列表中自动本地滚动查找包含指定文字或描述的控件，找到后直接点击（可设 tap_match=false 仅滚动到可见）。把多次滑动压缩为 1 步，大幅提速。"
    override val requiredParams = listOf("query")

    override fun parametersSchema(): JSONObject = JSONObject()
        .put("query", JSONObject().put("type", "string").put("description", "要查找的按钮或条目文字关键词"))
        .put(
            "direction",
            JSONObject()
                .put("type", "string")
                .put("enum", JSONArray(listOf("up", "down")))
                .put("description", "滚动方向，默认 up（手指上滑查看下方列表）"),
        )
        .put("max_scrolls", JSONObject().put("type", "integer").put("description", "最大本地滚动次数，默认 5，最大 8"))
        .put("tap_match", JSONObject().put("type", "boolean").put("description", "找到后是否立即点击，默认 true"))

    override fun humanLabel(args: JSONObject): String = "查找并点击「${args.optString("query").take(14)}」"

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return ToolExecutionResult("请提供要查找的文字 query。", isError = true)
        val direction = args.optString("direction", "up").lowercase().takeIf { it == "down" } ?: "up"
        val maxScrolls = args.optInt("max_scrolls", 5).coerceIn(1, 8)
        val tapMatch = args.optBoolean("tap_match", true)

        var snap = ctx.refreshTreeSnapshot() ?: ctx.lastSnapshot
            ?: return ToolExecutionResult("无法读取当前屏幕，请检查无障碍或 Shizuku 连接。", isError = true)
        var prevSignature = snapshotSignature(snap)
        var scrollsDone = 0

        while (true) {
            val match = findBestElementByQuery(snap.elements, query)
            if (match != null) {
                if (!tapMatch) {
                    return ctx.observeScreen(prefix = "已滚动 $scrollsDone 次找到「${match.displayLabel}」")
                }
                val verdict = ctx.checkSubActionSafety(
                    callId = "sft_${UUID.randomUUID().toString().take(8)}",
                    toolName = "tap",
                    args = JSONObject().put("id", match.index).put("query", query),
                    humanLabel = "点击「${match.displayLabel}」",
                    targetElement = match,
                )
                if (verdict != null && verdict.block) {
                    return ToolExecutionResult(verdict.reason, isError = true)
                }
                val tapped = ctx.channel.tapNode(match.bounds, match.centerX, match.centerY)
                if (!tapped) return ToolExecutionResult("找到「${match.displayLabel}」但点击失败。", isError = true)
                ctx.channel.waitForUiStable(minWaitMs = 120L, quietWindowMs = 160L, maxWaitMs = 800L)
                return ctx.observeScreen(prefix = "已滚动 $scrollsDone 次找到并点击「${match.displayLabel}」")
            }

            if (scrollsDone >= maxScrolls) {
                return ctx.observeScreen(prefix = "已连续滚动 $scrollsDone 次，未找到包含「$query」的控件")
            }

            val swiped = performSwipeArgs(ctx, JSONObject().put("direction", direction).put("duration_ms", 280))
            if (!swiped) {
                return ctx.observeScreen(prefix = "第 ${scrollsDone + 1} 次滚动失败，未找到「$query」")
            }
            scrollsDone++
            ctx.channel.waitForUiStable(minWaitMs = 140L, quietWindowMs = 150L, maxWaitMs = 700L)
            val nextSnap = ctx.refreshTreeSnapshot() ?: break
            val nextSig = snapshotSignature(nextSnap)
            snap = nextSnap
            if (nextSig == prevSignature) {
                // Reached end of scrollable list — stop immediately instead of wasting scrolls
                val finalMatch = findBestElementByQuery(snap.elements, query)
                if (finalMatch == null) {
                    return ctx.observeScreen(prefix = "滚动 $scrollsDone 次后页面已到底部，未找到「$query」")
                }
            }
            prevSignature = nextSig
        }

        return ctx.observeScreen(prefix = "已滚动 $scrollsDone 次，未找到「$query」")
    }
}

/**
 * Local deterministic action batcher: runs up to 6 known UI actions locally without cloud round-trips
 * between steps, aborting immediately on unexpected app switch, missing target, or sensitive-action gate.
 */
private object RunActionsTool : PhoneTool {
    override val name = "run_actions"
    override val label = "批量连续操作"
    override val description = "在手机本地连续执行 2~6 个确定的界面动作（如：点搜索框 → 输入文字并回车 → 等待结果出现），大幅减少大模型往返次数。遇到敏感确认、目标缺失或意外切出当前应用时会立即停止并返回当前页面。"
    override val requiredParams = listOf("actions")

    override fun parametersSchema(): JSONObject = JSONObject()
        .put(
            "actions",
            JSONObject()
                .put("type", "array")
                .put("description", "按顺序执行的动作数组（最多 6 步）。每步支持 type: tap(id 或 text)、input_text(text, 可选 id/target_text, press_enter)、swipe(direction)、press_key(key)、wait_for_text(text, timeout_ms)")
                .put("items", JSONObject().put("type", "object")),
        )
        .put(
            "allow_app_switch",
            JSONObject()
                .put("type", "boolean")
                .put("description", "是否允许中途跳转到其他应用，默认 false（遇意外跳应用立即暂停回报）"),
        )

    override fun humanLabel(args: JSONObject): String {
        val count = args.optJSONArray("actions")?.length() ?: 0
        return if (count > 0) "连续执行 $count 个步骤" else "批量连续操作"
    }

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val actions = args.optJSONArray("actions")
        if (actions == null || actions.length() == 0) {
            return ToolExecutionResult("actions 不能为空数组。", isError = true)
        }
        val maxSteps = actions.length().coerceAtMost(MAX_BATCH_ACTIONS)
        val allowAppSwitch = args.optBoolean("allow_app_switch", false)
        val initialPkg = ctx.channel.currentForegroundPackage().ifBlank { ctx.lastSnapshot?.packageName.orEmpty() }
        val executedLabels = mutableListOf<String>()

        for (i in 0 until maxSteps) {
            val stepObj = actions.optJSONObject(i)
                ?: return ctx.observeScreen(prefix = "批处理第 ${i + 1} 步格式无效，已完成 ${executedLabels.size} 步")

            // Refresh tree before steps after the first (and on step 0 when matching by live text rather than old IDs)
            if (i > 0) {
                val livePkg = ctx.channel.currentForegroundPackage()
                if (!allowAppSwitch &&
                    initialPkg.isNotBlank() &&
                    livePkg.isNotBlank() &&
                    livePkg != initialPkg
                ) {
                    return ctx.observeScreen(
                        prefix = "已执行 ${executedLabels.size}/$maxSteps 步（${executedLabels.joinToString(" → ")}），检测到页面跳转到 $livePkg，提前返回供你判断",
                    )
                }
                ctx.refreshTreeSnapshot()
            } else if (ctx.lastSnapshot == null || !stepObj.has("id")) {
                ctx.refreshTreeSnapshot()
            }

            val type = stepObj.optString("type").trim().lowercase()
            when (type) {
                "tap" -> {
                    val el = resolveSubActionElement(ctx, stepObj, textKey = "text")
                        ?: return ctx.observeScreen(
                            prefix = "已执行 ${executedLabels.size}/$maxSteps 步，第 ${i + 1} 步未找到点击目标（${stepObj.optString("text").ifBlank { "#${stepObj.optInt("id", -1)}" }}）",
                        )
                    val verdict = ctx.checkSubActionSafety(
                        callId = "batch_${i}_${UUID.randomUUID().toString().take(6)}",
                        toolName = "tap",
                        args = stepObj,
                        humanLabel = "点击「${el.displayLabel}」",
                        targetElement = el,
                    )
                    if (verdict != null && verdict.block) {
                        return ctx.observeScreen(prefix = "批处理第 ${i + 1} 步被安全拦截：${verdict.reason}")
                    }
                    val ok = ctx.channel.tapNode(el.bounds, el.centerX, el.centerY)
                    if (!ok) {
                        return ctx.observeScreen(prefix = "已执行 ${executedLabels.size}/$maxSteps 步，第 ${i + 1} 步点击失败")
                    }
                    executedLabels += "点击「${el.displayLabel.take(10)}」"
                    ctx.channel.waitForUiStable(minWaitMs = 110L, quietWindowMs = 150L, maxWaitMs = 750L)
                }

                "input_text" -> {
                    val text = stepObj.optString("text")
                    val target = resolveSubActionElement(ctx, stepObj, textKey = "target_text", preferEditable = true)
                    val verdict = ctx.checkSubActionSafety(
                        callId = "batch_${i}_${UUID.randomUUID().toString().take(6)}",
                        toolName = "input_text",
                        args = stepObj,
                        humanLabel = "输入「${text.take(14)}」",
                        targetElement = target,
                    )
                    if (verdict != null && verdict.block) {
                        return ctx.observeScreen(prefix = "批处理第 ${i + 1} 步被安全拦截：${verdict.reason}")
                    }
                    val ok = performFastInputText(ctx, text, target, stepObj.optBoolean("press_enter", false))
                    if (!ok) {
                        return ctx.observeScreen(prefix = "已执行 ${executedLabels.size}/$maxSteps 步，第 ${i + 1} 步输入文本失败")
                    }
                    executedLabels += "输入「${text.take(10)}」"
                    ctx.channel.waitForUiStable(minWaitMs = 120L, quietWindowMs = 150L, maxWaitMs = 800L)
                }

                "swipe" -> {
                    val ok = performSwipeArgs(ctx, stepObj)
                    if (!ok) {
                        return ctx.observeScreen(prefix = "已执行 ${executedLabels.size}/$maxSteps 步，第 ${i + 1} 步滑动失败")
                    }
                    executedLabels += "滑动(${stepObj.optString("direction", "custom")})"
                    ctx.channel.waitForUiStable(minWaitMs = 130L, quietWindowMs = 150L, maxWaitMs = 700L)
                }

                "press_key" -> {
                    val key = stepObj.optString("key", "back")
                    val ok = ctx.channel.pressKey(key)
                    if (!ok) {
                        return ctx.observeScreen(prefix = "已执行 ${executedLabels.size}/$maxSteps 步，第 ${i + 1} 步按键失败")
                    }
                    executedLabels += "按键($key)"
                    ctx.channel.waitForUiStable(minWaitMs = 110L, quietWindowMs = 150L, maxWaitMs = 750L)
                }

                "wait_for_text" -> {
                    val needle = stepObj.optString("text").trim()
                    val timeoutMs = stepObj.optLong("timeout_ms", 2_500L).coerceIn(300L, 6_000L)
                    if (needle.isNotEmpty()) {
                        val found = waitForTextInTree(ctx, needle, timeoutMs)
                        if (!found) {
                            return ctx.observeScreen(
                                prefix = "已执行 ${executedLabels.size}/$maxSteps 步，等待「$needle」超时 (${timeoutMs}ms)",
                            )
                        }
                        executedLabels += "等到「${needle.take(10)}」"
                    }
                }

                else -> {
                    return ctx.observeScreen(
                        prefix = "已执行 ${executedLabels.size}/$maxSteps 步，第 ${i + 1} 步不支持的动作类型：$type",
                    )
                }
            }
        }

        return ctx.observeScreen(
            prefix = "已连续执行 ${executedLabels.size} 步：${executedLabels.joinToString(" → ")}",
        )
    }

    private const val MAX_BATCH_ACTIONS = 6
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
        ctx.channel.waitForUiStable(minWaitMs = 120L, quietWindowMs = 160L, maxWaitMs = 800L)
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
        val prevPkg = ctx.channel.currentForegroundPackage()
        val launched = ctx.channel.launchPackage(match.first)
        if (!launched) {
            return ToolExecutionResult("启动 ${match.second} (${match.first}) 失败。", isError = true)
        }
        ctx.channel.waitForUiStable(
            minWaitMs = 220L,
            quietWindowMs = 180L,
            maxWaitMs = 1_800L,
            waitForPackageChangeFrom = prevPkg.takeIf { it.isNotBlank() && it != match.first },
        )
        return ctx.observeScreen(prefix = "已打开 ${match.second} (${match.first})")
    }
}

private object ListAppsTool : PhoneTool {
    override val name = "list_apps"
    override val label = "查找应用"
    override val description = "按关键词搜索手机已安装的可启动应用，返回名称与包名。"
    override val interactsWithScreen = false

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

private suspend fun performSwipeArgs(ctx: PhoneToolContext, args: JSONObject): Boolean {
    val (w, h) = ctx.channel.screenDimensions()
    val duration = args.optInt("duration_ms", 300).coerceIn(120, 1500)
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
    return ctx.channel.swipe(x1, y1, x2, y2, duration)
}

/**
 * Fast text entry path: tries direct Accessibility `ACTION_FOCUS + ACTION_SET_TEXT` first (zero 350ms
 * click-focus delay), falling back to tap-then-input only for custom WebView/canvas inputs.
 */
private suspend fun performFastInputText(
    ctx: PhoneToolContext,
    text: String,
    target: UiElement?,
    pressEnter: Boolean,
): Boolean {
    var ok = ctx.channel.inputText(text, target?.bounds)
    if (!ok && target != null) {
        ctx.channel.tapNode(target.bounds, target.centerX, target.centerY)
        delay(200L)
        ok = ctx.channel.inputText(text, target.bounds)
    }
    if (!ok) return false
    if (pressEnter) {
        delay(60L)
        ctx.channel.pressKey("enter")
    }
    return true
}

private fun resolveSubActionElement(
    ctx: PhoneToolContext,
    stepObj: JSONObject,
    textKey: String,
    preferEditable: Boolean = false,
): UiElement? {
    if (stepObj.has("id")) {
        return ctx.resolveElement(stepObj.optInt("id", -1))
    }
    val query = stepObj.optString(textKey).trim()
    val elements = ctx.lastSnapshot?.elements.orEmpty()
    if (query.isNotEmpty()) {
        return findBestElementByQuery(elements, query, preferEditable = preferEditable)
    }
    if (preferEditable) {
        return elements.firstOrNull { it.editable }
    }
    return null
}

private fun findBestElementByQuery(
    elements: List<UiElement>,
    query: String,
    preferEditable: Boolean = false,
): UiElement? {
    val needle = query.trim()
    if (needle.isEmpty()) return null
    val matches = elements.filter { el ->
        el.text.contains(needle, ignoreCase = true) ||
            el.contentDesc.contains(needle, ignoreCase = true)
    }
    if (matches.isEmpty()) return null
    return matches.minByOrNull { el ->
        val exact = el.text.equals(needle, ignoreCase = true) || el.contentDesc.equals(needle, ignoreCase = true)
        val editRank = if (preferEditable && el.editable) 0 else if (preferEditable) 1 else 0
        val exactRank = if (exact) 0 else 1
        val clickableRank = if (el.clickable || el.editable) 0 else 1
        editRank * 100 + exactRank * 10 + clickableRank
    }
}

private fun snapshotSignature(snapshot: ScreenSnapshot): String =
    snapshot.packageName + "|" + snapshot.elements.take(40).joinToString(";") {
        "${it.text.take(20)}:${it.contentDesc.take(16)}:${it.bounds.top}"
    }

private suspend fun waitForTextInTree(
    ctx: PhoneToolContext,
    needle: String,
    timeoutMs: Long,
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        val snap = ctx.refreshTreeSnapshot() ?: ctx.lastSnapshot
        if (snap != null && findBestElementByQuery(snap.elements, needle) != null) {
            return true
        }
        if (System.currentTimeMillis() >= deadline) return false
        delay(120L)
    }
}
