package cn.dumate.a6studio.catalog

import cn.dumate.a6studio.model.Block
import cn.dumate.a6studio.model.ParamValue

/**
 * The block catalogue: every draggable statement/container plus the AutoJs6 code it emits.
 * Only APIs confirmed in AutoJs6 docs / MCP tools are included; anything needing a plugin
 * is deliberately absent so generated scripts never reference a missing API.
 */
object Catalog {

    val CATS: List<Category> = listOf(
        Category("flow", "流程与控制", 0xFF6B7CFF),
        Category("app", "应用与设备", 0xFF2FB1A8),
        Category("ui", "UI 无障碍", 0xFFE0733C),
        Category("gesture", "手势与按键", 0xFFC05B8F),
        Category("vision", "图像 / OCR", 0xFF8A63D2),
        Category("data", "数据 / 网络 / 文件", 0xFF3D8BD4),
        Category("debug", "调试与诊断", 0xFFD4A017),
        Category("jd", "京东领券中心（实战）", 0xFFD94F4F),
        Category("module", "我的模块", 0xFF64748B),
        Category("raw", "原生代码", 0xFF7A8699)
    )

    val BY: List<SelectOption> = listOf(
        SelectOption("text", "文本"),
        SelectOption("desc", "描述"),
        SelectOption("id", "id"),
        SelectOption("textContains", "文本包含"),
        SelectOption("textMatches", "文本正则"),
        SelectOption("descContains", "描述包含"),
        SelectOption("descMatches", "描述正则"),
        SelectOption("idMatches", "id 正则"),
        SelectOption("className", "类名"),
        SelectOption("classNameMatches", "类名正则")
    )

    // ---- param helpers -------------------------------------------------------
    private fun text(key: String, label: String, default: String = "") =
        ParamDef(key, label, ParamKind.TEXT, ParamValue.Text(default))

    private fun expr(key: String, label: String, default: String = "") =
        ParamDef(key, label, ParamKind.EXPR, ParamValue.Text(default))

    private fun num(key: String, label: String, default: Double) =
        ParamDef(key, label, ParamKind.NUMBER, ParamValue.Num(default))

    private fun sel(key: String, label: String, default: String, options: List<SelectOption>) =
        ParamDef(key, label, ParamKind.SELECT, ParamValue.Text(default), options)

    private fun code(key: String, label: String, default: String = "") =
        ParamDef(key, label, ParamKind.CODE, ParamValue.Text(default))

    private fun chk(key: String, label: String, default: Boolean) =
        ParamDef(key, label, ParamKind.CHECKBOX, ParamValue.Bool(default))

    private fun byParam() = sel("by", "定位方式", "text", BY)

    // ---- registry ------------------------------------------------------------
    private val defs = mutableListOf<BlockDef>()
    private val byType = mutableMapOf<String, BlockDef>()

    private fun def(
        type: String,
        cat: String,
        name: String,
        params: List<ParamDef> = emptyList(),
        slots: List<SlotDef> = emptyList(),
        gen: (Block, GenContext) -> String
    ) {
        val d = BlockDef(type, cat, name, params, slots, gen)
        defs += d
        byType[type] = d
    }

    init {
        defFlow()
        defApp()
        defUi()
        defGesture()
        defVision()
        defData()
        defDebug()
        defJd()
        defRaw()
        defModule()
    }

