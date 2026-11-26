package cn.dumate.a6studio

import cn.dumate.a6studio.model.MemoryProjectStorage
import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.model.newBlock
import cn.dumate.a6studio.picker.Candidate
import cn.dumate.a6studio.picker.PickerLogic
import cn.dumate.a6studio.picker.UiNode
import cn.dumate.a6studio.recorder.RecEvent
import cn.dumate.a6studio.recorder.RecorderController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PickerLogicTest {

    private val node = UiNode(
        depth = 2,
        className = "android.widget.Button",
        text = "天天领豆",
        id = "com.jingdong.app.mall:id/coupon-sign",
        left = 600, top = 300, right = 1000, bottom = 420,
        clickable = true
    )

    private val bare = UiNode(
        className = "android.widget.Button",
        text = "立即领取",
        left = 0, top = 0, right = 10, bottom = 10,
        clickable = true
    )

    @Test
    fun `center and short names`() {
        assertEquals(800, node.centerX)
        assertEquals(360, node.centerY)
        assertEquals("coupon-sign", PickerLogic.shortId(node.id))
        assertEquals("Button", PickerLogic.shortClass(node.className))
    }

    @Test
    fun `candidate order puts id first`() {
        assertEquals(listOf("id", "idMatches", "text", "textContains"), PickerLogic.buildCandidates(node).map { it.by })
        assertEquals(".*coupon-sign$", PickerLogic.buildCandidates(node)[1].value)
    }

    @Test
    fun `node without id skips id candidates`() {
        assertEquals(listOf("text", "textContains"), PickerLogic.buildCandidates(bare).map { it.by })
    }

    @Test
    fun `scores degrade with weaker selectors`() {
        val c = PickerLogic.buildCandidates(node)
        assertEquals(100, PickerLogic.scoreOf(c[0], node, 1))
        assertEquals(90, PickerLogic.scoreOf(c[2], node, 1))
        assertEquals(84, PickerLogic.scoreOf(c[3], node, 1))
        assertEquals(76, PickerLogic.scoreOf(c[3], node, 2))
        assertEquals(59, PickerLogic.scoreOf(c[3], node, 0))
    }

    @Test
    fun `expressions use the matching selector function`() {
        val c = PickerLogic.buildCandidates(node)
        assertEquals("text(\"天天领豆\")", PickerLogic.toExpr(c[2]))
        assertEquals("id(\"com.jingdong.app.mall:id/coupon-sign\")", PickerLogic.toExpr(c[0]))
    }

    @Test
    fun `regex escaping keeps metacharacters literal`() {
        assertEquals("a\\.b\\[c\\]", PickerLogic.escapeRegex("a.b[c]"))
        // `{` `}` are regex quantifiers: unescaped `a{` throws at compile time, so the
        // idMatches selector for a view id containing braces used to be broken.
        assertEquals("a\\{2\\}", PickerLogic.escapeRegex("a{2}"))
    }

    @Test
    fun `fallback chain nests with exists checks`() {
        val a = Candidate("id", "x", "id", count = 1, score = 100)
        val b = Candidate("text", "y", "text", count = 1, score = 90)
        assertEquals(
            "id(\"x\").exists() ? id(\"x\") : text(\"y\")",
            PickerLogic.chainExpr(listOf(a, b))
        )
    }

    @Test
    fun `chain stops at three links and skips unverified`() {
        val cands = listOf(
            Candidate("id", "a", "", count = 1, score = 100),
            Candidate("id", "b", "", count = null, score = 100),
            Candidate("text", "c", "", count = 2, score = 84),
            Candidate("desc", "d", "", count = 1, score = 94),
            Candidate("textContains", "e", "", count = 1, score = 84)
        )
        val expr = PickerLogic.chainExpr(cands)!!
        assertTrue(!expr.contains("id(\"b\")"), "unverified candidate must not appear: $expr")
        assertEquals(2, expr.split("?").size - 1)
    }

    @Test
    fun `bestOf prefers a unique candidate even at lower score`() {
        val cands = listOf(
            Candidate("idMatches", ".*a$", "", count = 4, score = 84),
            Candidate("text", "b", "", count = 1, score = 90)
        )
        assertEquals("b", PickerLogic.bestOf(cands)!!.value)
    }

    @Test
    fun `buildCandidates normalizes json payloads`() {
        val raw = PickerLogic.json.parseToJsonElement(
            """{"depth":1,"className":"android.widget.TextView","text":"更多任务","id":"a/b","clickable":true,
                "bounds":{"left":10,"top":20,"right":110,"bottom":80}}"""
        )
        val n = PickerLogic.normalize(raw as kotlinx.serialization.json.JsonObject)
        assertEquals("更多任务", n.text)
        assertEquals("TextView", PickerLogic.shortClass(n.className))
        assertEquals(100, n.width)
        assertEquals(60, n.height)
    }

    @Test
    fun `region drill rebuilds hierarchy from flat bounds`() {
        val parent = UiNode(className = "android.widget.LinearLayout", left = 0, top = 0, right = 1080, bottom = 2000)
        val child = UiNode(className = "android.widget.TextView", text = "a", left = 10, top = 10, right = 200, bottom = 100)
        val grandchild = UiNode(className = "android.widget.TextView", text = "b", left = 20, top = 20, right = 100, bottom = 60)
        val sibling = UiNode(className = "android.widget.TextView", text = "c", left = 10, top = 300, right = 200, bottom = 400)
        val ordered = PickerLogic.buildHierarchy(listOf(grandchild, child, sibling, parent))
        assertEquals(listOf("", "a", "b", "c"), ordered.map { it.text })
        assertEquals(listOf(0, 1, 2, 1), ordered.map { it.depth })
    }

    @Test
    fun `empty tree does not crash the hierarchy builder`() {
        assertEquals(emptyList(), PickerLogic.buildHierarchy(emptyList()))
    }
}

