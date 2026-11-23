package cn.dumate.a6studio

import cn.dumate.a6studio.ai.BlockPatch
import cn.dumate.a6studio.ai.BlockPatchOp
import cn.dumate.a6studio.ai.LlmClient
import cn.dumate.a6studio.ai.LlmConfig
import cn.dumate.a6studio.ai.LlmProviderId
import cn.dumate.a6studio.ai.LlmRefineInput
import cn.dumate.a6studio.bridge.DesktopMcpServer
import cn.dumate.a6studio.bridge.McpClient
import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.model.Templates
import cn.dumate.a6studio.model.newBlock
import cn.dumate.a6studio.security.Redaction
import cn.dumate.a6studio.security.UrlSecurity
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MergedFeaturesTest {

    @Test
    fun redactionMasksSecretsAndKeepsTokenCounters() {
        assertTrue(Redaction.isSensitiveKey("Authorization"))
        assertTrue(Redaction.isSensitiveKey("pt_key"))
        assertFalse(Redaction.isSensitiveKey("maxTokens"))
        assertFalse(Redaction.isSensitiveKey("prompt_tokens"))

        val payload = buildJsonObject {
            put("Authorization", "Bearer abcdefghijklmnop12345")
            put("maxTokens", 2048)
            put("note", "联系手机 13812345678，Key: sk-proj-1234567890abcdefghij12345")
        }
        val report = Redaction.redactTrafficPayload(payload)
        assertEquals(1, report.stats.redactedKeyCount)
        assertTrue(report.stats.maskedPatternCount >= 2)
        val outText = report.redactedPayload.toString()
        assertFalse(outText.contains("13812345678"))
        assertFalse(outText.contains("sk-proj-1234567890abcdefghij12345"))
        assertTrue(outText.contains("2048"))
    }

    @Test
    fun redactionHandlesHarHeadersAndCookieStrings() {
        // 1. 新增敏感键名校验
        for (k in listOf("key", "wskey", "pin", "h5st", "eid", "uuid", "openudid")) {
            assertTrue(Redaction.isSensitiveKey(k), "应识别敏感键名: $k")
        }

        // 2. HAR {name, value} 头部结构 + 嵌套字符串形式 JSON + Cookie 键值对 + 原始头部行 + Basic 认证
        val harPayload = buildJsonObject {
            put("headers", buildJsonArray {
                add(buildJsonObject {
                    put("name", "Cookie")
                    put("value", "pt_key=AAJsecret123; pt_pin=jd_user99;")
                })
                add(buildJsonObject {
                    put("name", "User-Agent")
                    put("value", "okhttp/4.9.0")
                })
            })
            put("rawCookieStr", "wskey=WSKEY_SECRET_999; pt_key=PT_SECRET_888; normal=1")
            put("rawHeaderLines", "Host: api.m.jd.com\nCookie: pt_key=LINE_SECRET;\nAuthorization: Basic dXNlcjpwYXNzd29yZA==")
            put("stringifiedJsonBody", """{"wskey":"INNER_WSKEY_111","h5st":"INNER_H5ST_222","safeField":"ok"}""")
        }

        val report = Redaction.redactTrafficPayload(harPayload)
        val out = report.redactedPayload.toString()

        assertFalse(out.contains("AAJsecret123"), "HAR Cookie value 应被脱敏: $out")
        assertFalse(out.contains("WSKEY_SECRET_999"), "cookie_pair wskey 应被脱敏: $out")
        assertFalse(out.contains("PT_SECRET_888"), "cookie_pair pt_key 应被脱敏: $out")
        assertFalse(out.contains("LINE_SECRET"), "header_line Cookie 应被脱敏: $out")
        assertFalse(out.contains("dXNlcjpwYXNzd29yZA=="), "Basic auth 应被脱敏: $out")
        assertFalse(out.contains("INNER_WSKEY_111"), "字符串形式 JSON 内部的 wskey 应被递归脱敏: $out")
        assertFalse(out.contains("INNER_H5ST_222"), "字符串形式 JSON 内部的 h5st 应被递归脱敏: $out")
        assertTrue(out.contains("okhttp/4.9.0"), "非敏感 Header 应保留")
        assertTrue(out.contains("safeField"), "字符串形式 JSON 内部的非敏感字段应保留")

        // 3. 超大字符串 (>64KB) 先截断到 64KB 再脱敏
        val hugeInput = "a".repeat(70_000) + " pt_key=TAIL_SECRET"
        val hugeRes = Redaction.redactTextPatterns(hugeInput, maxStringLength = 70_000)
        assertTrue(hugeRes.truncated, "超过 64KB 的输入应标记为 truncated")
        assertFalse(hugeRes.text.contains("TAIL_SECRET"), "64KB 之后的内容应已被预先截断")
    }

    @Test
    fun exportSecretScanCatchesPlaintextKeys() {
        val clean = "log('hello');"
        assertTrue(Redaction.assertNoPlaintextSecrets(clean).isEmpty())

        val leaky = "var key = 'AIzaSyDummyKeyForTest1234567890abcdef';"
        val findings = Redaction.assertNoPlaintextSecrets(leaky)
        assertTrue(findings.contains("Google API Key"))
    }

    @Test
    fun urlSecurityBlocksSsrfMetadataHosts() {
        assertFalse(UrlSecurity.validateEndpointUrl("http://169.254.169.254/latest/meta-data").valid)
        assertFalse(UrlSecurity.validateEndpointUrl("http://[::ffff:169.254.169.254]/").valid)
        assertFalse(UrlSecurity.validateEndpointUrl("http://metadata.google.internal/").valid)
        assertFalse(UrlSecurity.validateEndpointUrl("http://127.0.0.1:11434/v1", allowLocal = false).valid)
        assertTrue(UrlSecurity.validateEndpointUrl("http://127.0.0.1:11434/v1", allowLocal = true).valid)
        assertTrue(UrlSecurity.validateEndpointUrl("https://api.openai.com/v1", allowLocal = false).valid)
    }

    @Test
    fun llmClientFallsBackToDeterministicRefinerWithRedactedTraffic() = runBlocking {
        val http = HttpClient(CIO)
        try {
            val client = LlmClient(http)
            val traffic = buildJsonObject {
                put("flows", buildJsonArray {
                    add(buildJsonObject {
                        put("path", "/api/jd/tasks")
                        put("headers", buildJsonObject { put("Cookie", "pt_key=secret123;") })
                        put("responseBody", buildJsonObject {
                            put("data", buildJsonObject {
                                put("tasks", buildJsonArray {
                                    add(buildJsonObject {
                                        put("buttonText", "去逛逛")
                                        put("status", "TODO")
                                        put("browseSeconds", 18)
                                    })
                                    add(buildJsonObject {
                                        put("buttonText", "已完成")
                                        put("status", "DONE")
                                        put("browseSeconds", 5)
                                    })
                                })
                            })
                        })
                    })
                })
            }
            val out = client.refine(
                LlmRefineInput(
                    goal = "提取任务按钮",
                    workflowName = "京东测试",
                    appPackage = "com.jingdong.app.mall",
                    fallbackKeywords = listOf("去完成"),
                    proxypinTraffic = traffic,
                    provider = LlmProviderId.AUTO
                ),
                LlmConfig(allowLocalInference = false)
            )
            assertEquals(LlmProviderId.DETERMINISTIC_RULES, out.providerUsed)
            assertTrue(out.refinedKeywords.contains("去逛逛"))
            assertFalse(out.refinedKeywords.contains("已完成"))
            assertEquals(20_000, out.recommendedBrowseWaitMs)
            assertTrue(out.redactionStats.redactedKeyCount >= 1)
        } finally {
            http.close()
        }
    }

    @Test
    fun llmRefinePropagatesCancellation() = runBlocking {
        // 起一个收到请求后延迟响应的本地 HttpServer，在协程取消时验证 CancellationException 被原样抛出，而不是降级返回确定性结果
        val slowServer = com.sun.net.httpserver.HttpServer.create(
            java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 0), 0
        )
        slowServer.createContext("/v1/chat/completions") { ex ->
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
            } finally {
                runCatching { ex.close() }
            }
        }
        slowServer.start()
        val port = slowServer.address.port
        val http = HttpClient(CIO)
        try {
            val client = LlmClient(http)
            var caughtCancellation = false
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                try {
                    client.refine(
                        LlmRefineInput(
                            goal = "测试取消",
                            workflowName = "取消测试",
                            appPackage = "com.jingdong.app.mall",
                            provider = LlmProviderId.AUTO
                        ),
                        LlmConfig(
                            openAiBaseUrl = "http://127.0.0.1:$port/v1",
                            openAiApiKey = "sk-test-dummy-key",
                            allowLocalInference = true
                        )
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    caughtCancellation = true
                    throw e
                }
            }
            kotlinx.coroutines.delay(150)
            job.cancel()
            job.join()
            assertTrue(caughtCancellation, "协程取消时 LlmClient.refine 必须重抛 CancellationException，不能吞掉降级")
        } finally {
            http.close()
            slowServer.stop(0)
        }
    }

    @Test
    fun blockPatchGuardsAgainstDestructiveEditsAndCodeInjection() {
        val proj = Templates.byId("jdServiceKit")!!.build()
        val bootBlock = proj.root.children["body"]!!.first { it.type == "kitBoot" }
        val finishBlock = proj.root.children["body"]!!.first { it.type == "kitFinish" }

        // 1. 删除护栏积木必须被拒绝
        val delRes = BlockPatch.guardAndApply(proj, listOf(BlockPatchOp.RemoveBlock(bootBlock.id)))
        assertFalse(delRes.accepted)
        assertTrue(delRes.errors.any { it.contains("kitBoot") })

        // 2. 强制修改 finish 结果为 success 必须被拒绝
        val forceRes = BlockPatch.guardAndApply(
            proj,
            listOf(BlockPatchOp.UpdateParam(finishBlock.id, "result", ParamValue.Text("success")))
        )
        assertFalse(forceRes.accepted)

        // 3. 参数注入可执行代码必须被拒绝
        val injectRes = BlockPatch.guardAndApply(
            proj,
            listOf(BlockPatchOp.AddBlock(newBlock("kitClick", mapOf("value" to ParamValue.Text("eval('alert(1)')")))))
        )
        assertFalse(injectRes.accepted)

        // 4. 合法修改项目名与添加普通点击应通过
        val okRes = BlockPatch.guardAndApply(
            proj,
            listOf(BlockPatchOp.RenameProject("新京东任务"))
        )
        assertTrue(okRes.accepted)
        assertEquals("新京东任务", okRes.project.name)
    }

    @Test
    fun blockPatchRejectsRawJsAndExprAndNested() {
        val proj = Templates.byId("jdServiceKit")!!.build()

        // 1. 禁止顶层 rawJs
        val rawRes = BlockPatch.guardAndApply(
            proj,
            listOf(BlockPatchOp.AddBlock(newBlock("rawJs", mapOf("code" to ParamValue.Text("toast(1);")))))
        )
        assertFalse(rawRes.accepted)
        assertTrue(rawRes.errors.any { it.contains("rawJs") })

        // 2. 禁止嵌套在 children 里的 rawJs
        val nestedRaw = newBlock("forRange", mapOf("i" to ParamValue.Text("i"), "n" to ParamValue.Num(2.0))).copy(
            children = mapOf("body" to listOf(newBlock("rawJs", mapOf("code" to ParamValue.Text("exit();")))))
        )
        val nestedRes = BlockPatch.guardAndApply(proj, listOf(BlockPatchOp.AddBlock(nestedRaw)))
        assertFalse(nestedRes.accepted)
        assertTrue(nestedRes.errors.any { it.contains("rawJs") })

        // 3. 禁止写入 EXPR 参数（如 varSet.expr）与非法标识符（varSet.name）
        val exprRes = BlockPatch.guardAndApply(
            proj,
            listOf(BlockPatchOp.AddBlock(newBlock("varSet", mapOf("name" to ParamValue.Text("x=eval(1)"), "expr" to ParamValue.Text("1")))))
        )
        assertFalse(exprRes.accepted)
        assertTrue(exprRes.errors.any { it.contains("表达式/代码参数") || it.contains("不是合法标识符") })

        // 4. 目标积木不存在时报错，绝不静默成功
        val missingRes = BlockPatch.guardAndApply(
            proj,
            listOf(BlockPatchOp.UpdateParam("non_existent_block_id", "value", ParamValue.Text("去完成")))
        )
        assertFalse(missingRes.accepted)
        assertTrue(missingRes.errors.any { it.contains("不存在") })

        // 5. SELECT 非法值与 NUMBER 非数字类型应被拒绝
        val badSelectRes = BlockPatch.guardAndApply(
            proj,
            listOf(BlockPatchOp.AddBlock(newBlock("kitClick", mapOf("by" to ParamValue.Text("invalid_by"), "value" to ParamValue.Text("签到")))))
        )
        assertFalse(badSelectRes.accepted)

        val badNumRes = BlockPatch.guardAndApply(
            proj,
            listOf(BlockPatchOp.AddBlock(newBlock("sleep", mapOf("ms" to ParamValue.Text("not_a_num")))))
        )
        assertFalse(badNumRes.accepted)

        // 6. CodeGen 对非法标识符强制生成注释而不是可执行代码
        val badIdentCode = cn.dumate.a6studio.catalog.CodeGen.blockCode(
            newBlock("varSet", mapOf("name" to ParamValue.Text("a; eval(1); var b"), "expr" to ParamValue.Text("1"))),
            proj
        )
        assertTrue(badIdentCode.trim().startsWith("// 非法标识符已跳过"), "CodeGen 应将非法标识符降级为注释: $badIdentCode")
    }

    @Test
    fun desktopMcpServerRoundTripWorksOverHttp() = runBlocking {
        var currentProject = Templates.byId("jdServiceKit")!!.build()
        val server = DesktopMcpServer(
            getProject = { currentProject },
            setProject = { currentProject = it },
            port = 0
        )
        server.start()
        val http = HttpClient(CIO)
        try {
            val client = McpClient(http)
            client.setUrl(server.endpointUrl)
            client.setAuth(server.authToken)

            val toolsText = client.listTools().toString()
            assertTrue(toolsText.contains("studio_apply_patch"))

            val genRes = client.callTool("studio_generate_code")
            assertTrue(genRes.text.contains("Kit.boot("))

            val patchArgs = buildJsonObject {
                put("ops", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "cn.dumate.a6studio.ai.BlockPatchOp.RenameProject")
                        put("name", "MCP更新后项目")
                    })
                })
            }
            val patchRes = client.callTool("studio_apply_patch", patchArgs)
            assertTrue(patchRes.text.contains("MCP更新后项目"))
            assertEquals("MCP更新后项目", currentProject.name)
        } finally {
            http.close()
            server.stop()
        }
    }

    @Test
    fun mcpServerRejectsMissingTokenAndForeignOrigin() = runBlocking {
        var currentProject = Templates.byId("jdServiceKit")!!.build()
        val server = DesktopMcpServer(
            getProject = { currentProject },
            setProject = { currentProject = it },
            port = 0
        )
        server.start()
        val http = HttpClient(CIO) { expectSuccess = false }
        try {
            val validPayload = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""

            // 1. 无令牌 → 401
            val noTokenResp = http.post(server.endpointUrl) {
                contentType(ContentType.Application.Json)
                setBody(validPayload)
            }
            assertEquals(401, noTokenResp.status.value)

            // 2. 带 Origin 头 → 403
            val originResp = http.post(server.endpointUrl) {
                header("Authorization", "Bearer ${server.authToken}")
                header("Origin", "https://evil.example.com")
                contentType(ContentType.Application.Json)
                setBody(validPayload)
            }
            assertEquals(403, originResp.status.value)

            // 3. Content-Type: text/plain → 415
            val plainResp = http.post(server.endpointUrl) {
                header("Authorization", "Bearer ${server.authToken}")
                contentType(ContentType.Text.Plain)
                setBody(validPayload)
            }
            assertEquals(415, plainResp.status.value)

            // 4. 超大请求体 (>1MB) → 413
            val hugeBody = "{" + "\"a\":\"" + "x".repeat(DesktopMcpServer.MAX_BODY_BYTES + 128) + "\"}"
            val hugeResp = http.post(server.endpointUrl) {
                header("Authorization", "Bearer ${server.authToken}")
                contentType(ContentType.Application.Json)
                setBody(hugeBody)
            }
            assertEquals(413, hugeResp.status.value)

            // 5. 畸形 method（非字符串）→ 返回 200 且包含 JSON-RPC error (-32600)
            val malformedResp = http.post(server.endpointUrl) {
                header("Authorization", "Bearer ${server.authToken}")
                contentType(ContentType.Application.Json)
                setBody("""{"jsonrpc":"2.0","id":9,"method":{}}""")
            }
            assertEquals(200, malformedResp.status.value)
            val malformedText = malformedResp.bodyAsText()
            assertTrue(malformedText.contains("\"error\"") && malformedText.contains("-32600"), "应返回 JSON-RPC -32600 错误: $malformedText")
        } finally {
            http.close()
            server.stop()
        }
    }

    @Test
    fun diagnoseAndAuditSupportKitBlocks() {
        val http = HttpClient(CIO)
        try {
            // 1. 缺少 kitBoot 且 kitClick value 为空的工程应被 Diagnose 检出
            val badProj = cn.dumate.a6studio.model.Samples.emptyProject().let {
                it.copy(
                    root = it.root.copy(
                        children = mapOf(
                            "body" to listOf(
                                newBlock("kitClick", mapOf("by" to ParamValue.Text("text"), "match" to ParamValue.Text("contains"), "value" to ParamValue.Text("")))
                            )
                        )
                    )
                )
            }
            val issues = cn.dumate.a6studio.diagnose.Diagnose.scan(badProj)
            assertTrue(issues.any { it.title.contains("护栏控件定位值为空") })
            assertTrue(issues.any { it.title.contains("缺少「初始化护栏运行时」") })

            // 2. AuditController 应能将 kitWaitFor / kitClick 的 (by, match) 合成为 textContains / descContains
            val serviceProj = Templates.byId("jdServiceKit")!!.build()
            val audit = cn.dumate.a6studio.audit.AuditController(McpClient(http)) { _, _ -> }
            val rows = audit.collect(serviceProj)
            assertTrue(rows.any { it.by == "textContains" && it.value == "天天签到" })
            assertTrue(rows.any { it.by == "text" && it.value == "立即签到" })
        } finally {
            http.close()
        }
    }

    @Test
    fun flowIrImporterImportsJingdouAndOmniForgeJson() {
        val jingdouJson = """
            {
              "irVersion": "0.1",
              "id": "jd_test",
              "name": "测试京东IR导入",
              "target": { "pkg": "com.jingdong.app.mall" },
              "vars": { "SIGN_TEXT": "天天签到" },
              "policy": { "maxRuntimeMs": 600000 },
              "flow": [
                { "id": "n1", "kind": "launch", "label": "启动京东" },
                { "id": "n2", "kind": "click", "selector": { "by": "text", "match": "contains", "value": "${'$'}{SIGN_TEXT}" } },
                { "id": "n3", "kind": "finish", "result": "auto" }
              ]
            }
        """.trimIndent()
        val res1 = cn.dumate.a6studio.importer.ScriptImporter.import(jingdouJson, "fallback")
        assertEquals("测试京东IR导入", res1.project.name)
        val body1 = res1.project.root.children["body"].orEmpty()
        assertTrue(body1.any { it.type == "kitBoot" })
        assertTrue(body1.any { it.type == "kitClick" && it.params["value"]?.asText() == "天天签到" })

        val omniJson = """
            {
              "id": "wf_1",
              "name": "测试OmniForge导入",
              "appPackage": "com.jingdong.app.mall",
              "nodes": [
                { "id": "1", "type": "launch_app", "label": "拉起应用", "enabled": true, "config": {} },
                { "id": "2", "type": "click_target", "label": "点击签到", "enabled": true, "config": { "keyword": "立即签到" } }
              ]
            }
        """.trimIndent()
        val res2 = cn.dumate.a6studio.importer.ScriptImporter.import(omniJson, "fallback2")
        assertEquals("测试OmniForge导入", res2.project.name)
        val body2 = res2.project.root.children["body"].orEmpty()
        assertTrue(body2.any { it.type == "kitClick" && it.params["value"]?.asText() == "立即签到" })
    }
}