    // ===================== 流程与控制 =====================
    private fun defFlow() {
        def("comment", "flow", "注释", listOf(text("text", "内容", "说明"))) { b, c ->
            "// " + c.str(b, "text").replace("\n", " ")
        }
        def("autoWait", "flow", "等待无障碍服务就绪") { _, _ -> "auto.waitFor();" }
        def("consoleShow", "flow", "显示控制台浮窗") { _, _ -> "console.show();" }
        def("log", "flow", "输出日志", listOf(text("text", "文本", "hello AutoJs6"))) { b, c ->
            "log(" + c.quote(c.str(b, "text")) + ");"
        }
        def("logExpr", "flow", "输出表达式", listOf(expr("expr", "表达式", "1 + 1"))) { b, c ->
            val e = c.str(b, "expr")
            "log(" + (if (e.isEmpty()) "''" else e) + ");"
        }
        def("toast", "flow", "弹出 Toast", listOf(text("text", "文本", "开始执行"))) { b, c ->
            "toast(" + c.quote(c.str(b, "text")) + ");"
        }
        def("sleep", "flow", "等待 {ms} 毫秒", listOf(num("ms", "毫秒", 1000.0))) { b, c ->
            "sleep(" + c.num(b, "ms").toLong() + ");"
        }
        def(
            "varSet", "flow", "定义变量 {name} = {expr}",
            listOf(text("name", "变量名", "value"), expr("expr", "值", "''"))
        ) { b, c ->
            val name = c.str(b, "name").ifEmpty { "v" }
            val e = c.str(b, "expr").ifEmpty { "null" }
            "let $name = $e;"
        }
        def(
            "varAssign", "flow", "赋值 {name} = {expr}",
            listOf(text("name", "变量名", "value"), expr("expr", "值", "0"))
        ) { b, c ->
            val name = c.str(b, "name").ifEmpty { "v" }
            val e = c.str(b, "expr").ifEmpty { "null" }
            "$name = $e;"
        }
        def(
            "if", "flow", "如果 {cond}", listOf(expr("cond", "条件", "true")),
            listOf(SlotDef("body", "那么"), SlotDef("else", "否则"))
        ) { b, c ->
            var s = "if (" + c.str(b, "cond").ifEmpty { "false" } + ") {\n" + c.body(b, "body", 1) + "\n}"
            if (c.has(b, "else")) s += " else {\n" + c.body(b, "else", 1) + "\n}"
            s
        }
        def(
            "forRange", "flow", "循环 {i} 从 0 到 {n}",
            listOf(text("i", "循环变量", "i"), num("n", "次数", 3.0)),
            listOf(SlotDef("body", "循环体"))
        ) { b, c ->
            val i = c.str(b, "i").ifEmpty { "i" }
            "for (let $i = 0; $i < ${c.num(b, "n").toLong()}; $i++) {\n" + c.body(b, "body", 1) + "\n}"
        }
        def(
            "forOf", "flow", "遍历 {item} ∈ {list}",
            listOf(text("item", "元素变量", "item"), expr("list", "列表", "[]")),
            listOf(SlotDef("body", "循环体"))
        ) { b, c ->
            val item = c.str(b, "item").ifEmpty { "item" }
            val list = c.str(b, "list").ifEmpty { "[]" }
            // `const` in a for-of head is not parseable by every Rhino build AutoJs6 ships
            // (1.9.x rejects it), while `let` works everywhere — so emit `let`.
            "for (let $item of ($list)) {\n" + c.body(b, "body", 1) + "\n}"
        }
        def(
            "whileGuard", "flow", "循环直到（最多 {max} 轮）",
            listOf(expr("cond", "条件", "true"), num("max", "最大轮数", 20.0)),
            listOf(SlotDef("body", "循环体"))
        ) { b, c ->
            val g = c.newVar("guard")
            val cond = c.str(b, "cond").ifEmpty { "false" }
            "let $g = 0;\nwhile ($cond) {\n  if (++$g > ${c.num(b, "max").toLong().coerceAtLeast(1)}) " +
                "{ log(\"循环保护触发: ${escForString(cond)}\"); break; }\n" +
                c.body(b, "body", 1) + "\n}"
        }
        def(
            "tryCatch", "flow", "尝试 / 捕获 {err}",
            listOf(text("err", "异常变量", "e"), chk("rethrow", "捕获后重新抛出", false)),
            listOf(SlotDef("body", "尝试"), SlotDef("catch", "捕获"))
        ) { b, c ->
            val err = c.str(b, "err").ifEmpty { "e" }
            var s = "try {\n" + c.body(b, "body", 1) + "\n} catch ($err) {\n" + c.body(b, "catch", 1)
            if (b.params["rethrow"]?.asBool() == true) s += "\n  throw $err;"
            s += "\n}"
            s
        }
        def(
            "funcDef", "flow", "定义函数 {name}({args})",
            listOf(
                text("name", "函数名", "doTask"),
                text("args", "参数列表", "task"),
                chk("isAsync", "生成 async 函数", false)
            ),
            listOf(SlotDef("body", "函数体"))
        ) { b, c ->
            val name = c.str(b, "name").ifEmpty { "fn" }
            "function $name(${c.str(b, "args")}) {\n" + c.body(b, "body", 1) + "\n}"
        }
        def(
            "funcCall", "flow", "调用函数 {name}({args})",
            listOf(text("name", "函数名", "doTask"), expr("args", "实参", "'a'"))
        ) { b, c ->
            val name = c.str(b, "name").ifEmpty { "fn" }
            "$name(${c.str(b, "args")});"
        }
        def("exitScript", "flow", "结束脚本") { _, _ -> "exit();" }
        def(
            "retry", "flow", "重试 {times} 次（间隔 {delay}ms）",
            listOf(num("times", "次数", 3.0), num("delay", "间隔", 800.0)),
            listOf(SlotDef("body", "尝试"), SlotDef("catch", "失败处理"))
        ) { b, c ->
            val n = c.num(b, "times").toLong().coerceAtLeast(1)
            val d = c.num(b, "delay").toLong()
            "for (let attempt = 1; attempt <= $n; attempt++) {\n  try {\n" + c.body(b, "body", 2) +
                "\n    break;\n  } catch (e) {\n    log(\"第 \" + attempt + \" 次尝试失败: \" + e);\n" +
                c.body(b, "catch", 2) + "\n    if (attempt === $n) throw e;\n    sleep($d);\n  }\n}"
        }
    }

