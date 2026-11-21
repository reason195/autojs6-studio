package cn.dumate.a6studio

import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.catalog.Friendly
import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.model.Templates
import cn.dumate.a6studio.model.TreeOps
import cn.dumate.a6studio.runtime.Runner
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateTest {

    @Test
    fun `every template generates runnable code`() {
        Templates.all.forEach { t ->
            val project = t.build()
            val code = CodeGen.code(project)
            assertTrue(code.isNotBlank(), "${t.id} produced no code")
            assertTrue(
                TreeOps.count(project.root.children["body"].orEmpty()) > 0,
                "${t.id} has no blocks"
            )
            // Local simulator run must not crash: templates are meant to run out of the box.
            val result = Runner.run(project)
            assertTrue(
                result.status == "finished" || result.status == "exited",
                "${t.id} failed in simulator: ${result.error}"
            )
        }
    }

    @Test
    fun `hello template opens wechat and toasts`() {
        val code = CodeGen.code(Templates.byId("hello")!!.build())
        assertContains(code, "com.tencent.mm")
        assertContains(code, "toast(\"脚本开始啦\")")
    }

    @Test
    fun `repeat template wraps swipe in a loop`() {
        val code = CodeGen.code(Templates.byId("repeatSwipe")!!.build())
        assertContains(code, "for (let i = 0; i < 10; i++)")
        assertContains(code, "swipe(device.width / 2")
    }

    @Test
    fun `friendliness covers every category and every starter block`() {
        val catIds = cn.dumate.a6studio.catalog.Catalog.CATS.map { it.id }
        assertEquals(catIds.toSet(), Friendly.categories.map { it.id }.toSet(), "category list drifted")
        Friendly.starterBlocks.forEach { type ->
            assertTrue(
                cn.dumate.a6studio.catalog.Catalog.byType(type) != null,
                "starter block $type does not exist"
            )
            assertTrue(Friendly.describe(type).isNotEmpty(), "starter block $type has no description")
        }
    }

    @Test
    fun `every block has a plain language description`() {
        val missing = cn.dumate.a6studio.catalog.Catalog.all()
            .map { it.type }
            .filter { Friendly.describe(it).isEmpty() }
        assertEquals(emptyList(), missing, "blocks without a beginner description")
    }
}
