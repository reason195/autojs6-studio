package cn.dumate.a6studio.model


/**
 * Ready-made scripts. A beginner should never have to face an empty canvas: picking a template
 * gives them a working script they can run immediately, then tweak one field at a time.
 */
data class Template(
    val id: String,
    val title: String,
    val summary: String,
    val needsDevice: Boolean,
    val build: () -> Project
) {
    val beginnerSafe: Boolean get() = !needsDevice || true
}

object Templates {

    val all: List<Template> = listOf(
        Template(
            id = "hello",
            title = "打个招呼（最简单的第一个脚本）",
            summary = "打开微信，弹一句提示。用来确认脚本能跑起来。",
            needsDevice = true,
            build = { helloWorld() }
        ),
        Template(
            id = "openAndTap",
            title = "打开 App 并点一个按钮",
            summary = "打开淘宝，等首页出现，然后点「我的」。改成你自己的文字就能用。",
            needsDevice = true,
            build = { openAndTap() }
        ),
        Template(
            id = "waitAndType",
            title = "在搜索框里输入并搜索",
            summary = "打开浏览器，等搜索框出现，输入内容再点搜索。",
            needsDevice = true,
            build = { searchFlow() }
        ),
        Template(
            id = "repeatSwipe",
            title = "自动往下滑 10 次",
            summary = "适合刷列表、看短视频之类的重复动作。",
            needsDevice = true,
            build = { repeatSwipe() }
        ),
        Template(
            id = "jdCoupon",
            title = "京东领券中心（完整示例）",
            summary = "进入领券中心、签到、把京豆任务逐个做完。可以先「运行」看效果再上真机。",
            needsDevice = false,
            build = { Samples.exampleProject() }
        )
    )

    fun byId(id: String): Template? = all.firstOrNull { it.id == id }

    // ---- builders -----------------------------------------------------------
    private fun blank(name: String) = Samples.emptyProject(name)

    private fun Project.withBody(blocks: List<Block>): Project =
        copy(root = root.copy(children = mapOf("body" to blocks)))

    private fun helloWorld(): Project = blank("打招呼").withBody(
        listOf(
            newBlock("comment", mapOf("text" to ParamValue.Text("第一个脚本：打开微信，然后弹一句提示"))),
            newBlock("toast", mapOf("text" to ParamValue.Text("脚本开始啦"))),
            newBlock("launchPackage", mapOf("pkg" to ParamValue.Text("com.tencent.mm"), "wait" to ParamValue.Num(2000.0))),
            newBlock("toast", mapOf("text" to ParamValue.Text("微信已经打开了"))),
            newBlock("comment", mapOf("text" to ParamValue.Text("想改内容？点上面积木里的文字框直接改就行"))),
            newBlock("sleep", mapOf("ms" to ParamValue.Num(2000.0)))
        )
    )

    private fun openAndTap(): Project = blank("打开并点击").withBody(
        listOf(
            newBlock("comment", mapOf("text" to ParamValue.Text("打开淘宝 → 等首页 → 点「我的」"))),
            newBlock("autoWait"),
            newBlock("launchPackage", mapOf("pkg" to ParamValue.Text("com.taobao.taobao"), "wait" to ParamValue.Num(3000.0))),
            newBlock(
                "waitText",
                mapOf("value" to ParamValue.Text("搜索"), "timeout" to ParamValue.Num(8000.0))
            ),
            newBlock("clickNode", mapOf("value" to ParamValue.Text("我的"), "timeout" to ParamValue.Num(3000.0))),
            newBlock("toast", mapOf("text" to ParamValue.Text("已经点进去了")))
        )
    )

    private fun searchFlow(): Project = blank("搜索").withBody(
        listOf(
            newBlock("comment", mapOf("text" to ParamValue.Text("把 input 改成你想搜的词"))),
            newBlock("autoWait"),
            newBlock("launchPackage", mapOf("pkg" to ParamValue.Text("com.android.chrome"), "wait" to ParamValue.Num(3000.0))),
            newBlock(
                "setTextNode",
                mapOf("value" to ParamValue.Text("搜索或输入网址"), "text" to ParamValue.Text("AutoJs6"))
            ),
            newBlock("pressKey", mapOf("key" to ParamValue.Text("menu"))),
            newBlock("sleep", mapOf("ms" to ParamValue.Num(3000.0))),
            newBlock("toast", mapOf("text" to ParamValue.Text("搜完啦")))
        )
    )

    private fun repeatSwipe(): Project = blank("自动下滑").withBody(
        listOf(
            newBlock("comment", mapOf("text" to ParamValue.Text("把「10」改成你要滑的次数"))),
            newBlock("autoWait"),
            newBlock(
                "forRange",
                mapOf("i" to ParamValue.Text("i"), "n" to ParamValue.Num(10.0))
            ).copy(
                children = mapOf(
                    "body" to listOf(
                        newBlock("swipeUpList"),
                        newBlock("sleep", mapOf("ms" to ParamValue.Num(800.0)))
                    ),
                    "else" to emptyList()
                )
            ),
            newBlock("toast", mapOf("text" to ParamValue.Text("滑完了")))
        )
    )
}