    // ===================== 应用与设备 =====================
    private fun defApp() {
        def(
            "launchPackage", "app", "启动应用 {pkg}",
            listOf(text("pkg", "包名", "com.jingdong.app.mall"), num("wait", "等待毫秒", 1500.0))
        ) { b, c ->
            "app.launchPackage(${c.quote(c.str(b, "pkg"))});\nsleep(${c.num(b, "wait").toLong()});"
        }
        def("openUrl", "app", "打开链接 / 深链", listOf(text("url", "URL", "https://pro.m.jd.com/"))) { b, c ->
            "app.openUrl(${c.quote(c.str(b, "url"))});"
        }
        def("killApp", "app", "结束应用 {pkg}", listOf(text("pkg", "包名", "com.jingdong.app.mall"))) { b, c ->
            "app.kill(${c.quote(c.str(b, "pkg"))});\nsleep(800);"
        }
        def("currentPkgVar", "app", "记录当前包名到 {name}", listOf(text("name", "变量名", "pkg"))) { b, c ->
            val name = c.str(b, "name").ifEmpty { "pkg" }
            "let $name = currentPackage();\nlog(\"当前包名: \" + $name);"
        }
        def(
            "waitForPackage", "app", "等待包名 {pkg}（{timeout}ms）",
            listOf(text("pkg", "包名", "com.jingdong.app.mall"), num("timeout", "超时", 8000.0))
        ) { b, c ->
            "waitForPackage(${c.quote(c.str(b, "pkg"))}, ${c.num(b, "timeout").toLong()});"
        }
        def("keepScreenOn", "app", "保持屏幕常亮 {ms} 毫秒", listOf(num("ms", "毫秒", 1_800_000.0))) { b, c ->
            "device.keepScreenOn(${c.num(b, "ms").toLong()});"
        }
        def("vibrate", "app", "震动 {ms} 毫秒", listOf(num("ms", "毫秒", 300.0))) { b, c ->
            "device.vibrate(${c.num(b, "ms").toLong()});"
        }
        def("clipSet", "app", "写入剪贴板", listOf(text("text", "文本", ""))) { b, c ->
            "setClip(${c.quote(c.str(b, "text"))});"
        }
        def("clipGet", "app", "读取剪贴板到 {name}", listOf(text("name", "变量名", "clip"))) { b, c ->
            "let ${c.str(b, "name").ifEmpty { "clip" }} = getClip();"
        }
        def(
            "shellCmd", "app", "执行 Shell：{var} = {cmd}",
            listOf(text("var", "结果变量", "r"), code("cmd", "命令", "echo hi"))
        ) { b, c ->
            val v = c.str(b, "var").ifEmpty { "r" }
            "let $v = shell(${c.quote(c.str(b, "cmd"))}, true);\nlog(String($v));"
        }
    }

