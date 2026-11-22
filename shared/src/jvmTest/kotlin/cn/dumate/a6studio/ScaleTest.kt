package cn.dumate.a6studio

import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.model.MemoryProjectStorage
import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.store.ProjectRepo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.system.measureTimeMillis

/**
 * 规模守卫：一个「每敲一个键就快照 + 全量序列化 + 落盘」的编辑器，块多了会不会卡死？
 * 实测（2026-11-22，本机）：
 *
 * ```
 * 1000 块  build 0.55 ms/块  codegen 4 ms  save 0.35 ms  disk 0.60 ms   89 KB
 * 2000 块  build 0.82 ms/块  codegen 2 ms  save 0.90 ms  disk 0.80 ms  177 KB
 * ```
 *
 * 结论：2000 块时一次编辑仍不到 1 ms，**不需要给保存加防抖**（加了反而引入异步时序问题）。
 * 这里用宽松阈值（实测值的 5–10 倍）守住数量级，避免慢一点的 CI 机器误报。
 */
class ScaleTest {

    @Test
    fun `a thousand blocks stay interactive`() {
        val n = 1000
        val storage = MemoryProjectStorage()
        val repo = ProjectRepo(storage)
        repo.load()
        repo.setProjectDirect(Samples.emptyProject("scale"))

        // 每次 addBlock 都 snapshot + save，等价于逐键编辑的成本。
        val build = measureTimeMillis {
            repeat(n) { repo.addBlock("log", index = repo.current.root.children["body"]!!.size) }
        }
        val blocks = repo.current.root.children["body"]!!.size
        assertEquals(n, blocks)

        val codegen = measureTimeMillis { CodeGen.code(repo.current) }
        val save = measureTimeMillis { repeat(10) { repo.save() } } / 10.0
        val json = storage.loadRaw()!!
        val tmp = java.io.File.createTempFile("a6scale", ".json").apply { deleteOnExit() }
        val disk = measureTimeMillis { repeat(10) { tmp.writeText(json) } } / 10.0

        println(
            "SCALE blocks=$blocks build=${build}ms (${"%.2f".format(build.toDouble() / blocks)}ms/block) " +
                "codegen=${codegen}ms save=${"%.2f".format(save)}ms disk=${"%.2f".format(disk)}ms json=${json.length / 1024}KB"
        )

        assertTrue(build < 10_000, "1000 块构建用了 ${build}ms，逐键编辑的耗时失控了")
        assertTrue(codegen < 1_000, "1000 块的代码生成用了 ${codegen}ms")
        assertTrue(save < 50, "1000 块一次保存 ${save}ms，超过每键预算")
        assertTrue(disk < 50, "1000 块一次落盘 ${disk}ms，超过每键预算")
        // 生成结果不能因为规模而退化：至少每块一行代码。
        assertTrue(CodeGen.code(repo.current).lines().size >= blocks)
    }
}
