package cn.dumate.a6studio

import cn.dumate.a6studio.catalog.Catalog
import cn.dumate.a6studio.model.ParamValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BlockTitleTest {

    @Test
    fun `canvas title shows the current parameter values`() {
        val keep = Catalog.byType("keepScreenOn")!!
        assertEquals("保持屏幕常亮 60000 毫秒", Catalog.filledName(keep, mapOf("ms" to ParamValue.Num(60000.0))))

        val loop = Catalog.byType("jdTaskLoop")!!
        assertEquals("④ 遍历京豆任务（每页最多 8 个）", Catalog.filledName(loop, mapOf("max" to ParamValue.Num(8.0))))
    }

    @Test
    fun `missing values fall back to the parameter default`() {
        val keep = Catalog.byType("keepScreenOn")!!
        assertEquals("保持屏幕常亮 1800000 毫秒", Catalog.filledName(keep, emptyMap()))
    }

    @Test
    fun `no title on the canvas keeps an unfilled placeholder`() {
        for (def in Catalog.all()) {
            val name = Catalog.filledName(def, emptyMap())
            assertFalse(name.contains('{') || name.contains('}'), "${def.type}: $name")
        }
    }
}