    // ===================== UI 无障碍 =====================
    private fun defUi() {
        def(
            "waitText", "ui", "等待控件出现（{timeout}ms）",
            listOf(byParam(), text("value", "值", "更多任务"), num("timeout", "超时", 8000.0))
        ) { b, c ->
            val n = c.newVar("wait")
            val t = c.num(b, "timeout").toLong().let { if (it <= 0) 3000 else it }
            val v = c.str(b, "value")
            "let $n = ${c.selector(b)}.findOne($t);\n" +
                "if ($n) { log(\"控件已出现: \" + ${c.quote(v)}); } else { log(\"等待控件超时: \" + ${c.quote(v)}); }"
        }
        def(
            "clickNode", "ui", "点击控件 {value}",
            listOf(byParam(), text("value", "值", "立即领奖"), code("expr", "或表达式", ""), num("timeout", "查找超时", 3000.0))
        ) { b, c ->
            val n = c.newVar("node")
            val t = c.num(b, "timeout").toLong()
            val v = c.str(b, "value")
            "let $n = ${c.selector(b)}.findOne($t);\nif ($n) {\n  $n.click();\n  log(\"点击: \" + ${c.quote(v)});\n}" +
                " else {\n  log(\"未找到控件: \" + ${c.quote(v)});\n}"
        }
        def(
            "longClickNode", "ui", "长按控件 {value}",
            listOf(byParam(), text("value", "值", "删除"), code("expr", "或表达式", ""), num("timeout", "超时", 3000.0))
        ) { b, c ->
            val n = c.newVar("node")
            val v = c.str(b, "value")
            "let $n = ${c.selector(b)}.findOne(${c.num(b, "timeout").toLong()});\n" +
                "if ($n) { $n.longClick(); } else { log(\"未找到控件: \" + ${c.quote(v)}); }"
        }
        def(
            "setTextNode", "ui", "向输入框 {value} 输入 {text}",
            listOf(byParam(), text("value", "值", "搜索"), code("expr", "或表达式", ""), text("text", "输入内容", "AutoJs6"))
        ) { b, c ->
            val n = c.newVar("node")
            val v = c.str(b, "value")
            "let $n = ${c.selector(b)}.findOne(3000);\nif ($n) { $n.setText(${c.quote(c.str(b, "text"))}); }" +
                " else { log(\"未找到输入框: \" + ${c.quote(v)}); }"
        }
        def(
            "nodeTextVar", "ui", "读取控件文本到 {var}",
            listOf(text("var", "变量名", "t"), byParam(), text("value", "值", "更多任务"), code("expr", "或表达式", ""))
        ) { b, c ->
            val v = c.str(b, "var").ifEmpty { "t" }
            val n = c.newVar("node")
            "let $n = ${c.selector(b)}.findOne(2000);\nlet $v = $n ? $n.text() : null;\nlog(\"$v = \" + $v);"
        }
        def(
            "existsVar", "ui", "{var} = 控件是否存在",
            listOf(text("var", "变量名", "ok"), byParam(), text("value", "值", "已完成"), code("expr", "或表达式", ""))
        ) { b, c ->
            val v = c.str(b, "var").ifEmpty { "ok" }
            "let $v = ${c.selector(b)}.exists();\nlog(\"存在性: \" + $v);"
        }
        def(
            "scrollNode", "ui", "滚动控件（{dir}）",
            listOf(
                sel("dir", "方向", "forward", listOf(SelectOption("forward", "向前/下"), SelectOption("backward", "向后/上"))),
                byParam(), text("value", "值", ""), code("expr", "或表达式", ""), num("times", "次数", 1.0)
            )
        ) { b, c ->
            val times = c.num(b, "times").toLong().coerceAtLeast(1)
            val call = if (c.str(b, "dir") == "backward") "scrollBackward" else "scrollForward"
            (1..times).joinToString("") { "${c.selector(b)}.findOne(2000).$call();\n" }.removeSuffix("\n")
        }
        def("pressBack", "ui", "按返回键") { _, _ -> "back();" }
        def("pressHome", "ui", "回到桌面") { _, _ -> "home();" }
        def("coordClick", "ui", "点击坐标 ({x}, {y})", listOf(num("x", "x", 540.0), num("y", "y", 1200.0))) { b, c ->
            "click(${c.num(b, "x").toLong()}, ${c.num(b, "y").toLong()});"
        }
    }

