package cn.dumate.a6studio

import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.model.Samples
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Porting guard: the Kotlin code generator must emit the same script as the original web
 * generator for the built-in sample project. The expected files are produced by
 * `build/crosscheck/legacy-gen.mjs` (which runs the untouched legacy `js/codegen.js`).
 *
 * Only the header timestamp differs between runs, so it is normalised before comparing.
 */
class LegacyParityTest {

    /** Gradle runs tests with the module dir as CWD, so probe upward for the repo root. */
    private fun fixture(name: String): File? {
        val candidates = listOf(
            File("build/crosscheck/$name"),
            File("../build/crosscheck/$name"),
            File("../../build/crosscheck/$name")
        )
        return candidates.firstOrNull { it.exists() }
    }

    private val markerRe = Regex("""//@b_[a-z0-9]+_\d+""")

    /**
     * Block ids are generated from a timestamp + counter, so they differ between two runs.
     * Everything else — which blocks get a marker, their order and indentation — must match.
     */
    private fun normalize(text: String): List<String> =
        text.lines()
            .filterNot { it.contains("由 AutoJs6 Studio 可视化积木生成") }
            .map { markerRe.replace(it.trimEnd(), "//@BLOCK") }

    @Test
    fun `sample project generates identical script to legacy build`() {
        val legacyFile = fixture("legacy.js")
        val legacyMarkedFile = fixture("legacy-marked.js")
        if (legacyFile == null || legacyMarkedFile == null) {
            println("SKIP LegacyParityTest: run `node tools/legacy-gen.mjs` to generate the fixture")
            return
        }

        val project = Samples.exampleProject()
        val kotlinPlain = normalize(CodeGen.code(project, markers = false))
        val legacyPlain = normalize(legacyFile.readText())
        assertEquals(legacyPlain, kotlinPlain, "plain codegen diverged from legacy build")

        val kotlinMarked = normalize(CodeGen.code(project, markers = true))
        val legacyMarked = normalize(legacyMarkedFile.readText())
        assertEquals(legacyMarked, kotlinMarked, "marked codegen diverged from legacy build")
    }
}