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
import cn.dumate.a6studio.catalog.CodeGen
import kotlin.test.assertContentEquals
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
    fun urlSecurityRejectsUserinfoAndNumericIp() {
        // N-M1 / N-T5：所有绕过写法都必须被拦截（allowLocal 默认 true 也一样拦元数据）
        val blocked = listOf(
            "http://x@169.254.169.254/latest/meta-data",       // userinfo
            "http://a:b@169.254.169.254/",                      // userinfo 带密码
            "http://2852039166/",                               // 十进制整数
            "http://0xA9FEA9FE/",                               // 十六进制
            "http://0251.0376.0251.0376/",                      // 八进制
            "http://169.254.43518/",                            // 缺段
            "http://[0:0:0:0:0:ffff:a9fe:a9fe]/",               // 完整写法 v4-mapped
            "http://[::169.254.169.254]/",                      // v4-compat
            "http://[fd00:ec2::254]/",                          // AWS IMDS IPv6
            "http://100.100.100.200/",                          // 阿里云元数据
            "http://metadata.google.internal./",                // 末尾带点
            "http://0.0.0.0/",
        )
        for (u in blocked) {
            assertFalse(UrlSecurity.validateEndpointUrl(u).valid, "应拦截：$u")
        }

        // allowLocal=false 时，IPv6 回环的各种写法都要识别
        for (u in listOf("http://[0:0:0:0:0:0:0:1]/", "http://[::ffff:7f00:1]/", "http://[::1]:8080/", "http://localhost./")) {
            assertFalse(UrlSecurity.validateEndpointUrl(u, allowLocal = false).valid, "应拦截本地：$u")
        }

        // 误判回归：fc/fd 开头的普通域名不是内网
        assertTrue(UrlSecurity.validateEndpointUrl("https://fdroid.org/api", allowLocal = false).valid)
        assertTrue(UrlSecurity.validateEndpointUrl("https://fcbarcelona.com/", allowLocal = false).valid)
        // 局域网 Ollama 在 allowLocal=true 时仍可用
        assertTrue(UrlSecurity.validateEndpointUrl("http://192.168.1.10:11434/v1", allowLocal = true).valid)
        assertTrue(UrlSecurity.validateEndpointUrl("http://[::1]:1234/v1", allowLocal = true).valid)
    }

    @Test
    fun urlSecurityParsesAddressesCorrectly() {
        assertContentEquals(byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte()), UrlSecurity.parseIpv4Loose("2852039166"))
        assertContentEquals(byteArrayOf(127, 0, 0, 1), UrlSecurity.parseIpv4Loose("127.1"))
        assertEquals(null, UrlSecurity.parseIpv4Loose("api.openai.com"))
        assertEquals(null, UrlSecurity.parseIpv4Loose("256.1.1.1"))
        val v6 = UrlSecurity.parseIpv6("::ffff:1.2.3.4")!!
        assertContentEquals(byteArrayOf(1, 2, 3, 4), v6.copyOfRange(12, 16))
        assertEquals(null, UrlSecurity.parseIpv6("1::2::3"))
        assertEquals(null, UrlSecurity.parseIpv6("fdroid.org"))
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
    fun funcDefArgsCannotSmuggleStatements() {
        val proj = Templates.byId("jdServiceKit")!!.build()
        val evil = "a) {} files.removeDir(\"/sdcard/Download\"); function z(b"

        // 补丁通道：新增和修改都要拒绝
        val fn = newBlock("funcDef", mapOf("name" to ParamValue.Text("doTask"), "args" to ParamValue.Text(evil)))
        val addRes = BlockPatch.guardAndApply(proj, listOf(BlockPatchOp.AddBlock(fn)))
        assertFalse(addRes.accepted, "funcDef.args 注入必须被拒绝")
        assertTrue(addRes.errors.any { it.contains("funcDef.args") }, addRes.errors.joinToString())

        val okFn = newBlock("funcDef", mapOf("name" to ParamValue.Text("doTask"), "args" to ParamValue.Text("task, n")))
        val okRes = BlockPatch.guardAndApply(proj, listOf(BlockPatchOp.AddBlock(okFn)))
        assertTrue(okRes.accepted, okRes.errors.joinToString())
        val updRes = BlockPatch.guardAndApply(
            okRes.project, listOf(BlockPatchOp.UpdateParam(okFn.id, "args", ParamValue.Text(evil)))
        )
        assertFalse(updRes.accepted)

        // 生成器兜底：即使工程文件里已经有这样的值，也不会拼出第二条语句；保留字参数同样丢弃
        val withEvil = proj.copy(
            root = proj.root.copy(children = proj.root.children + ("body" to proj.root.children["body"].orEmpty() + fn))
        )
        val code = CodeGen.code(withEvil)
        assertFalse(code.contains("function z("), code)
        assertFalse(code.contains("/sdcard/Download"), code)
        assertTrue(code.contains("function doTask() {"), code)
        assertEquals("a, b", cn.dumate.a6studio.catalog.Js.paramList("a, class, if, b, 1x"))
    }

    @Test
    fun redactionCoversFormBodiesJsonFragmentsAndSessionCookies() {
        val pw = "FAKEPW" + "123"
        // 表单首参数（前面没有 ? 或 &）
        val form = Redaction.redactTextPatterns("pass" + "word=$pw&user=bob").text
        assertFalse(form.contains(pw), form)
        assertTrue(form.contains("user=bob"), form)
        // 会话 cookie
        val cookie = Redaction.redactTextPatterns("PHPSESSID=abc123; JSESSIONID=XYZ789; theme=dark").text
        assertFalse(cookie.contains("abc123") || cookie.contains("XYZ789"), cookie)
        assertTrue(cookie.contains("theme=dark"), cookie)
        // 超过 64 KB 的 JSON 字符串不会被解析，靠片段规则按键名兜住；计数字段保持原样
        val big = buildString {
            append("{\"pass" + "word\":\"$pw\",\"max_tokens\":1024,\"tokenType\":\"bearer\",\"pad\":\"")
            append("x".repeat(70_000)).append("\"}")
        }
        val payload = buildJsonObject { put("responseBody", big) }
        val out = Redaction.redactTrafficPayload(payload).redactedPayload.toString()
        assertFalse(out.contains(pw), out.take(300))
        assertTrue(out.contains("1024") && out.contains("bearer"), out.take(300))
        // 未闭合的长转义串不能把正则引擎递归爆栈
        Redaction.redactTextPatterns("\"access_token\":\"" + "\\\\".repeat(30_000))
        for (k in listOf("JSESSIONID", "PHPSESSID", "X-Auth-Key", "accessKey", "ticket", "auth")) {
            assertTrue(Redaction.isSensitiveKey(k), "应识别敏感键名: $k")
        }
        assertFalse(Redaction.isSensitiveKey("author"))
    }

    @Test
    fun blockPatchIgnoresPreexistingExprAndRawJsBlocks() {
        // R-1 回归：工程里已有用户手工搭的 if(cond=true) 与 rawJs，补丁只碰 kit 积木时必须被接受
        val base = Templates.byId("jdServiceKit")!!.build()
        val userIf = newBlock("if", mapOf("cond" to ParamValue.Text("true")))
        val userRaw = newBlock("rawJs", mapOf("code" to ParamValue.Text("log(1);")))
        val proj = base.copy(
            root = base.root.copy(
                children = base.root.children + ("body" to base.root.children["body"].orEmpty() + userIf + userRaw)
            )
        )

        val okRes = BlockPatch.guardAndApply(
            proj,
            listOf(BlockPatchOp.AddBlock(newBlock("kitClick", mapOf("value" to ParamValue.Text("去完成")))))
        )
        assertTrue(okRes.accepted, okRes.errors.joinToString())

        // 改既有 if 的 cond 仍须拒绝
        val editRes = BlockPatch.guardAndApply(
            proj,
            listOf(BlockPatchOp.UpdateParam(userIf.id, "cond", ParamValue.Text("java.lang.Runtime.getRuntime()")))
        )
        assertFalse(editRes.accepted)

        // 往既有 if 里塞 rawJs 仍须拒绝
        val nestRes = BlockPatch.guardAndApply(
            proj,
            listOf(BlockPatchOp.AddBlock(newBlock("rawJs", mapOf("code" to ParamValue.Text("exit();"))), parentId = userIf.id, slot = "body"))
        )
        assertFalse(nestRes.accepted)
        assertTrue(nestRes.errors.any { it.contains("rawJs") })
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
                        put("type", "rename")
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

    @Test
    fun flowIrImporterKeepsPolicyAndMergesWithDefaults() {
        // N-M9：IR 的护栏策略必须带进生成代码，且只能加严
        val ir = """
            {
              "name": "策略透传",
              "policy": {
                "maxRuntimeMs": 300000,
                "skipKeywords": ["开会员"],
                "riskKeywords": ["滑块验证"],
                "riskyActivityRe": "WalletActivity",
                "forbiddenZones": [[0, 120]],
                "riskWaitMs": 60000,
                "evil": "ignored"
              },
              "flow": [ { "kind": "launch" } ]
            }
        """.trimIndent()
        val res = cn.dumate.a6studio.importer.FlowIrImporter.tryImport(ir, "fb")!!
        val code = CodeGen.code(res.project)
        assertTrue(code.contains("maxRuntimeMs: 300000"))
        assertTrue(code.contains("\"开会员\"") && code.contains("\"下单\""), "skipKeywords 应与默认值合并")
        assertTrue(code.contains("\"滑块验证\"") && code.contains("\"京东验证\""), "riskKeywords 应与默认值合并")
        assertTrue(code.contains("Settlement|Checkout") && code.contains("WalletActivity"), "危险页正则应为默认|导入")
        assertTrue(code.contains("[2130, 2400]") && code.contains("[0, 120]"))
        assertTrue(code.contains("riskWaitMs: 60000"))
        assertFalse(code.contains("evil"))

        // 空 policy 时与原默认输出等价
        val plain = cn.dumate.a6studio.catalog.KitPolicy.parse(null)
        assertEquals(cn.dumate.a6studio.catalog.KitPolicy.DEFAULT_RISKY_RE, plain.riskyActivityRe)
    }

    @Test
    fun flowIrImporterSurvivesMalformedInputAndDeepNesting() {
        // 畸形字段：都不能抛异常
        val malformed = """
            {
              "name": {},
              "vars": { "A": {}, "B": [1] },
              "target": { "pkg": [] },
              "policy": { "maxRuntimeMs": "abc", "skipKeywords": "notArray", "forbiddenZones": [[1], "x", [5, 2]] },
              "flow": [
                { "kind": {}, "label": [] },
                { "kind": "click", "selector": { "value": [] } },
                { "kind": "waitFor", "selector": [], "timeoutMs": {} },
                "notAnObject",
                { "kind": "probe", "label": "探测" },
                { "kind": "ocrClick" }
              ]
            }
        """.trimIndent()
        val res = cn.dumate.a6studio.importer.FlowIrImporter.tryImport(malformed, "fb")
        assertTrue(res != null, "畸形输入应能导入而不是抛异常")
        assertEquals("fb", res!!.project.name)
        assertTrue(res.rawCount >= 3, "未识别节点应计入 rawCount，实际 ${res.rawCount}")
        assertTrue(res.notes.any { it.contains("probe") && it.contains("ocrClick") }, "notes 应列出丢弃的节点: ${res.notes}")
        val body = res.project.root.children["body"].orEmpty()
        assertFalse(body.count { it.type == "kitGuard" } > 0, "未知节点不应再被静默替换成 kitGuard")
        CodeGen.code(res.project) // 生成代码也不能抛

        // 深层嵌套：5000 层不应 StackOverflowError
        val depth = 5000
        val deep = buildString {
            append("{\"flow\":[")
            repeat(depth) { append("{\"kind\":\"loop\",\"steps\":[") }
            append("{\"kind\":\"launch\"}")
            repeat(depth) { append("]}") }
            append("]}")
        }
        val deepRes = cn.dumate.a6studio.importer.FlowIrImporter.tryImport(deep, "deep")
        // kotlinx 解析器本身对极深 JSON 可能直接失败（返回 null 也可接受），关键是不能崩
        if (deepRes != null) assertTrue(deepRes.notes.any { it.contains("嵌套超过") })
    }

    /**
     * N-L11 regression：studio_get_project 改用 encodeToJsonElement + redactStrings 逐值脱敏后：
     * 1. 返回的 text 必须仍是合法 JSON（之前用 redactTextPatterns 对整段文本跑 header_line 正则，
     *    会把 "Cookie: ..." 一直吃到行尾，把大工程文本截断到 64 KB，两种情况都产生非法 JSON）。
     * 2. rawJs 积木里的明文 Cookie 字符串必须被脱敏（不含 pt_key=abc）。
     * 3. 超过 64 KB 的字符串值脱敏后仍留在 JSON 结构里（不会整段截断）。
     */
    @Test
    fun mcpGetProjectReturnsValidJsonAndRedactsSecrets() = runBlocking {
        // 构造含 rawJs 积木的工程（内含 Cookie 明文和一段超过 64 KB 的无害字符串）
        val sensitiveCode = "// Cookie: pt_key=abc; pt_pin=x\nlog('hello');"
        val bigPadding = "a".repeat(70_000)   // 超过 HARD_MAX_INPUT_CHARS=65536

        val base = cn.dumate.a6studio.model.Samples.emptyProject()
        val proj = base.copy(
            root = base.root.copy(
                children = mapOf(
                    "body" to listOf(
                        newBlock(
                            "rawJs",
                            mapOf("code" to ParamValue.Text(sensitiveCode))
                        ),
                        newBlock(
                            "comment",
                            mapOf("text" to ParamValue.Text(bigPadding))
                        )
                    )
                )
            )
        )

        val server = DesktopMcpServer(
            getProject = { proj },
            setProject = {},
            port = 0
        )
        server.start()
        val http = HttpClient(CIO)
        try {
            val client = McpClient(http)
            client.setUrl(server.endpointUrl)
            client.setAuth(server.authToken)

            val res = client.callTool("studio_get_project")

            // 1. 返回的 text 必须是合法 JSON
            val parsed = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(res.text)
            }
            assertTrue(parsed.isSuccess, "studio_get_project 返回的 text 必须是合法 JSON，实际长度=${res.text.length}")

            // 2. 敏感 Cookie 字段必须被脱敏
            assertFalse(res.text.contains("pt_key=abc"), "rawJs 中的 Cookie 值应被脱敏")

            // 3. 超大字符串值不应导致整段 JSON 被截断（工程名等字段必须仍然存在）
            assertTrue(res.text.contains("\"name\""), "工程名字段在脱敏后应仍存在（大字符串值不应截断整段 JSON）")
        } finally {
            http.close()
            server.stop()
        }
    }

    @Test
    fun dualMcpGatewaySupportsBothAutoJs6AndProxyPinWithRedaction() = runBlocking {
        val http = HttpClient(CIO)
        try {
            val gw = cn.dumate.a6studio.bridge.DualMcpGateway(http)

            // 1. Token 控制字符防注入校验
            val badTokenResp = gw.call(
                cn.dumate.a6studio.bridge.CallMcpGatewayOptions(
                    serverId = cn.dumate.a6studio.bridge.McpServerId.AUTOJS6,
                    transport = cn.dumate.a6studio.bridge.McpTransport.LOCAL_BRIDGE,
                    authToken = "bad\r\ntoken"
                )
            )
            assertFalse(badTokenResp.ok, "含 CR/LF 控制字符的 Token 必须被网关拒绝")
            assertEquals(cn.dumate.a6studio.bridge.McpErrorKind.PROTOCOL_ERROR, badTokenResp.errorKind)

            // 1b. G-6：__simulateToolError 在 devMode=false 时不生效，在 devMode=true 时生效
            val simBlocked = gw.call(
                cn.dumate.a6studio.bridge.CallMcpGatewayOptions(
                    serverId = cn.dumate.a6studio.bridge.McpServerId.AUTOJS6,
                    transport = cn.dumate.a6studio.bridge.McpTransport.LOCAL_BRIDGE,
                    method = "tools/call",
                    toolName = "get_device_state",
                    args = buildJsonObject { put("__simulateToolError", true) },
                    devMode = false
                )
            )
            assertTrue(simBlocked.ok, "devMode=false 时 __simulateToolError 钩子不应触发错误")
            assertTrue(simBlocked.isSynthetic, "LOCAL_BRIDGE 响应必须标记 isSynthetic=true")

            val simAllowed = gw.call(
                cn.dumate.a6studio.bridge.CallMcpGatewayOptions(
                    serverId = cn.dumate.a6studio.bridge.McpServerId.AUTOJS6,
                    transport = cn.dumate.a6studio.bridge.McpTransport.LOCAL_BRIDGE,
                    method = "tools/call",
                    toolName = "get_device_state",
                    args = buildJsonObject { put("__simulateToolError", true) },
                    devMode = true
                )
            )
            assertFalse(simAllowed.ok, "devMode=true 时 __simulateToolError 应触发模拟错误")
            assertEquals(cn.dumate.a6studio.bridge.McpErrorKind.TOOL_EXECUTION_ERROR, simAllowed.errorKind)
            assertTrue(simAllowed.isSynthetic)

            // 2. AutoJs6 MCP (LOCAL_BRIDGE)：get_device_state & capture_ui_tree
            val devResp = gw.call(
                cn.dumate.a6studio.bridge.CallMcpGatewayOptions(
                    serverId = cn.dumate.a6studio.bridge.McpServerId.AUTOJS6,
                    transport = cn.dumate.a6studio.bridge.McpTransport.LOCAL_BRIDGE,
                    method = "tools/call",
                    toolName = "get_device_state"
                )
            )
            assertTrue(devResp.ok)
            assertTrue(devResp.isSynthetic)
            assertTrue(devResp.data.toString().contains("visibleActionTexts"))
            assertTrue(devResp.data.toString().contains("去浏览15秒"))

            // 3. ProxyPin MCP (LOCAL_BRIDGE)：list_http_flows 自动脱敏 + analyze_endpoints
            val proxyResp = gw.call(
                cn.dumate.a6studio.bridge.CallMcpGatewayOptions(
                    serverId = cn.dumate.a6studio.bridge.McpServerId.PROXYPIN,
                    transport = cn.dumate.a6studio.bridge.McpTransport.LOCAL_BRIDGE,
                    method = "tools/call",
                    toolName = "list_http_flows",
                    args = buildJsonObject { put("domain", "ms.jr.jd.com") }
                )
            )
            assertTrue(proxyResp.ok)
            assertTrue(proxyResp.isSynthetic)
            val proxyDataStr = proxyResp.data.toString()
            assertFalse(proxyDataStr.contains("demo-session-token-9f8e7d6c5b4a3210"), "Bearer Token 必须已被网关脱敏")
            assertFalse(proxyDataStr.contains("demo_cookie_abc123"), "Cookie pt_key 必须已被网关脱敏")
            assertFalse(proxyDataStr.contains("13800138000"), "手机号必须已被网关脱敏")
            assertTrue((proxyResp.redactionStats?.redactedKeyCount ?: 0) >= 3)

            // 4. 双 MCP（autojsState + proxypinTraffic）联合送入 LlmClient.refine 提炼
            val llm = LlmClient(http)
            val refined = llm.refine(
                LlmRefineInput(
                    goal = "联合双 MCP 提炼任务按钮与浏览时长",
                    workflowName = "双MCP联调",
                    appPackage = "com.jingdong.app.mall",
                    autojsState = devResp.data,
                    proxypinTraffic = proxyResp.data,
                    provider = LlmProviderId.DETERMINISTIC_RULES
                ),
                LlmConfig(allowLocalInference = false)
            )
            assertTrue(refined.refinedKeywords.contains("领取奖励"))
            assertTrue(refined.refinedKeywords.contains("去浏览15秒"))
            assertFalse(refined.refinedKeywords.contains("已完成"))
            assertEquals(17_000, refined.recommendedBrowseWaitMs)
        } finally {
            http.close()
        }
    }

    @Test
    fun dualMcpGatewayOnlyDegradesOnMissingTool() {
        val gw = cn.dumate.a6studio.bridge.DualMcpGateway
        fun be(code: String, msg: String) = cn.dumate.a6studio.bridge.BridgeException(msg, code)
        // 工具/方法不存在 → 允许降级
        assertTrue(gw.isToolNotFoundError(be("RPC_ERROR", "Method not found: get_device_state")))
        assertTrue(gw.isToolNotFoundError(be("RPC_ERROR", "-32601")))
        assertTrue(gw.isToolNotFoundError(be("TOOL_ERROR", "unknown tool list_http_flows")))
        // 业务层「找不到」不是缺工具 → 不能降级
        assertFalse(gw.isToolNotFoundError(be("TOOL_ERROR", "node not found")))
        assertFalse(gw.isToolNotFoundError(be("TOOL_ERROR", "flow not found: flow_123")))
        assertFalse(gw.isToolNotFoundError(be("TOOL_ERROR", "element not found on screen")))
        // 非 RPC/TOOL 错误一律不降级
        assertFalse(gw.isToolNotFoundError(be("TIMEOUT", "Method not found")))
        assertFalse(gw.isToolNotFoundError(be("UNAUTHORIZED", "tool not found")))
    }

    @Test
    fun dualMcpGatewayHttpPathAndReviewFixes() = runBlocking {
        // ---- G-3：全部错误码映射断言 ----
        val expectedMappings = mapOf(
            "TIMEOUT" to cn.dumate.a6studio.bridge.McpErrorKind.TIMEOUT_ERROR,
            "INVALID_URL" to cn.dumate.a6studio.bridge.McpErrorKind.SSRF_BLOCKED,
            "BAD_RESPONSE" to cn.dumate.a6studio.bridge.McpErrorKind.PROTOCOL_ERROR,
            "EMPTY_BODY" to cn.dumate.a6studio.bridge.McpErrorKind.PROTOCOL_ERROR,
            "EMPTY_RESPONSE" to cn.dumate.a6studio.bridge.McpErrorKind.PROTOCOL_ERROR,
            "RPC_ERROR" to cn.dumate.a6studio.bridge.McpErrorKind.PROTOCOL_ERROR,
            "INVALID_ARGUMENTS" to cn.dumate.a6studio.bridge.McpErrorKind.PROTOCOL_ERROR,
            "RESPONSE_TOO_LARGE" to cn.dumate.a6studio.bridge.McpErrorKind.PROTOCOL_ERROR,
            "INVALID_TOKEN" to cn.dumate.a6studio.bridge.McpErrorKind.PROTOCOL_ERROR,
            "TOOL_ERROR" to cn.dumate.a6studio.bridge.McpErrorKind.TOOL_EXECUTION_ERROR,
            "RATE_LIMITED" to cn.dumate.a6studio.bridge.McpErrorKind.TOOL_EXECUTION_ERROR,
            "PAIRING_REQUIRED" to cn.dumate.a6studio.bridge.McpErrorKind.TOOL_EXECUTION_ERROR,
            "NODE_REF_STALE" to cn.dumate.a6studio.bridge.McpErrorKind.TOOL_EXECUTION_ERROR,
            "HOST_ERROR" to cn.dumate.a6studio.bridge.McpErrorKind.TOOL_EXECUTION_ERROR,
            "NO_IMAGE" to cn.dumate.a6studio.bridge.McpErrorKind.TOOL_EXECUTION_ERROR,
            "UNAUTHORIZED" to cn.dumate.a6studio.bridge.McpErrorKind.TOOL_EXECUTION_ERROR,
            "NETWORK" to cn.dumate.a6studio.bridge.McpErrorKind.TRANSPORT_ERROR,
            "CLEARTEXT_BLOCKED" to cn.dumate.a6studio.bridge.McpErrorKind.TRANSPORT_ERROR,
            "NO_URL" to cn.dumate.a6studio.bridge.McpErrorKind.TRANSPORT_ERROR,
            "SESSION_EXPIRED" to cn.dumate.a6studio.bridge.McpErrorKind.TRANSPORT_ERROR,
            "BAD_ENDPOINT" to cn.dumate.a6studio.bridge.McpErrorKind.TRANSPORT_ERROR,
            "HTTP_ERROR" to cn.dumate.a6studio.bridge.McpErrorKind.TRANSPORT_ERROR
        )
        for ((code, expectedKind) in expectedMappings) {
            assertEquals(
                expectedKind,
                cn.dumate.a6studio.bridge.DualMcpGateway.mapBridgeErrorCode(code),
                "错误码 $code 映射结果不匹配"
            )
        }

        // 启动本地假 MCP HTTP 服务端
        val initCount = java.util.concurrent.atomic.AtomicInteger(0)
        val totalRequests = java.util.concurrent.atomic.AtomicInteger(0)
        val forceUnauthorized = java.util.concurrent.atomic.AtomicBoolean(false)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { ex ->
            totalRequests.incrementAndGet()
            try {
                if (forceUnauthorized.get()) {
                    val errBytes = """{"error":"unauthorized"}""".toByteArray(Charsets.UTF_8)
                    ex.responseHeaders.set("Content-Type", "application/json")
                    ex.sendResponseHeaders(401, errBytes.size.toLong())
                    ex.responseBody.use { it.write(errBytes) }
                    return@createContext
                }
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val req = json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
                val idEl = req["id"] ?: JsonPrimitive(1)
                val method = (req["method"] as? JsonPrimitive)?.content.orEmpty()
                val params = req["params"] as? kotlinx.serialization.json.JsonObject

                val replyObj: kotlinx.serialization.json.JsonObject = when (method) {
                    "initialize" -> {
                        initCount.incrementAndGet()
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", idEl)
                            put("result", buildJsonObject {
                                put("protocolVersion", "2025-03-26")
                                put("serverInfo", buildJsonObject {
                                    put("name", "FakeMcpServer")
                                    put("version", "1.0.0")
                                })
                                put("capabilities", buildJsonObject {})
                            })
                        }
                    }
                    "notifications/initialized" -> {
                        ex.sendResponseHeaders(202, -1)
                        ex.close()
                        return@createContext
                    }
                    "tools/call" -> {
                        val toolName = (params?.get("name") as? JsonPrimitive)?.content.orEmpty()
                        when (toolName) {
                            // 模拟旧版 AutoJs6 插件不存在 get_device_state，返回 -32601 Method not found
                            "get_device_state" -> buildJsonObject {
                                put("jsonrpc", "2.0")
                                put("id", idEl)
                                put("error", buildJsonObject {
                                    put("code", -32601)
                                    put("message", "Method not found: unknown tool get_device_state")
                                })
                            }
                            "device_info" -> buildJsonObject {
                                put("jsonrpc", "2.0")
                                put("id", idEl)
                                put("result", buildJsonObject {
                                    put("content", buildJsonArray {
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", buildJsonObject {
                                                put("manufacturer", "Xiaomi")
                                                put("model", "Mi 14")
                                                put("androidRelease", "14")
                                                put("accessibility", buildJsonObject {
                                                    put("operational", true)
                                                })
                                            }.toString())
                                        })
                                    })
                                })
                            }
                            "ui_current_window" -> buildJsonObject {
                                put("jsonrpc", "2.0")
                                put("id", idEl)
                                put("result", buildJsonObject {
                                    put("content", buildJsonArray {
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", buildJsonObject {
                                                put("packageName", "com.jingdong.app.mall")
                                                put("activityName", ".MainFrameActivity")
                                            }.toString())
                                        })
                                    })
                                })
                            }
                            "ui_dump" -> buildJsonObject {
                                put("jsonrpc", "2.0")
                                put("id", idEl)
                                put("result", buildJsonObject {
                                    put("content", buildJsonArray {
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", buildJsonObject {
                                                put("text", "根节点")
                                                put("clickable", false)
                                                put("children", buildJsonArray {
                                                    // 非 clickable 节点（如用户隐私昵称）不应进入 visibleActionTexts
                                                    add(buildJsonObject {
                                                        put("text", "张三的私密昵称")
                                                        put("clickable", false)
                                                        put("visibleToUser", true)
                                                    })
                                                    // clickable=true 的按钮节点应进入 visibleActionTexts
                                                    add(buildJsonObject {
                                                        put("text", "去完成")
                                                        put("clickable", true)
                                                        put("visibleToUser", true)
                                                    })
                                                })
                                            }.toString())
                                        })
                                    })
                                })
                            }
                            "list_http_flows" -> buildJsonObject {
                                put("jsonrpc", "2.0")
                                put("id", idEl)
                                put("result", buildJsonObject {
                                    put("content", buildJsonArray {
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", buildJsonObject {
                                                put("flows", buildJsonArray {
                                                    add(buildJsonObject {
                                                        put("url", "https://api.m.jd.com/client.action")
                                                        put("Authorization", "Bearer real_secret_jwt_token_987654321")
                                                        put("Cookie", "pt_key=REAL_PT_KEY_SECRET_111; pt_pin=jd_real_user;")
                                                        put("phone", "13812345678")
                                                    })
                                                })
                                            }.toString())
                                        })
                                    })
                                })
                            }
                            else -> buildJsonObject {
                                put("jsonrpc", "2.0")
                                put("id", idEl)
                                put("result", buildJsonObject {
                                    put("content", buildJsonArray {
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", """{"ok":true}""")
                                        })
                                    })
                                })
                            }
                        }
                    }
                    else -> buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", idEl)
                        put("result", buildJsonObject {})
                    }
                }
                val bytes = replyObj.toString().toByteArray(Charsets.UTF_8)
                ex.responseHeaders.set("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            } finally {
                ex.close()
            }
        }
        server.start()
        val endpoint = "http://127.0.0.1:${server.address.port}/mcp"
        val http = HttpClient(CIO)
        try {
            val autojsClient = McpClient(http)
            val proxyPinBridge = cn.dumate.a6studio.bridge.ProxyPinBridge(http, autojsClient = autojsClient)
            val gw = proxyPinBridge.gateway

            // ---- G-1：SSRF DNS 解析后校验（userinfo 绕过 & 十进制 IP 绕过） ----
            val beforeReqs = totalRequests.get()
            for (maliciousUrl in listOf("http://x@169.254.169.254/", "http://2852039166/")) {
                val ssrfResp = gw.call(
                    cn.dumate.a6studio.bridge.CallMcpGatewayOptions(
                        serverId = cn.dumate.a6studio.bridge.McpServerId.PROXYPIN,
                        transport = cn.dumate.a6studio.bridge.McpTransport.HTTP_JSONRPC,
                        endpointUrl = maliciousUrl,
                        allowLocal = true,
                        method = "tools/call",
                        toolName = "list_http_flows"
                    )
                )
                assertFalse(ssrfResp.ok, "恶意地址 $maliciousUrl 必须被网关拦截")
                assertEquals(cn.dumate.a6studio.bridge.McpErrorKind.SSRF_BLOCKED, ssrfResp.errorKind)

                // ProxyPinBridge.configure / listTools / fetchRedactedTraffic 同样必须拦截并抛 INVALID_URL
                val listToolsErr = runCatching {
                    proxyPinBridge.configure(maliciousUrl, token = "", allowLocal = true)
                    proxyPinBridge.listTools()
                }.exceptionOrNull()
                assertTrue(listToolsErr is cn.dumate.a6studio.bridge.BridgeException)
                assertEquals("INVALID_URL", (listToolsErr as cn.dumate.a6studio.bridge.BridgeException).code)

                val fetchErr = runCatching {
                    proxyPinBridge.configure(maliciousUrl, token = "", allowLocal = true)
                    proxyPinBridge.fetchRedactedTraffic()
                }.exceptionOrNull()
                assertTrue(fetchErr is cn.dumate.a6studio.bridge.BridgeException)
                assertEquals("INVALID_URL", (fetchErr as cn.dumate.a6studio.bridge.BridgeException).code)
            }
            assertEquals(beforeReqs, totalRequests.get(), "SSRF 拦截必须在发出任何网络请求前生效")

            // ---- G-2 & G-4：同一个 McpClient 先 initialize，再走网关 HTTP 调用不重置会话，且 -32601 自动降级 ----
            autojsClient.setUrl(endpoint)
            autojsClient.setAuth("test_token_123")
            autojsClient.initialize()
            assertTrue(autojsClient.isReady, "手动 initialize 后 autojsClient.isReady 应为 true")
            assertEquals(1, initCount.get(), "初次握手 initialize 计数应为 1")

            // 用相同地址和令牌调用网关 get_device_state（服务端对 get_device_state 返回 -32601，触发降级）
            val stateResp = gw.call(
                cn.dumate.a6studio.bridge.CallMcpGatewayOptions(
                    serverId = cn.dumate.a6studio.bridge.McpServerId.AUTOJS6,
                    transport = cn.dumate.a6studio.bridge.McpTransport.HTTP_JSONRPC,
                    endpointUrl = endpoint,
                    authToken = "test_token_123",
                    allowLocal = true,
                    method = "tools/call",
                    toolName = "get_device_state"
                )
            )
            assertTrue(stateResp.ok, "get_device_state 遇到 -32601 应降级成功: ${stateResp.errorMessage}")
            assertFalse(stateResp.isSynthetic, "HTTP 真实路径 isSynthetic 应为 false")
            assertTrue(autojsClient.isReady, "网关调用后 autojsClient.isReady 必须保持 true")
            assertEquals(1, initCount.get(), "相同地址与令牌调用网关不得重新触发 initialize 握手")

            val stateStr = stateResp.data.toString()
            assertTrue(stateStr.contains("去完成"), "clickable=true 的节点文本应被提取到 visibleActionTexts")
            assertFalse(stateStr.contains("张三的私密昵称"), "clickable=false 的非交互节点文本不应进入 visibleActionTexts")

            // ---- HTTP 路径脱敏验证（ProxyPin list_http_flows） ----
            val flowResp = gw.call(
                cn.dumate.a6studio.bridge.CallMcpGatewayOptions(
                    serverId = cn.dumate.a6studio.bridge.McpServerId.PROXYPIN,
                    transport = cn.dumate.a6studio.bridge.McpTransport.HTTP_JSONRPC,
                    endpointUrl = endpoint,
                    authToken = "test_token_123",
                    allowLocal = true,
                    method = "tools/call",
                    toolName = "list_http_flows"
                )
            )
            assertTrue(flowResp.ok)
            val flowStr = flowResp.data.toString()
            assertFalse(flowStr.contains("real_secret_jwt_token_987654321"), "HTTP 路径返回的 Bearer Token 必须被脱敏")
            assertFalse(flowStr.contains("REAL_PT_KEY_SECRET_111"), "HTTP 路径返回的 Cookie pt_key 必须被脱敏")
            assertFalse(flowStr.contains("13812345678"), "HTTP 路径返回的手机号必须被脱敏")

            // ---- G-4 & G-5：非「工具不存在」错误（如 HTTP 401 UNAUTHORIZED）不触发降级 ----
            forceUnauthorized.set(true)
            val reqBefore401 = totalRequests.get()
            val unauthResp = gw.call(
                cn.dumate.a6studio.bridge.CallMcpGatewayOptions(
                    serverId = cn.dumate.a6studio.bridge.McpServerId.AUTOJS6,
                    transport = cn.dumate.a6studio.bridge.McpTransport.HTTP_JSONRPC,
                    endpointUrl = endpoint,
                    authToken = "test_token_123",
                    allowLocal = true,
                    method = "tools/call",
                    toolName = "get_device_state"
                )
            )
            assertFalse(unauthResp.ok, "401 响应必须返回 ok=false")
            assertEquals(cn.dumate.a6studio.bridge.McpErrorKind.TOOL_EXECUTION_ERROR, unauthResp.errorKind)
            assertEquals(
                reqBefore401 + 1,
                totalRequests.get(),
                "401 错误不得触发 device_info/ui_current_window/ui_dump 降级请求（应只发 1 次请求）"
            )
        } finally {
            http.close()
            server.stop(0)
        }
    }
}