    // ===================== 手势与按键 =====================
    private fun defGesture() {
        def(
            "swipe", "gesture", "滑动 ({x1},{y1}) → ({x2},{y2})",
            listOf(
                num("x1", "x1", 540.0), num("y1", "y1", 1600.0),
                num("x2", "x2", 540.0), num("y2", "y2", 600.0), num("dur", "时长", 400.0)
            )
        ) { b, c ->
            val coords = listOf("x1", "y1", "x2", "y2").joinToString(", ") { c.num(b, it).toLong().toString() }
            "swipe($coords, ${c.num(b, "dur").toLong().let { if (it <= 0) 300 else it }});"
        }
        def("swipeUpList", "gesture", "列表上滑一屏") { _, _ ->
            "swipe(device.width / 2, device.height * 0.78, device.width / 2, device.height * 0.28, 420);"
        }
        def("edgeBackGesture", "gesture", "左边缘右滑（切回上个应用）") { _, _ ->
            "gesture(2, device.height * 0.5, device.width * 0.65, device.height * 0.5, 320);"
        }
        def(
            "pressKey", "gesture", "按键 {key}",
            listOf(
                sel(
                    "key", "按键", "back", listOf(
                        SelectOption("back", "返回"), SelectOption("home", "Home"),
                        SelectOption("recents", "任务键"), SelectOption("menu", "菜单")
                    )
                )
            )
        ) { b, c ->
            "press(${c.quote(c.str(b, "key").ifEmpty { "back" })});"
        }
    }

    // ===================== 图像 / OCR =====================
    private fun defVision() {
        def("requestCapture", "vision", "申请截屏权限") { _, _ ->
            "if (!images.requestScreenCapture()) {\n  toast(\"截屏权限被拒绝\");\n  exit();\n}"
        }
        def(
            "findImage", "vision", "找图 {path} → {var}（阈值 {th}）",
            listOf(text("var", "结果变量", "pt"), text("path", "模板路径", "/sdcard/模板.png"), num("th", "阈值", 0.85))
        ) { b, c ->
            val v = c.str(b, "var").ifEmpty { "pt" }
            val th = c.num(b, "th").let { if (it <= 0) 0.8 else it }
            "let $v = images.findImage(images.captureScreen(), images.read(${c.quote(c.str(b, "path"))}), " +
                "{ threshold: $th });\nif ($v) { log(\"找到图片: \" + $v.x + \",\" + $v.y); } else { log(\"未找到图片\"); }"
        }
        def("ocrText", "vision", "OCR 识别屏幕 → {var}", listOf(text("var", "结果变量", "txt"))) { b, c ->
            val v = c.str(b, "var").ifEmpty { "txt" }
            "let $v = ocr.recognizeText(images.captureScreen());\nlog(\"OCR: \" + $v);"
        }
        def("saveScreen", "vision", "截图保存到 {path}", listOf(text("path", "路径", "/sdcard/autojs6/shot.png"))) { b, c ->
            val p = c.str(b, "path")
            "files.ensureDir(${c.quote(dirname(p))});\nimages.save(images.captureScreen(), ${c.quote(p)}, \"png\", 100);\nlog(\"截图已保存\");"
        }
    }

