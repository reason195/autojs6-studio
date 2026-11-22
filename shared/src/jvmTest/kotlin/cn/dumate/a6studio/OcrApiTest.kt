package cn.dumate.a6studio

import cn.dumate.a6studio.runtime.SimRuntime
import kotlin.test.Test
import kotlin.test.assertTrue

/** Rhino's newArray insists on an Object[] component type; String[] throws. */
class OcrApiTest {
    @Test
    fun `ocr helpers are callable`() {
        listOf(
            "ocr.recognizeText(images.captureScreen());",
            "ocr.detect();",
            "ocr.recognizeTextFromImage(null);",
            "JSON.stringify(ocr.detect().length);"
        ).forEach { src ->
            val sim = SimRuntime()
            val (status, err) = sim.execute(src)
            println("OCR [$src] -> $status ${err ?: ""}")
            assertTrue(status == "finished", "$src failed: $err")
        }
    }
}
