package cn.dumate.a6studio

import cn.dumate.a6studio.bridge.McpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Live integration check against a real AutoJs6 device. Disabled unless both env vars are set,
 * so it never breaks CI:
 *
 *   A6_MCP_URL=http://<手机IP>:9637/mcp A6_MCP_TOKEN=<令牌> \
 *     ./gradlew :shared:jvmTest --tests "cn.dumate.a6studio.LiveMcpTest"
 *
 * This exists because "抓不到真机" was the recurring complaint: the client logic must be verified
 * against the actual server, not against a mock.
 */
class LiveMcpTest {

    private val url = System.getenv("A6_MCP_URL")
    private val token = System.getenv("A6_MCP_TOKEN")

    @Test
    fun `handshake ping and ui dump against a real device`() {
        if (url.isNullOrBlank()) {
            println("SKIP LiveMcpTest: set A6_MCP_URL / A6_MCP_TOKEN")
            return
        }
        val client = McpClient(HttpClient(CIO))
        client.setUrl(url)
        if (!token.isNullOrBlank()) client.setAuth(token)

        runBlocking {
            val ms = client.ping()
            println("LIVE ping ok in ${ms}ms")
            val info = client.deviceInfo()
            println("LIVE device: ${info.label} android=${info.androidRelease} api=${info.androidApi}")
            val dump = client.uiDumpJson(maxNodes = 20, maxDepth = 6)
            val nodes = dump["nodes"]?.toString()?.length ?: 0
            println("LIVE ui_dump window=${dump["window"]} nodesJsonChars=$nodes")
            val win = client.currentWindow()
            println("LIVE window: ${win.packageName}/${win.activityName}")

            // End-to-end: actually execute a script on the phone and read its console back.
            val run = client.runScript(
                "toast('studio-live-ok'); log('STUDIO_LIVE_RESULT=42');",
                "a6studio-live"
            )
            println("LIVE script_run: ${run.take(200)}")
            val tail = client.consoleTail(30)
            println("LIVE console has marker: ${tail.contains("STUDIO_LIVE_RESULT")}")
        }
    }
}