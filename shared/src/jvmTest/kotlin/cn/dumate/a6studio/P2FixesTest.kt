package cn.dumate.a6studio

import cn.dumate.a6studio.ai.BlockPatch
import cn.dumate.a6studio.ai.BlockPatchOp
import cn.dumate.a6studio.ai.LlmProviderId
import cn.dumate.a6studio.bridge.BridgeException
import cn.dumate.a6studio.bridge.McpClient
import cn.dumate.a6studio.bridge.McpConfigParser
import cn.dumate.a6studio.bridge.McpImportCheck
import cn.dumate.a6studio.bridge.SseAccumulator
import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.diagnose.Diagnose
import cn.dumate.a6studio.diagnose.Level
import cn.dumate.a6studio.importer.ScriptImporter
import cn.dumate.a6studio.model.Block
import cn.dumate.a6studio.model.MemoryProjectStorage
import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.model.ParamValueSerializer
import cn.dumate.a6studio.model.Project
import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.model.StudioModule
import cn.dumate.a6studio.model.newBlock
import cn.dumate.a6studio.platform.Platform
import cn.dumate.a6studio.runtime.Aj6KitRuntime
import cn.dumate.a6studio.runtime.RhinoRunEngine
import cn.dumate.a6studio.runtime.SimRuntime
import cn.dumate.a6studio.security.Redaction
import cn.dumate.a6studio.security.UrlSecurity
import cn.dumate.a6studio.ui.BeginnerCopy
import cn.dumate.a6studio.ui.StudioState
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class P2FixesTest {

    @Test
    fun nonFiniteNumbersDoNotBreakProjectSave() {
        val json = Json { encodeDefaults = true }
        assertEquals("\"Infinity\"", json.encodeToString(ParamValueSerializer, ParamValue.Num(Double.POSITIVE_INFINITY)))
        assertEquals("\"NaN\"", json.encodeToString(ParamValueSerializer, ParamValue.Num(Double.NaN)))
        // Finite numbers keep their existing encoding.
        assertEquals("1500.0", json.encodeToString(ParamValueSerializer, ParamValue.Num(1500.0)))

        val project = ScriptImporter.import("sleep(1/0);\nsleep(0/0);\nsleep(2*500);", "t").project
        // Must not throw; before the fix this was "Unexpected special floating-point value Infinity".
        val raw = json.encodeToString(Project.serializer(), project)
        json.decodeFromString(Project.serializer(), raw)
    }

    @Test
    fun mcpImportRejectsMetadataAddressesAndHeaderInjection() {
        assertNotNull(McpImportCheck.reject("http://169.254.169.254/mcp", ""))
        assertNotNull(McpImportCheck.reject("http://metadata.google.internal/mcp", ""))
        assertNotNull(McpImportCheck.reject("http://192.168.1.5:9637/mcp", "abc\r\nX-Evil: 1"))
        assertNull(McpImportCheck.reject("http://192.168.1.5:9637/mcp", "abc"))
        assertNull(McpImportCheck.reject("http://127.0.0.1:9637/mcp", ""))
    }

    @Test
    fun mcpClientRefusesMetadataUrlAndOversizedBodies() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { ex ->
            ex.requestBody.readBytes()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, 0) // chunked: no Content-Length to short-circuit on
            val chunk = ByteArray(64 * 1024) { ' '.code.toByte() }
            runCatching { ex.responseBody.use { out -> repeat(160) { out.write(chunk) } } } // 10MB
        }
        server.start()
        val http = HttpClient(CIO) { install(HttpTimeout) }
        try {
            val client = McpClient(http)
            client.setUrl("http://169.254.169.254/mcp")
            val blocked = runBlocking { runCatching { client.ping() } }.exceptionOrNull()
            assertContains(blocked?.message.orEmpty(), "元数据")

            client.setUrl("http://127.0.0.1:${server.address.port}/mcp")
            val big = runBlocking { runCatching { client.ping() } }.exceptionOrNull()
            assertContains(big?.message.orEmpty(), "上限")
        } finally {
            http.close()
            server.stop(0)
        }
    }

    @Test
    fun simulatorFileSystemAndStorageAreBounded() {
        val simFiles = SimRuntime()
        val files = try {
            simFiles.execute(
                """
                var s = 'x';
                for (var i = 0; i < 20; i++) s = s + s;   // 1M chars
                for (var j = 0; j < 20; j++) files.write('/sdcard/f' + j + '.txt', s);
                """.trimIndent()
            )
        } finally {
            simFiles.close()
        }
        assertEquals("error", files.first, files.toString())
        assertContains(files.second.orEmpty(), "上限")

        val simStorage = SimRuntime()
        val storage = try {
            simStorage.execute(
                """
                var st = storages.create('t');
                for (var i = 0; i < 10001; i++) st.put('k' + i, i);
                """.trimIndent()
            )
        } finally {
            simStorage.close()
        }
        assertEquals("error", storage.first, storage.toString())
        assertContains(storage.second.orEmpty(), "上限")

        // Normal use is untouched.
        val simOk = SimRuntime()
        val ok = try {
            simOk.execute("files.write('/sdcard/a.txt', 'hi'); files.append('/sdcard/a.txt', '!');")
        } finally {
            simOk.close()
        }
        assertNotEquals("error", ok.first, ok.toString())
    }

    @Test
    fun funcNameAndCommentsCannotEscapeIntoCode() {
        val proj = Samples.emptyProject("p */ evil(); /*").copy(
            modules = listOf(StudioModule(id = "m1", name = "m", desc = "d */ evil2(); /*"))
        )
        val fn = CodeGen.blockCode(
            newBlock("funcDef", mapOf("name" to ParamValue.Text("f() {} evil(); function g"))), proj
        )
        assertContains(fn, "// 非法标识符已跳过")
        assertFalse(fn.contains('\n'), fn)

        val code = CodeGen.code(proj)
        assertFalse(code.contains("*/ evil"), code)
        assertContains(code, "* / evil();")
        assertContains(code, "* / evil2();")
    }

    @Test
    fun blockPatchRejectsDuplicateIdGuardHijackAndExcessRuntime() {
        val boot = newBlock("kitBoot", mapOf("maxRuntimeMs" to ParamValue.Num(600_000.0)))
        val guard = newBlock("kitGuard")
        val finish = newBlock("kitFinish")
        val mod = StudioModule(id = "m_unused", name = "unused", children = mapOf("body" to emptyList()))
        val proj = Samples.emptyProject("kit").copy(
            root = Project.rootBlock().copy(children = mapOf("body" to listOf(boot, guard, finish))),
            modules = listOf(mod)
        )

        // 1. 禁止重复 ID / 空 ID
        val dupRes = BlockPatch.guardAndApply(
            proj,
            listOf(BlockPatchOp.AddBlock(newBlock("log").copy(id = boot.id)))
        )
        assertFalse(dupRes.accepted, "重复 ID 必须被拒绝")
        val emptyIdRes = BlockPatch.guardAndApply(
            proj,
            listOf(BlockPatchOp.AddBlock(newBlock("log").copy(id = "   ")))
        )
        assertFalse(emptyIdRes.accepted, "空白 ID 必须被拒绝")

        // 2. 禁止将主程序 kitBoot 移花接木到未调用模块后删除主程序 kitBoot
        val hijackRes = BlockPatch.guardAndApply(
            proj,
            listOf(
                BlockPatchOp.AddBlock(newBlock("kitBoot"), parentId = mod.id, slot = "body"),
                BlockPatchOp.RemoveBlock(boot.id)
            )
        )
        assertFalse(hijackRes.accepted, "删除主程序中的 kitBoot 必须被拒绝")

        // 3. 禁止将 kitBoot 超时拉到超过 1 小时硬上限
        val hugeRuntimeRes = BlockPatch.guardAndApply(
            proj,
            listOf(
                BlockPatchOp.AddBlock(newBlock("kitBoot", mapOf("maxRuntimeMs" to ParamValue.Num(999_999_999.0))))
            )
        )
        assertFalse(hugeRuntimeRes.accepted, "超出硬上限的 kitBoot 超时必须被拒绝")

        // 4. 同批先新增再修改新增积木参数应允许
        val added = newBlock("kitClick", mapOf("value" to ParamValue.Text("旧文案")))
        val seqRes = BlockPatch.guardAndApply(
            proj,
            listOf(
                BlockPatchOp.AddBlock(added),
                BlockPatchOp.UpdateParam(added.id, "value", ParamValue.Text("新文案"))
            )
        )
        assertTrue(seqRes.accepted, "同批先增后改应被允许: ${seqRes.errors}")
        assertEquals("新文案", BlockPatch.locateBlock(seqRes.project, added.id)?.params?.get("value")?.asText())

        // 5. 已有 1 个凭据时，新增第 2 个同类型凭据应因频次增加被拦截
        val withSecret = Samples.emptyProject("sec").copy(
            root = Project.rootBlock().copy(
                children = mapOf(
                    "body" to listOf(
                        newBlock("log", mapOf("text" to ParamValue.Text("AKIA1234567890ABCDEF")))
                    )
                )
            )
        )
        val addSecondSecret = BlockPatch.guardAndApply(
            withSecret,
            listOf(BlockPatchOp.AddBlock(newBlock("log", mapOf("text" to ParamValue.Text("AKIA0987654321FEDCBA")))))
        )
        assertFalse(addSecondSecret.accepted, "新增同类明文凭据应被频次差值拦截")
    }

    @Test
    fun diagnoseValidatesKitRegexAndCatalogUsesCorrectSignatures() {
        val proj = Samples.emptyProject("diag").copy(
            root = Project.rootBlock().copy(
                children = mapOf(
                    "body" to listOf(
                        newBlock("kitBoot"),
                        newBlock(
                            "kitWaitFor",
                            mapOf(
                                "by" to ParamValue.Text("text"),
                                "match" to ParamValue.Text("regex"),
                                "value" to ParamValue.Text("[unclosed")
                            )
                        ),
                        newBlock("kitFinish")
                    )
                )
            )
        )
        val issues = Diagnose.scan(proj)
        assertTrue(
            issues.any { it.level == Level.ERROR && it.title.contains("正则表达式无效") },
            "非法 kitWaitFor 正则应被静态诊断拦截: $issues"
        )

        val empty = Samples.emptyProject()
        assertEquals(
            "gesture(320, [2, device.height * 0.5], [device.width * 0.65, device.height * 0.5]);",
            CodeGen.blockCode(newBlock("edgeBackGesture"), empty)
        )
        assertContains(
            CodeGen.blockCode(newBlock("jdCrossApp"), empty),
            "gesture(320, [2, device.height * 0.5], [device.width * 0.65, device.height * 0.5]);"
        )
        assertEquals("back();", CodeGen.blockCode(newBlock("pressKey", mapOf("key" to ParamValue.Text("back"))), empty))
        assertEquals("home();", CodeGen.blockCode(newBlock("pressKey", mapOf("key" to ParamValue.Text("home"))), empty))
        assertEquals("recents();", CodeGen.blockCode(newBlock("pressKey", mapOf("key" to ParamValue.Text("recents"))), empty))
        assertEquals("KeyCode(\"KEYCODE_MENU\");", CodeGen.blockCode(newBlock("pressKey", mapOf("key" to ParamValue.Text("menu"))), empty))

        // launchPackage 导入时不再平白多出 sleep(1500)
        val imp = ScriptImporter.import("app.launchPackage(\"com.jd.jrapp\");", "imp")
        val impCode = CodeGen.code(imp.project, markers = false)
        assertContains(impCode, "app.launchPackage(\"com.jd.jrapp\");")
        assertFalse(impCode.contains("sleep(1500)"), "导入 launchPackage 不应凭空增加 sleep(1500): $impCode")

        // 跨行反引号模板字符串内嵌的语句不应被拆成独立积木
        val tplSrc = "let s = `line1\nsleep(100);\nline3`;"
        val tplImp = ScriptImporter.import(tplSrc, "tpl")
        assertEquals(0, tplImp.mappedCount, "模板字符串内的 sleep 不应被提取为独立积木")
    }

    @Test
    fun simulatorTruthyNegativeSleepAndStorageCharCap() {
        val sim1 = SimRuntime()
        try {
            val rUndef = sim1.execute("assert(undefined, 'undef');")
            assertEquals("error", rUndef.first, "assert(undefined) 必须失败")
            val rNan = sim1.execute("assert(NaN, 'nan');")
            assertEquals("error", rNan.first, "assert(NaN) 必须失败")
            val rSleep = sim1.execute("sleep(100); sleep(-5000);")
            assertEquals("finished", rSleep.first, rSleep.toString())
            assertEquals(100L, sim1.state.clock, "负数 sleep 不得倒扣虚拟时钟")
            val rFindOnce = sim1.execute("assert(text('首页').findOnce() != null, 'findOnce');")
            assertEquals("finished", rFindOnce.first, rFindOnce.toString())
        } finally {
            sim1.close()
        }

        val simCap = SimRuntime()
        try {
            val rCap = simCap.execute(
                """
                var s = 'a';
                for (var i = 0; i < 20; i++) s = s + s;   // 1M chars
                var st = storages.create('big');
                for (var j = 0; j < 20; j++) st.put('k' + j, s);
                """.trimIndent()
            )
            assertEquals("error", rCap.first, rCap.toString())
            assertContains(rCap.second.orEmpty(), "上限")
        } finally {
            simCap.close()
        }
    }

    @Test
    fun sseSplitCrlfBoundaryAndUrlBlockCode() {
        val acc = SseAccumulator()
        val part1 = "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\r\n\r".toByteArray()
        val part2 = "\n".toByteArray()
        assertNull(acc.feed(part1, part1.size))
        val payload = acc.feed(part2, part2.size)
        assertNotNull(payload, "跨 chunk 的 \\r\\n\\r + \\n 帧边界必须能被识别")
        assertContains(payload, "\"id\":1")

        val http = HttpClient(CIO) { install(HttpTimeout) }
        try {
            val client = McpClient(http)
            client.setUrl("http://169.254.169.254/mcp")
            val err = runBlocking { runCatching { client.ping() } }.exceptionOrNull() as? BridgeException
            assertNotNull(err)
            assertEquals("INVALID_URL", err.code)
        } finally {
            http.close()
        }
    }

    @Test
    fun redactionSuffixHarKeyAndMcpConfigParserFixes() {
        val txt = Redaction.redactTextPatterns("{\"passwordHash\":\"abc123\",\"secretKey\":\"xyz789\"}").text
        assertFalse(txt.contains("abc123"), txt)
        assertFalse(txt.contains("xyz789"), txt)

        val har = buildJsonObject {
            put("key", "Authorization")
            put("value", "Bearer secret-token-123")
        }
        val rep = Redaction.redactTrafficPayload(har).redactedPayload as JsonObject
        assertEquals("Authorization", (rep["key"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("[REDACTED]", (rep["value"] as? JsonPrimitive)?.contentOrNull)

        val leaked = Redaction.assertNoPlaintextSecrets("Bearer abc+/=1234567890 and AKIA1234567890ABCDEF")
        assertTrue(leaked.any { it.contains("Bearer") }, leaked.toString())
        assertTrue(leaked.any { it.contains("AWS") }, leaked.toString())

        val parsed = McpConfigParser.parse(
            """
            http://192.168.1.9:9637/mcp YWJjZGVmMTIzNDU2==
            http://192.168.1.10:9637/mcp
            token=abc:def=ghi
            """.trimIndent()
        )
        assertEquals(2, parsed.size, parsed.toString())
        assertEquals("YWJjZGVmMTIzNDU2==", parsed[0].token)
        assertEquals("abc:def=ghi", parsed[1].token)

        val httpCheck = UrlSecurity.validateEndpointUrl(
            "http://api.example.com/v1",
            allowLocal = false,
            requireHttpsForPublic = true
        )
        assertFalse(httpCheck.valid, "公网带 Key 请求必须拒绝明文 http://")
    }

    @Test
    fun beginnerCopyEmptyModuleAndAj6KitOcrAndRiskyGuard() {
        val title = BeginnerCopy.issueTitle("模块「测试」是空的")
        assertEquals("有一个模块是空的，调用它什么也不会发生", title)

        val sim = SimRuntime()
        try {
            val script = Aj6KitRuntime.SOURCE + "\n" + """
                var k = Kit.boot({
                    id: 't', name: 't', pkg: 'com.jingdong.app.mall',
                    policy: { riskyActivityRe: 'PayActivity' }
                });
                // 1. OCR 结果仅提供 label（与 SimRuntime / AutoJs6 一致）也能被 ocrFind 命中
                var hit = k.ocrFind('首页');
                assert(hit != null && hit.t === '首页', 'ocrFind 应支持 label 字段');

                // 2. 危险页连续 4 次 back 仍未退出时 guard() 必须返回 false
                currentActivity = function () { return 'com.jingdong.app.mall.PayActivity'; };
                back = function () { return true; };
                assert(k.guard() === false, '无法退出危险页时 guard() 必须返回 false');
            """.trimIndent()
            val (status, err) = sim.execute(script)
            assertEquals("finished", status, "Aj6Kit 测试失败: $err")
        } finally {
            sim.close()
        }
    }

    private class MemPlatform : Platform {
        override val name = "test"
        override val supportsFileDialogs = false
        val files = mutableMapOf<String, String>()
        override fun readTextFile(path: String): String? = files[path]
        override fun writeTextFile(path: String, content: String): Boolean {
            files[path] = content
            return true
        }
        override suspend fun saveTextAs(suggestedName: String, content: String, mimeType: String): String? = null
        override suspend fun openTextFile(extensions: List<String>): Pair<String, String>? = null
        override fun copyToClipboard(text: String) {}
        override fun setWindowTitle(title: String) {}
    }

    @Test
    fun bridgeAndAiConfigsPersistAcrossRestartAndProjectSwitch() {
        val platform = MemPlatform()
        val storage = MemoryProjectStorage()
        val s1 = StudioState(platform, storage, CIO, RhinoRunEngine)
        try {
            s1.init()
            assertTrue(s1.rememberToken, "首次运行应默认开启记住令牌")
            s1.updateBridgeUrl("http://192.168.1.55:9637/mcp")
            s1.updateBridgeToken("tok-autojs-001")
            // 修改地址时保留当前已填令牌并绑定到新地址，不清空
            s1.updateBridgeUrl("http://192.168.1.66:9637/mcp")
            assertEquals("tok-autojs-001", s1.bridgeToken)

            // 切空项目/模板不得冲掉已保存的真机地址
            s1.newProject()
            assertEquals("http://192.168.1.66:9637/mcp", s1.bridgeUrl)

            // AI 抓包与 LLM 配置实时保存
            s1.updateProxyPinUrl("http://192.168.1.66:9127/mcp")
            s1.updateProxyPinToken("tok-proxypin-002")
            s1.updateProxyPinDomainFilter("api.jd.com")
            s1.updateLlmProvider(LlmProviderId.OPENAI_COMPATIBLE)
            s1.updateLlmConfig(
                s1.llmConfig.copy(
                    openAiBaseUrl = "https://api.example.com/v1",
                    openAiApiKey = "sk-test-openai-key",
                    openAiModel = "test-model"
                )
            )
            // 凭据绝不写入非敏感配置文件或工程文件
            assertFalse(platform.files["a6studio.ai.v1"].orEmpty().contains("tok-proxypin-002"))
            assertFalse(platform.files["a6studio.ai.v1"].orEmpty().contains("sk-test-openai-key"))
            assertFalse(storage.loadRaw().orEmpty().contains("tok-autojs-001"))

            // 关闭再重新开启「记住令牌」不得清空刚填的令牌
            s1.changeRememberToken(false)
            assertEquals("tok-autojs-001", s1.bridgeToken)
            assertEquals("{}", platform.files["a6studio.tokens.v1"])
            assertEquals("{}", platform.files["a6studio.tokens.ai.v1"])
            s1.changeRememberToken(true)
            assertEquals("tok-autojs-001", s1.bridgeToken)
            assertContains(platform.files["a6studio.tokens.v1"].orEmpty(), "tok-autojs-001")
            assertContains(platform.files["a6studio.tokens.ai.v1"].orEmpty(), "tok-proxypin-002")
        } finally {
            s1.dispose()
        }

        // 模拟杀进程重启：重建 StudioState 全部字段应完整恢复
        val s2 = StudioState(platform, storage, CIO, RhinoRunEngine)
        try {
            s2.init()
            assertEquals("http://192.168.1.66:9637/mcp", s2.bridgeUrl)
            assertEquals("tok-autojs-001", s2.bridgeToken)
            assertEquals("http://192.168.1.66:9127/mcp", s2.proxyPinUrl)
            assertEquals("tok-proxypin-002", s2.proxyPinToken)
            assertEquals("api.jd.com", s2.proxyPinDomainFilter)
            assertEquals(LlmProviderId.OPENAI_COMPATIBLE, s2.llmProvider)
            assertEquals("https://api.example.com/v1", s2.llmConfig.openAiBaseUrl)
            assertEquals("sk-test-openai-key", s2.llmConfig.openAiApiKey)
            assertEquals("test-model", s2.llmConfig.openAiModel)
        } finally {
            s2.dispose()
        }
    }

    @Test
    fun legacyAiConfigFileWithInlineSecretsMigratesCleanly() {
        val platform = MemPlatform()
        platform.files["a6studio.ai.v1"] = """
            {
              "version": 1,
              "mcpTransport": "http-jsonrpc",
              "proxyPinUrl": "http://192.168.1.17:9127/mcp",
              "proxyPinToken": "legacy-proxypin-token",
              "proxyPinDomainFilter": "ms.jr.jd.com",
              "llmProvider": "openai_compatible",
              "llmConfig": {
                "openAiBaseUrl": "http://192.168.1.12:3425/v1",
                "openAiApiKey": "sk-legacy-key",
                "openAiModel": "aws-quick/smart"
              }
            }
        """.trimIndent()
        val storage = MemoryProjectStorage()
        val state = StudioState(platform, storage, CIO, RhinoRunEngine)
        try {
            state.init()
            assertEquals("http://192.168.1.17:9127/mcp", state.proxyPinUrl)
            assertEquals("legacy-proxypin-token", state.proxyPinToken)
            assertEquals("ms.jr.jd.com", state.proxyPinDomainFilter)
            assertEquals(LlmProviderId.OPENAI_COMPATIBLE, state.llmProvider)
            assertEquals("http://192.168.1.12:3425/v1", state.llmConfig.openAiBaseUrl)
            assertEquals("sk-legacy-key", state.llmConfig.openAiApiKey)
            assertEquals("aws-quick/smart", state.llmConfig.openAiModel)
            // 迁移后旧文件中的明文凭据应被剥离并转入独立凭据文件
            assertFalse(platform.files["a6studio.ai.v1"].orEmpty().contains("legacy-proxypin-token"))
            assertFalse(platform.files["a6studio.ai.v1"].orEmpty().contains("sk-legacy-key"))
            assertContains(platform.files["a6studio.tokens.ai.v1"].orEmpty(), "legacy-proxypin-token")
            assertContains(platform.files["a6studio.tokens.ai.v1"].orEmpty(), "sk-legacy-key")
        } finally {
            state.dispose()
        }
    }
}
