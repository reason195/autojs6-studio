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
                LlmConfig()
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