class RecorderLogicTest {

    private val controller = RecorderController(cn.dumate.a6studio.bridge.McpClient(io.ktor.client.HttpClient())) { }

    private val jsonl = """
        {"type":"start","path":"/sdcard/studio-record.jsonl","screen":{"w":1080,"h":2376},"pkg":"com.jingdong.app.mall"}
        {"type":"tap","x":7606,"y":30482,"node":{"text":"天天领豆","id":"com.jingdong.app.mall:id/coupon-sign","counts":{"id":1,"text":1,"textContains":1,"desc":-1}}}
        {"type":"tap","x":500,"y":900,"node":{"text":"立即领取","counts":{"id":-1,"text":2,"textContains":2,"desc":-1}}}
        {"type":"swipe","x1":540,"y1":1600,"x2":540,"y2":600,"dur":420}
        {"type":"key","key":"back"}
        {"type":"start","screen":{"w":1080,"h":2376}}
    """.trimIndent()

    @Test
    fun `parse jsonl and ignore junk lines`() {
        val evs = controller.parseLines(jsonl + "\n{broken\n\n")
        assertEquals(6, evs.size)
        assertEquals("tap", evs[1].type)
    }

    @Test
    fun `hundredth-pixel coordinates are normalised`() {
        val evs = controller.normalizeEvents(controller.parseLines(jsonl))
        val tap = evs.first { it.type == "tap" }
        assertTrue(tap.x <= 1080, "x=${tap.x}")
        assertTrue(tap.y <= 2376, "y=${tap.y}")
    }

    @Test
    fun `unique selector wins, otherwise coordinates`() {
        val evs = controller.normalizeEvents(controller.parseLines(jsonl)).filter { it.usable }
        assertEquals("id" to "com.jingdong.app.mall:id/coupon-sign", controller.pickSelector(evs[0]))
        assertNull(controller.pickSelector(evs[1]))
    }

    @Test
    fun `events become blocks of the right type`() {
        val evs = controller.normalizeEvents(controller.parseLines(jsonl))
        val blocks = controller.blocksFor(evs, 0)
        assertEquals(listOf("clickNode", "coordClick", "swipe", "pressKey"), blocks.map { it.type })
        assertEquals(ParamValue.Text("com.jingdong.app.mall:id/coupon-sign"), blocks[0].params["value"])
    }

    @Test
    fun `only fresh events are converted`() {
        val evs = controller.normalizeEvents(controller.parseLines(jsonl))
        assertEquals(2, controller.blocksFor(evs, 2).size)
    }
}