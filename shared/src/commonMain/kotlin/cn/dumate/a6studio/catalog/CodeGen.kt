package cn.dumate.a6studio.catalog

import cn.dumate.a6studio.diagnose.Diagnose
import cn.dumate.a6studio.model.Block
import cn.dumate.a6studio.model.Project
import cn.dumate.a6studio.model.StudioModule
import cn.dumate.a6studio.runtime.Aj6KitRuntime

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
        if (usesKit(project)) {
            parts += Aj6KitRuntime.SOURCE
        }
        val modules = modulesCode(project, ctx)
        if (modules.isNotEmpty()) parts += modules

        val main = generateList(project.root.children["body"].orEmpty(), project, ctx)
        parts += if (main.isNotEmpty()) main else "// 主程序为空：从左侧拖入积木开始"
        return parts.joinToString("\n\n") + "\n"
    }

    fun blockCode(block: Block, project: Project): String = generateBlock(block, project, GenContext(project))

    fun moduleName(name: String): String = Js.moduleName(name)

    /**
     * Blocks whose templates declare fixed top-level names (`let signNode`, `let _nodes`, …).
     * Two of them in one list redeclared the same `let`, which is a SyntaxError that stops the
     * WHOLE script from running (M4). From the second occurrence on, the block is wrapped in
     * its own `{ }` scope. The first stays unwrapped, so single-use output is unchanged and the
     * legacy parity fixtures still match.
     */
    private val fixedNameTypes = setOf("dumpUi", "jdSign", "jdOpenPanel", "jdTaskLoop", "jdFinishGuard")

    fun generateList(list: List<Block>, project: Project, ctx: GenContext): String {
        val seen = mutableSetOf<String>()
        return list.mapNotNull { b ->
            var text = generateBlock(b, project, ctx)
            if (text.isNotEmpty() && b.type in fixedNameTypes && !seen.add(b.type)) {
                text = "{\n" + Js.indentLines(text, 1) + "\n}"
            }
            if (text.isEmpty()) null
            else if (ctx.markers) "//@${Js.idSafe(b.id)}\n$text" else text
        }.joinToString("\n")
    }

    private fun generateBlock(block: Block, project: Project, ctx: GenContext): String {
        val def = Catalog.byType(block.type) ?: return ""
        val identKeys = Diagnose.identifierParams[block.type].orEmpty()
        for (key in identKeys) {
            val raw = ctx.str(block, key).trim()
            if (raw.isNotEmpty() && !Diagnose.isJsIdentifier(raw)) {
                return "// 非法标识符已跳过: ${Js.commentSafe("${block.type}.$key = $raw")}"
            }
        }
        return try {
            def.gen(block, ctx)
        } catch (e: Exception) {
            "// 生成失败: ${Js.commentSafe("${block.type} (${e.message})")}"
        }
    }

    private fun modulesCode(project: Project, ctx: GenContext): String =
        project.modules.joinToString("\n\n") { m ->
            val body = generateList(m.children["body"].orEmpty(), project, ctx)
            "/** 模块：${Js.commentSafe(m.desc.ifBlank { m.name }, block = true)} */\n" +
                "function ${Js.moduleName(m.name)}(${moduleArgs(m.args)}) {\n" +
                Js.indentLines(body.ifEmpty { "// 空模块" }, 1) + "\n}"
        }

    /**
     * Module parameter list. `m.args` is free text, so `a b`, `x-y` or a multi-line value used
     * to land straight in the function signature — a SyntaxError, or worse a second statement.
     * Keep the valid identifiers, drop the rest (the module call still passes arguments by
     * position, which is what the block editor shows). Reserved words (`class`, `if`) are
     * dropped too: Rhino rejects the whole script on them.
     */
    private fun moduleArgs(raw: String): String = Js.paramList(raw)

    private fun header(project: Project): String {
        val now = nowLocal()
        val stamp = "${now.year}-${pad(now.month)}-${pad(now.day)} ${pad(now.hour)}:${pad(now.minute)}"
        return listOf(
            "/**",
            " * ${Js.commentSafe(project.name.ifBlank { "AutoJs6 脚本" }, block = true)}",
            " * 由 AutoJs6 Studio 可视化积木生成 · $stamp",
            " * 目标运行时：AutoJs6 6.x（Rhino）· 语法遵循 AutoJs6 全局 API",
            " */",
            "\"auto\";"
        ).joinToString("\n")
    }

    private fun pad(n: Int) = n.toString().padStart(2, '0')

    fun moduleCallName(module: StudioModule): String = Js.moduleName(module.name)

    private fun usesKit(project: Project): Boolean =
        hasKitBlock(project.root.children["body"].orEmpty()) ||
            project.modules.any { hasKitBlock(it.children["body"].orEmpty()) }

    private fun hasKitBlock(blocks: List<Block>): Boolean =
        blocks.any { b ->
            b.type.startsWith("kit") || b.children.values.any { hasKitBlock(it) }
        }
}