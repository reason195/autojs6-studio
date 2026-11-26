package cn.dumate.a6studio.ui

/**
 * Rewrites technical findings into something a non-programmer can act on.
 *
 * The diagnostic engine reports in AutoJs6 vocabulary ("控件定位值为空", "缺少收口判定") because
 * that is what an experienced user wants. A beginner needs three things instead: what they will
 * see happen, why it happens, and the exact next click.
 */
object BeginnerCopy {

    /** Keyed by the technical title emitted by the diagnostic engine. */
    private val titles: Map<String, String> = mapOf(
        "主程序还是空的" to "还没有任何积木",
        "UI 操作前没有 auto.waitFor()" to "点按钮之前没有先等手机准备好",
        "控件定位值为空" to "有一个「点击/输入」积木没写要点的字",
        "等待控件未设置超时" to "有一个「等待」积木没设等待时间",
        "sleep(0) 无实际等待" to "有个「等待」积木填的是 0，等于没等",
        "单次等待超过 30 秒" to "有个「等待」积木等太久了",
        "循环内有 3 秒以上等待" to "重复的动作里每次都等很久，会很慢",
        "循环上限过大" to "重复次数设得太多了",
        "容器积木是空的" to "有个「如果/重复」积木里面还没放东西",
        "函数名为空" to "有个「调用函数」积木没写名字",
        "未选择模块" to "有个「调用模块」积木还没选模块",
        "变量名为空" to "有个积木的变量名空着",
        "网络请求没有异常保护" to "联网操作没做防断网处理",
        "签到前没有「进入领券中心」" to "京东示例里少了开头那步",
        "任务循环缺少「关弹窗并重读」" to "京东示例少了一个关键步骤",
        "任务循环体为空" to "「遍历任务」积木里面还没放东西",
        "缺少「收工判定」" to "京东示例少了「确认做完」那一步",
        "模块名为空" to "有一个模块没起名字",
        "还没有测试用例" to "还没有「自检」用例（可以不管）",
        "运行抛出异常" to "脚本跑到一半停下来了",
        "运行超时被自动停止" to "脚本一直跑个不停，被自动叫停了",
        "疑似「0 任务」误判收工" to "可能漏做了任务就提前结束"
    )

    private val hints: Map<String, String> = mapOf(
        "主程序还是空的" to "点上面的「📋 模板」选一个现成的，或者点左边的积木加进来。",
        "UI 操作前没有 auto.waitFor()" to "在开头加一块「等待手机准备好」，不然点按钮时经常点空。",
        "控件定位值为空" to "找到那个红色的积木，在文字框里填上屏幕上真实看到的字，比如「立即领取」。",
        "等待控件未设置超时" to "把等待时间填上，比如 8000（= 8 秒）。",
        "sleep(0) 无实际等待" to "改成 500~1500 之间的数字。",
        "单次等待超过 30 秒" to "改成十几秒，或者换成「等某段文字出现」。",
        "容器积木是空的" to "把要做的动作拖到它下面；没有内容的话，删掉也可以。",
        "任务循环体为空" to "把「完成一个任务」这块放进「遍历任务」里面。",
        "运行抛出异常" to "看下面日志最后一行，那里写着停在了哪一步。",
        "运行超时被自动停止" to "检查「重复」积木或代码里的循环结束条件，是不是永远停不下来。",
        "疑似「0 任务」误判收工" to "在「确认做完」之前，先放一块「关掉弹窗再重新数一遍」。"
    )

    /** Falls back to the original title when there is no friendlier wording. */
    fun issueTitle(technical: String): String {
        titles[technical]?.let { return it }
        return when {
            technical.startsWith("模块「") -> "有一个模块是空的，调用它什么也不会发生"
            technical.startsWith("有 ") && technical.contains("等待超时") -> "有几次没等到要找的东西"
            technical.startsWith("循环保护触发") -> "重复太多次被自动叫停了"
            technical.startsWith("断言失败") -> "自检没通过"
            technical.startsWith("模拟耗时") -> "这次模拟跑得比较久"
            else -> technical
        }
    }

    fun issueHint(technical: String, original: String): String {
        val hit = hints.entries
            .filter { technical.startsWith(it.key) }
            .maxByOrNull { it.key.length }
        return hit?.value ?: original
    }

    /** The run badge, phrased so it answers "did it work?". */
    fun runStatus(status: String): String = when {
        status.startsWith("完成") -> {
            // "完成 · 模拟 40s" -> "跑完了（模拟用了 40s）"
            val detail = status.substringAfter("·", "").trim().removePrefix("模拟").trim()
            if (detail.isEmpty()) "跑完了" else "跑完了（模拟用了 $detail）"
        }
        status == "运行中" -> "正在跑…"
        status == "异常" -> "跑到一半出错了"
        status == "已退出" -> "脚本自己结束了"
        status == "已停止" -> "你让它停下了"
        status == "超时" -> "跑太久被自动停下了"
        status == "未运行" -> "还没开始"
        status.startsWith("真机") -> "在真机上跑完了"
        else -> status
    }

    /** Run-log lines get a short "what this means" prefix in beginner mode. */
    fun logLine(msg: String): String = when {
        msg.contains("未找到控件") -> msg + "　← 屏幕上看不到这个文字，可能已经变了"
        msg.contains("等待控件超时") -> msg + "　← 等了很久也没出现，检查字是不是写错了"
        msg.contains("被自动停止") -> msg + "　← 多半是某个循环永远停不下来，检查循环的结束条件"
        msg.contains("循环保护触发") -> msg + "　← 重复太多次被自动停下，避免卡死"
        msg.contains("断言失败") -> msg + "　← 自检没通过"
        msg.contains("收工") -> msg + "　← 确认没有更多任务了"
        else -> msg
    }
}