    // ===================== 数据 / 网络 / 文件 =====================
    private fun defData() {
        def(
            "filesWrite", "data", "写文件 {path}",
            listOf(text("path", "路径", "/sdcard/autojs6/log.txt"), expr("content", "内容", "\"hello\""), chk("append", "追加模式", true))
        ) { b, c ->
            val p = c.str(b, "path")
            val fn = if (b.params["append"]?.asBool() != false) "files.append(" else "files.write("
            "files.ensureDir(${c.quote(dirname(p))});\n$fn${c.quote(p)}, ${c.str(b, "content").ifEmpty { "\"\"" }});"
        }
        def(
            "filesRead", "data", "读文件 {path} → {var}",
            listOf(text("var", "变量名", "txt"), text("path", "路径", "/sdcard/autojs6/log.txt"))
        ) { b, c ->
            val v = c.str(b, "var").ifEmpty { "txt" }
            val p = c.quote(c.str(b, "path"))
            "let $v = files.exists($p) ? files.read($p) : null;"
        }
        def(
            "filesExistsVar", "data", "{var} = 文件是否存在",
            listOf(text("var", "变量名", "has"), text("path", "路径", "/sdcard/autojs6/log.txt"))
        ) { b, c ->
            "let ${c.str(b, "var").ifEmpty { "has" }} = files.exists(${c.quote(c.str(b, "path"))});"
        }
        def(
            "storagePut", "data", "本地存储写入 {key}",
            listOf(text("key", "键", "count"), expr("value", "值", "0"))
        ) { b, c ->
            "storages.create(\"autojs6-studio\").put(${c.quote(c.str(b, "key"))}, ${c.str(b, "value").ifEmpty { "null" }});"
        }
        def(
            "storageGet", "data", "本地存储读取 {key} → {var}",
            listOf(text("key", "键", "count"), text("var", "变量名", "count"))
        ) { b, c ->
            "let ${c.str(b, "var").ifEmpty { "v" }} = storages.create(\"autojs6-studio\").get(${c.quote(c.str(b, "key"))}, null);"
        }
        def(
            "httpGet", "data", "GET {url} → {var}",
            listOf(text("var", "结果变量", "res"), text("url", "URL", "https://httpbin.org/get"))
        ) { b, c ->
            val v = c.str(b, "var").ifEmpty { "res" }
            "let $v = http.get(${c.quote(c.str(b, "url"))});\nlog(\"HTTP \" + $v.statusCode + \" \" + $v.body.string());"
        }
        def(
            "httpPostJson", "data", "POST JSON {url} → {var}",
            listOf(text("var", "结果变量", "res"), text("url", "URL", "https://httpbin.org/post"), expr("payload", "请求体", "{}"))
        ) { b, c ->
            val v = c.str(b, "var").ifEmpty { "res" }
            "let $v = http.postJson(${c.quote(c.str(b, "url"))}, ${c.str(b, "payload").ifEmpty { "{}" }});\nlog(\"HTTP \" + $v.statusCode);"
        }
        def("threadStart", "data", "子线程执行", slots = listOf(SlotDef("body", "线程体"))) { b, c ->
            "threads.start(function () {\n" + c.body(b, "body", 1) + "\n});"
        }
    }

    // ===================== 调试与诊断 =====================
    private fun defDebug() {
        def(
            "assertBlock", "debug", "断言 {cond}",
            listOf(expr("cond", "条件", "true"), text("msg", "失败信息", "断言失败"))
        ) { b, c ->
            "if (!(${c.str(b, "cond").ifEmpty { "true" }})) {\n  throw new Error(${c.quote("断言失败: " + c.str(b, "msg"))});\n}"
        }
        def("timerStart", "debug", "开始计时 → {var}", listOf(text("var", "变量名", "t0"))) { b, c ->
            "let ${c.str(b, "var").ifEmpty { "t0" }} = Date.now();"
        }
        def(
            "timerLap", "debug", "输出耗时（{label}）",
            listOf(text("label", "标签", "步骤"), text("var", "计时变量", "t0"))
        ) { b, c ->
            "log(${c.quote(c.str(b, "label") + " 耗时 ")} + (Date.now() - ${c.str(b, "var").ifEmpty { "t0" }}) + \"ms\");"
        }
        def("dumpUi", "debug", "输出当前界面控件摘要") { _, _ ->
            "let _nodes = descMatches(\".*\").find();\nlog(\"控件数: \" + _nodes.length);\n" +
                "_nodes.forEach(function (n) {\n  log([n.text(), n.desc(), n.id(), JSON.stringify(n.bounds())].join(\" | \"));\n});"
        }
    }

    // ===================== 京东领券中心（实战模板） =====================
    private fun defJd() {
        def("jdEnter", "jd", "① 进入京东领券中心（深链）") { _, _ ->
            listOf(
                "// 固定深链进入；已在领券中心时复用当前页面（见分享会话第 4 段）",
                "app.launchPackage(\"com.jingdong.app.mall\");",
                "sleep(1500);",
                "if (currentPackage() !== \"com.jingdong.app.mall\") {",
                "  toast(\"请先安装并登录京东\");",
                "  exit();",
                "}",
                "app.openUrl(\"https://pro.m.jd.com/mall/active/VAjs3vpayA513UwxL5XC4eGBXqY/index.html\");",
                "sleep(2500);",
                "log(\"已请求进入领券中心\");"
            ).joinToString("\n")
        }
        def("jdSign", "jd", "② 签到（天天领豆 → 更多任务）") { _, _ ->
            listOf(
                "// 签到标志物：按钮从「天天领豆/立即领奖」变为「更多任务」",
                "let signNode = text(\"天天领豆\").findOne(8000) || text(\"立即领奖\").findOne(1000);",
                "if (signNode) {",
                "  signNode.click();",
                "  sleep(1500);",
                "  log(\"已点击签到\");",
                "} else {",
                "  log(\"未发现签到按钮，可能已签到\");",
                "}",
                "let moreMark = desc(\"更多任务\").findOne(2000) || text(\"更多任务\").findOne(2000);",
                "if (moreMark) { log(\"签到标志物已出现\"); } else { log(\"警告：签到标志物未出现\"); }"
            ).joinToString("\n")
        }
        def("jdOpenPanel", "jd", "③ 打开任务浮层（双通道定位）") { _, _ ->
            listOf(
                "// id 不变但文案会变：先 desc，再 id，最后 text；坐标不缓存",
                "let entry = desc(\"更多任务\").findOne(3000) || id(\"coupon-left-entry\").findOne(1500) || text(\"更多任务\").findOne(1500);",
                "if (!entry) {",
                "  toast(\"未找到任务入口\");",
                "  log(\"入口定位失败\");",
                "  exit();",
                "}",
                "entry.click();",
                "sleep(1800);",
                "log(\"任务浮层已打开\");"
            ).joinToString("\n")
        }
        def(
            "jdTaskLoop", "jd", "④ 遍历京豆任务（每页最多 {max} 个）",
            listOf(text("taskVar", "任务变量", "task"), num("max", "单轮上限", 8.0)),
            listOf(SlotDef("body", "单个任务"))
        ) { b, c ->
            val tv = c.str(b, "taskVar").ifEmpty { "task" }
            val max = c.num(b, "max").toLong().let { if (it <= 0) 8 else it }
            listOf(
                "// 副标题含「得\\d+京豆」的任务才算目标；抽奖/券/红包跳过",
                "let _roundTasks = 0;",
                "for (let round = 0; round < 3; round++) {",
                "  let rows = descMatches(\".*得\\\\d+京豆.*\").find();",
                "  if (rows.length === 0) { break; }",
                "  for (let ti = 0; ti < rows.length && _roundTasks < $max; ti++) {",
                "    let $tv = rows[ti];",
                "    _roundTasks++;",
                c.body(b, "body", 2),
                "    sleep(600);",
                "  }",
                "}",
                "log(\"本轮处理任务: \" + _roundTasks);"
            ).joinToString("\n")
        }
        def(
            "jdDoTask", "jd", "⑤ 执行任务（点去完成 → 等倒计时 → 返回）",
            listOf(text("taskVar", "任务变量", "task"), num("stay", "无倒计时停留毫秒", 7000.0))
        ) { b, c ->
            val tv = c.str(b, "taskVar").ifEmpty { "task" }
            val stay = c.num(b, "stay").toLong().let { if (it <= 0) 7000 else it }
            listOf(
                "if ($tv) {",
                "  $tv.click();",
                "  sleep(1500);",
                "  // 落地页有倒计时就等「已完成/点击立即返回」，没有就计时后 back",
                "  let done = text(\"已完成\").exists() || text(\"点击立即返回\").exists();",
                "  if (done) { log(\"任务完成标记出现\"); } else { sleep($stay); }",
                "  back();",
                "  sleep(1200);",
                "}"
            ).joinToString("\n")
        }
        def("jdCrossApp", "jd", "⑥ 跨 app 任务：跳转后立即切回") { _, _ ->
            listOf(
                "// 跳到京东金融后不要在其中等待：手势条右滑切回京东",
                "sleep(800);",
                "gesture(2, device.height * 0.5, device.width * 0.65, device.height * 0.5, 320);",
                "sleep(1500);",
                "log(\"已切回京东\");"
            ).joinToString("\n")
        }
        def("jdClosePopup", "jd", "⑦ 关遮挡弹窗并重读（×3）") { _, _ ->
            listOf(
                "// 关键：完成任务会弹无文字空弹窗，必须先关闭再重读，才不会被判成 0 任务",
                "for (let ci = 0; ci < 3; ci++) {",
                "  let closeNode = desc(\"关闭\").findOne(700) || text(\"关闭\").findOne(300) || id(\"close\").findOne(300);",
                "  if (closeNode) { closeNode.click(); sleep(500); } else { break; }",
                "}",
                "sleep(400);",
                "log(\"重读任务数: \" + descMatches(\".*得\\\\d+京豆.*\").find().length);"
            ).joinToString("\n")
        }
        def(
            "jdFinishGuard", "jd", "⑧ 收工判定（下滑扫描，最多 {rounds} 轮）",
            listOf(num("rounds", "最大轮数", 12.0), num("zero", "连续空屏阈值", 3.0))
        ) { b, c ->
            val rounds = c.num(b, "rounds").toLong().let { if (it <= 0) 12 else it }
            val zero = c.num(b, "zero").toLong().let { if (it <= 0) 3 else it }
            listOf(
                "// 浮层会动态刷新：首屏 16 个可能全是抽奖，必须下滑扫完才能判收工",
                "let emptyRounds = 0;",
                "for (let r = 0; r < $rounds; r++) {",
                "  let list = descMatches(\".*得\\\\d+京豆.*\").find();",
                "  if (list.length > 0) { emptyRounds = 0; break; }",
                "  emptyRounds++;",
                "  if (emptyRounds >= $zero) { break; }",
                "  swipe(device.width / 2, device.height * 0.78, device.width / 2, device.height * 0.28, 420);",
                "  sleep(800);",
                "}",
                "if (emptyRounds >= $zero) { log(\"收工：连续 \" + emptyRounds + \" 轮未发现京豆任务\"); } else { log(\"仍有任务，继续处理\"); }"
            ).joinToString("\n")
        }
    }

    // ===================== 导入的原生代码 =====================
    /**
     * Verbatim AutoJs6 script. Used when a script imported from `.js` contains constructs the
     * block catalogue cannot express (helper functions, `ocr.detect()`, `app.startActivity`…):
     * keeping the source as one block guarantees the imported project behaves exactly like the
     * original instead of silently dropping code we failed to map.
     */
    private fun defRaw() {
        def(
            "rawJs", "raw", "原生 JavaScript 代码（导入的脚本）",
            params = listOf(ParamDef("code", "代码", ParamKind.CODE, ParamValue.Text(""))),
            slots = emptyList()
        ) { b, c ->
            val body = b.params["code"]?.asText().orEmpty()
            if (body.isBlank()) "// （空的原生代码积木）" else body
        }
    }

    // ===================== 我的模块 =====================
    private fun defModule() {
        def(
            "moduleCall", "module", "调用模块 {module}",
            listOf(sel("module", "模块", "", emptyList()), expr("args", "实参", ""))
        ) { b, c -> c.callModule(b) }
    }

    // ---- lookups -------------------------------------------------------------
    fun byType(type: String): BlockDef? = byType[type]
    fun byCat(catId: String): List<BlockDef> = defs.filter { it.cat == catId }
    fun all(): List<BlockDef> = defs
    fun colorOf(catId: String): Long = CATS.firstOrNull { it.id == catId }?.color ?: 0xFF4F8CFF
    fun nameOf(type: String): String = byType[type]?.name ?: type

    /** Block title with `{param}` placeholders stripped, for palette rows. */
    fun cleanName(def: BlockDef): String {
        val noPlaceholders = Regex("\\{[^}]*\\}").replace(def.name, "")
        return noPlaceholders.replace(Regex("\\s+"), " ").trim()
    }

    private fun dirname(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx > 0) path.substring(0, idx) else path
    }

    /** Escape text that goes inside a generated double-quoted JS string literal. */
    private fun escForString(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}