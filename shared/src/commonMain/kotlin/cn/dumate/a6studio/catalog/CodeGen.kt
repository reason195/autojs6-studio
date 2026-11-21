package cn.dumate.a6studio.catalog

import cn.dumate.a6studio.model.Block
import cn.dumate.a6studio.model.Project
import cn.dumate.a6studio.model.StudioModule

/**
 * Emits AutoJs6 (Rhino) source from a project.
 *
 * Two modes, matching the original build:
 *  - `markers = false` → clean script for export / running on device.
 *  - `markers = true`  → a `//@<blockId>` line before each block so the editor can
 *    highlight the code lines a selected block produced.
 */
object CodeGen {

    fun code(project: Project, markers: Boolean = false): String {
        val ctx = GenContext(project, markers)
        val parts = mutableListOf(header(project))
        val modules = modulesCode(project, ctx)
        if (modules.isNotEmpty()) parts += modules

        val main = generateList(project.root.children["body"].orEmpty(), project, ctx)
        parts += if (main.isNotEmpty()) main else "// 主程序为空：从左侧拖入积木开始"
        return parts.joinToString("\n\n") + "\n"
    }

    fun blockCode(block: Block, project: Project): String = generateBlock(block, project, GenContext(project))

    fun moduleName(name: String): String = Js.moduleName(name)

    fun generateList(list: List<Block>, project: Project, ctx: GenContext): String =
        list.mapNotNull { b ->
            val text = generateBlock(b, project, ctx)
            if (text.isEmpty()) null
            else if (ctx.markers) "//@${b.id}\n$text" else text
        }.joinToString("\n")

    private fun generateBlock(block: Block, project: Project, ctx: GenContext): String {
        val def = Catalog.byType(block.type) ?: return ""
        return try {
            def.gen(block, ctx)
        } catch (e: Exception) {
            "// 生成失败: ${block.type} (${e.message})"
        }
    }

    private fun modulesCode(project: Project, ctx: GenContext): String =
        project.modules.joinToString("\n\n") { m ->
            val body = generateList(m.children["body"].orEmpty(), project, ctx)
            "/** 模块：${m.desc.ifBlank { m.name }} */\n" +
                "function ${Js.moduleName(m.name)}(${m.args}) {\n" +
                Js.indentLines(body.ifEmpty { "// 空模块" }, 1) + "\n}"
        }

    private fun header(project: Project): String {
        val now = nowLocal()
        val stamp = "${now.year}-${pad(now.month)}-${pad(now.day)} ${pad(now.hour)}:${pad(now.minute)}"
        return listOf(
            "/**",
            " * ${project.name.ifBlank { "AutoJs6 脚本" }}",
            " * 由 AutoJs6 Studio 可视化积木生成 · $stamp",
            " * 目标运行时：AutoJs6 6.x（Rhino）· 语法遵循 AutoJs6 全局 API",
            " */",
            "\"auto\";"
        ).joinToString("\n")
    }

    private fun pad(n: Int) = n.toString().padStart(2, '0')

    fun moduleCallName(module: StudioModule): String = Js.moduleName(module.name)
}