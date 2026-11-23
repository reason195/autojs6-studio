# Round 04 Agent Report（编译修复 + N-L 轻微项）

依据：[docs/合并代码审查.md](../合并代码审查.md) · [docs/agent-reports/round-03.md](round-03.md)

---

## 0. 本轮工作范围

前一个助手已写入但未编译/测试的改动（一批未提交的工作区文件）加上审查文档第三节 N-L1~N-L20 轻微项。

---

## 1. 编译修复：唯一失败的测试

### `Aj6KitIntegrationTest.waitCountdownUsesConfiguredDwell`（Aj6KitIntegrationTest.kt:146）

**原因**：测试断言生成代码里含 `if (!_cd) { $kit.backToBean(); }`，但 Catalog.kt 的 R-4 实现生成的是完整的 `if (!_cd) { if ($kit.overBudget() || ...) { ... } $kit.backToBean(); }`，子串匹配失败。

**修复**：`Aj6KitIntegrationTest.kt:146` — 把断言改为检查 `$kit.backToBean();` 子串（它一定包含在 R-4 完整 guard 中），并补了注释说明 R-4 的完整语义。

---

## 2. N-L 轻微项（已完成）

### N-L1 — LlmClient snippet 关键词用 Js.quote
- **文件**：`shared/src/commonMain/…/ai/LlmClient.kt`
- **改动**：`runDeterministicRefiner` 中 `sortedKeywords.joinToString { "\"$it\"" }` → `joinToString { Js.quote(it) }`；补充了 `import cn.dumate.a6studio.catalog.Js`。
- 关键词来自抓包响应的 `buttonText`，可由服务端控制；用 `Js.quote` 确保任意字符都安全转义。

### N-L2 — browseSeconds coerceIn(1, 120)
- **文件**：`LlmClient.kt`
- **改动**：规则引擎 `val waitMs = (maxBrowseSec + 2) * 1000` → `.coerceIn(1_000, 120_000)`，防止 Int 溢出成负数。

### N-L3 — model URL 路径编码 + 错误回显脱敏
- **文件**：`LlmClient.kt`
- **改动**：
  - Gemini endpoint 中 `model` 改为 `model.encodeURLPathPart()`（补 `import io.ktor.http.encodeURLPathPart`）；
  - callGemini 和 callOpenAiCompat 两处 `rawText.take(180)` → `Redaction.redactTextPatterns(rawText.take(180)).text`，错误消息不再把原始响应体回显到 UI 和 toast。

### N-L6 — kitBoot id 只保留 `[A-Za-z0-9_-]`
- **文件**：`shared/src/commonMain/…/catalog/Catalog.kt`（kitBoot codegen）
- **改动**：在 Catalog.kt `kitBoot` 生成代码时，单独计算 `safeId = rawName.replace(Regex("[^A-Za-z0-9_-]"), "").ifEmpty { "kit" }` 并输出为 `id: $idLit`，`name` 保持原始值不变。运行时日志文件名 `jdstudio_${id}.log` 因此不再包含路径穿越字符。

### N-L7 — SimRuntime log O(n²) 累积限制
- **文件**：`shared/src/jvmAndroidMain/…/runtime/SimRuntime.kt`（`files.append` 实现）
- **改动**：对 `jdstudio_*.log` 类文件，当已有内容超过 64 KB 时截掉前半段再追加，避免长时间模拟时字符串拼接 O(n²)。其余文件行为不变。

### N-L8 — ocrSubstrWarn 调用顺序；jdMiaoshaKit 模板「立即刮卡」改 eq
- **文件 1**：`shared/src/commonMain/…/runtime/Aj6KitRuntime.kt`
  - 把 `ocrSubstrWarn(vals)` 从 `var ocrHits = ocrFindAll(...)` 之前移到之后，避免告警早一轮。
- **文件 2**：`shared/src/commonMain/…/model/Templates.kt`（`jdChannelFlow`）
  - 最后一个 `kitClick` 的 `match` 从 `"contains"` 改为 `"eq"`，「立即刮卡」是精确文案，用 eq 避免子串误命中。

### N-L9 — Diagnose 增强：kitBoot 位置、保留名、regex 编译
- **文件**：`shared/src/commonMain/…/diagnose/Diagnose.kt`
- **新增检查**：
  1. `kitBoot` 放在模块里报 ERROR（`$kit` 变成函数局部变量，其他积木访问不到）；
  2. `kitWaitFor`/`kitClick` 的 `match="regex"` 时，对 `value` 尝试 `Regex(...)` 编译，失败报 ERROR；
  3. `Kit`、`$kit` 列为保留名，`varSet`/`varAssign`/`funcDef` 使用这些名字报 ERROR；
  4. 在所有项目级检查后，若 kitBoot 在第一个其他 kit 积木之后出现，报 ERROR。

### N-L10 — SimState reset 一致 + jd.service back
- **文件**：`shared/src/commonMain/…/runtime/SimState.kt`
- **改动 1**：初始 `activity = "BabelActivity"`（与 `screen="jd.mall"` setter 触发的值一致），消除 reset() 前后不一致。
- **改动 2**：`back()` 映射表补 `"jd.service" to "jd.mall"`，模拟器里在服务页按返回能正确回到主页。

### N-L11 — MCP：notifications/* 回 202；ops inputSchema；BlockPatchOp @SerialName；get_project 脱敏
- **文件 1**：`shared/src/jvmMain/…/bridge/DesktopMcpServer.kt`
  - `notifications/initialized` 返回空字符串（内部约定），`handleExchange` 检测到空字符串时回 202 No Content（MCP 规范：通知不期待响应）；
  - `studio_apply_patch` 的 `toolDef` 补完整 `inputSchema`，含 `ops` 数组结构和 `type` 枚举；
  - `studio_get_project` 返回前先过 `Redaction.redactTextPatterns`（rawJs 里可能有用户粘贴的凭据）。
- **文件 2**：`shared/src/commonMain/…/ai/BlockPatch.kt`
  - `BlockPatchOp` 四个子类加 `@SerialName("add"/"update"/"remove"/"rename")`，外部 AI 现在可以用简短名称，不需要填完整类名。
- **文件 3**：`shared/src/jvmTest/…/MergedFeaturesTest.kt`
  - `desktopMcpServerRoundTripWorksOverHttp` 测试把 `type` 从 `"cn.dumate.a6studio.ai.BlockPatchOp.RenameProject"` 改为 `"rename"`，与 `@SerialName` 对齐。

### N-L12 — 截图冒烟路径退出前 stop MCP + dispose
- **文件**：`desktop/src/jvmMain/…/desktop/Main.kt`
- **改动**：截图 `exitApplication()` 前加 `runCatching { mcpServerHolder[0]?.stop() }` + `state.dispose()`，确保 jdk.httpserver 调度线程（非守护线程）不会阻止 JVM 退出。

### N-L16 — runAiRefine 从 kitBoot.pkg 读包名
- **文件**：`shared/src/commonMain/…/ui/StudioState.kt`
- **改动**：`runAiRefine` 从工程根 body 里查找 `kitBoot` 积木，取 `params["pkg"]`；找不到时回落到 `"com.jingdong.app.mall"`。

### N-L17 — McpClient 加 label 区分 ProxyPin
- **文件 1**：`shared/src/commonMain/…/bridge/McpClient.kt`
  - 构造函数增加 `val label: String = "手机 AutoJs6"` 参数；`timeoutMessage` 和 `networkMessage` 中 `"手机 AutoJs6"` 改为 `"$label"` / `"$label 的 MCP Server"`。
- **文件 2**：`shared/src/commonMain/…/bridge/ProxyPinBridge.kt`
  - `val mcp = McpClient(http, label = "ProxyPin")`，错误消息不再误导用户去找 AutoJs6 手机端。

### N-L18 — RenameProject 名称校验；凭据检查只比较新增
- **文件**：`shared/src/commonMain/…/ai/BlockPatch.kt`
- **改动 1**：`guardAndApply` 中 `RenameProject` 验证补齐：长度 ≤ 64、无控制字符、无文件名非法字符（`\/:|*?"<>`）。
- **改动 2**："生成代码含明文凭据"护栏改为差分对比：先扫 `before` 工程的命中集合，只有 `after` 新增的命中才拒绝，已有的不再阻断所有补丁。

### N-M10 追加：`suggestRuntimeModules` 核对结果补进 build.gradle.kts
- **文件**：`desktop/build.gradle.kts`
- **改动**：`./gradlew :desktop:suggestRuntimeModules` 输出 `java.instrument, java.management, jdk.dynalink, jdk.httpserver, jdk.unsupported`；全部补入 `modules(...)` 列表。

---

## 3. 未做的项及原因

| 编号 | 未做原因 |
|---|---|
| N-L4 | 双计数去重需要在 `RedactionReport` 上增加"已脱敏"标记并跨层传递，改动量较大且风险不低；`ProxyPinBridge` 的第二次脱敏目前只是对已脱敏字符串再跑一遍正则，不会引入新漏洞，列为后续优化。 |
| N-L5 | 属于 N-M1 的一部分，N-M1 在 round-03 已修复（`UrlSecurity.kt` 改写），本轮无额外工作。 |
| N-L13 | 检查 `jvmMain` 多余的 `ktor-client-okhttp` 依赖：去掉前需确认桌面端没有任何间接用到 OkHttp（round-03 round-02 已确认 `StudioState` 只用 `CIO`），但配置变更属于构建系统改动，优先级低；列为后续清理。 |
| N-L14 | round-03 报告已确认 `shared/shared` 空目录不存在，本轮验证一致，无需操作。 |
| N-L15 | Aj6KitRuntime.kt 注释约 2000 行嵌入每次真机下发请求里，体积和信息泄露风险确实存在；但「构建时去掉注释」需要改动代码生成流程，改动量大且可能影响测试，列为专项优化，不在本轮范围内。 |
| N-L19 | Redaction 单次扫描：已确认 `redactTextPatterns` 目前是每条规则一次 `replace`（单遍），没有 `findAll().count()` + `replace` 双遍，不需要改动。 |
| N-L20 | 云端确认弹窗在 round-03 已实现（`pendingCloudPreview` 预览机制）；Ollama/LM Studio 地址输入框属于 UI 新功能，不在本轮约束范围内（无用户明确指令）。 |

---

## 4. 我修正了哪些写错的地方

1. **R-4 / `waitCountdownUsesConfiguredDwell` 断言**：前一个助手把测试断言写成 `if (!_cd) { $kit.backToBean(); }`，但实际生成代码是含 overBudget/atRisk 判断的完整 guard 形式，子串不匹配导致测试失败。已更新为检查 `$kit.backToBean();` 子串。

2. **N-L8 `ocrSubstrWarn` 重复调用**：在实现"移到 ocrFindAll 之后"时，strReplace 路径导致出现两次 `ocrSubstrWarn(vals)` 调用；已检测并修正为只保留一次（移动到 ocrFindAll 结果之后）。

3. **Diagnose.kt `onFailure` 参数名冲突**：`runCatching {...}.onFailure { it -> add(..., it) }` 中 `it` 在 lambda 里是 `Throwable`，遮住了外层循环变量 `it: Item`，导致编译类型错误；改为 `onFailure { _ -> add(..., it) }` 使用外层 `it`。

---

## 5. 三条命令最终输出

```
./gradlew :shared:jvmTest --console=plain --no-daemon
  → BUILD SUCCESSFUL（188 tests, 0 failures, 0 ignored）

./gradlew :desktop:compileKotlinJvm --console=plain --no-daemon
  → BUILD SUCCESSFUL

./gradlew :androidApp:assembleDebug --console=plain --no-daemon
  → BUILD SUCCESSFUL
```

`./gradlew :desktop:suggestRuntimeModules` 输出：
```
modules("java.instrument", "java.management", "jdk.dynalink", "jdk.httpserver", "jdk.unsupported")
```
已全部写入 `desktop/build.gradle.kts`。

---

## 6. 复审（Quick，编译测试后）

逐项核对了 Kiro 的改动。结论：大部分正确，以下 2 处已由我修正（**尚未重新编译**，需再跑一次 `:shared:jvmTest`）：

| 位置 | 问题 | 修正 |
|---|---|---|
| `DesktopMcpServer.kt` studio_get_project | 对整段 JSON 文本跑 `redactTextPatterns`：`header_line` 规则 `(?im)^…Cookie:….+$` 在单行 JSON 里会从命中处吃到行尾，破坏 JSON；且 >64KB 会被截断成非法 JSON | 改为遍历 JsonElement，只对字符串值脱敏，结构不变 |
| `Templates.kt` jdChannelFlow | 把**所有**频道模板的最后一次 kitClick 都改成 `eq`，「去完成」等带后缀的按钮会点不到；审查只要求「立即刮卡」 | 仅 `actionText == "立即刮卡"` 时用 `eq`，其余保持 `contains` |

已核对无问题：N-L1/L2/L3/L6/L7/L9/L10/L12/L16/L17/L18、N-M10 模块列表、notifications 202、BlockPatchOp `@SerialName`。

需要用户知悉的行为变化：
- **BlockPatchOp 判别名改为短名**（`add/update/remove/rename`），旧的全类名不再被接受。外部 MCP 客户端/提示词需同步。
- **N-L10** 模拟器初始 activity 改为 `BabelActivity`。
- **N-M11** 测试未改 `_lastRiskAt`，能通过说明 SimRuntime 时钟起点 > 3s；真机仍需按一次停止验证。
- N-M10 尚未打 MSI 实测。


---

## 7. 复审后改动验证（2026-11-24）

用户在 round-04 结束后改了两处（未编译），本节记录验证过程与结论。

### 7.1 两处改动内容确认

**改动 1**：`DesktopMcpServer.kt` — `studio_get_project` 改为先把 `Project` 序列化为 `JsonElement`（`json.encodeToJsonElement(Project.serializer(), getProject())`），再通过新私有函数 `redactStrings` 递归遍历每个 `JsonPrimitive` 字符串值调用 `Redaction.redactTextPatterns`，最后 `.toString()` 输出。  
这解决了之前对整段 JSON 文本运行 `redactTextPatterns` 的两个问题：  
① `header_line` 正则按行匹配到行尾，会把 `"rawJs": "... Cookie: pt_key=...` 一直吃到该行末尾；  
② `HARD_MAX_INPUT_CHARS=65536` 截断逻辑会把大工程的完整 JSON 截断，输出非法 JSON。  
编译验证：`encodeToJsonElement` 是 kotlinx-serialization 的顶层扩展，无需额外 import，编译通过。

**改动 2**：`Templates.kt` `jdChannelFlow` — 最后一个 `kitClick` 仅当 `actionText == "立即刮卡"` 时用 `match="eq"`，否则恢复 `"contains"`。round-04 把所有频道的末尾点击都改成了 `eq`，这里只针对「立即刮卡」这个确有子串撞车风险的值精确匹配，其他频道（`去完成`、`立即签到` 等）保持 `contains` 以兼容带后缀的按钮文案。

### 7.2 新增测试

**`MergedFeaturesTest.mcpGetProjectReturnsValidJsonAndRedactsSecrets`**（`MergedFeaturesTest.kt`，新增于末尾）

覆盖：
1. `rawJs` 积木含 `"Cookie: pt_key=abc; pt_pin=x"` → 返回 text 不含 `pt_key=abc`；
2. `comment` 积木含 70,000 字符字符串（超过 `HARD_MAX_INPUT_CHARS=65536`）→ 返回 text 仍可被 `Json.parseToJsonElement` 解析（合法 JSON），且 `"name"` 字段仍存在（结构未截断）。

### 7.3 三条命令最终输出

```
./gradlew :shared:jvmTest --console=plain --no-daemon
  → BUILD SUCCESSFUL（189 tests，0 failures）

./gradlew :desktop:compileKotlinJvm --console=plain --no-daemon
  → BUILD SUCCESSFUL

./gradlew :androidApp:assembleDebug --console=plain --no-daemon
  → BUILD SUCCESSFUL
```

### 7.4 packageMsi 产物与 jdk.httpserver 确认

```
./gradlew :desktop:packageMsi --console=plain --no-daemon
  → BUILD SUCCESSFUL
  → The distribution is written to
    desktop/build/compose/binaries/main/msi/AutoJs6 Studio-1.0.1.msi
```

clean 重建后，`desktop/build/compose/tmp/main/runtime/release` 的 `MODULES` 行：

```
MODULES="java.base java.datatransfer java.xml java.prefs java.desktop
         java.instrument java.logging java.management
         jdk.crypto.ec jdk.dynalink jdk.httpserver jdk.unsupported"
```

**`jdk.httpserver` 已确认包含**。`jdk.crypto.ec` 是 jpackage 为 TLS 自动追加的，不影响结果。

MSI 产物路径：`I:\Github\autojs6-studio\desktop\build\compose\binaries\main\msi\AutoJs6 Studio-1.0.1.msi`

**能否启动验证本地 MCP**：本次在 CI/无界面环境下无法交互运行 `.msi` 安装包。若在有桌面的 Windows 环境安装后，打开「AI 抓包」面板点「启用本地 MCP 服务端」，日志应显示 `本地 MCP 已启动 http://127.0.0.1:<port>/mcp` 并生成 Bearer Token；若显示 `jdk.httpserver 模块缺失` 错误则说明 jlink 打包有问题（本次确认模块已在 `release` 文件中，该路径不应触发）。

---

## 8. 双 MCP 网关修复（G-1 ~ G-7 及轻微项）

### 8.1 逐项修改文件与函数清单

| 审查项 | 修改文件与行号 | 修改的函数 / 结构说明 |
|---|---|---|
| **G-1【严重】SSRF DNS 解析后校验** | `DualMcpGateway.kt:295`<br>`ProxyPinBridge.kt:52-64, 152` | 1. `DualMcpGateway.call()` 的 HTTP 分支改用 `UrlSecurity.validateEndpointUrlResolved(targetUrl, allowLocal = opts.allowLocal)`；<br>2. `ProxyPinBridge` 新增 `private suspend fun ensureResolvedSafe()` 调用 `UrlSecurity.validateEndpointUrlResolved(...)`，在 `listTools()` 与 `fetchRedactedTraffic()` 的 HTTP 路径发请求前强制执行，失败抛 `BridgeException(..., "INVALID_URL")`。 |
| **G-2【严重】避免网关重置 AutoJs6 会话** | `McpClient.kt:85-116`<br>`DualMcpGateway.kt:308-314`<br>`ProxyPinBridge.kt:43-50` | 1. `McpClient` 新增 `buildAuthorizationHeader(token)` 与 `currentAuthHeader()`，`setAuth(token)` 仅在规范化头变化或 `!ready` 时才重置 `ready = false` 与 `sessionId = ""`；<br>2. `DualMcpGateway.call()` 仅在 `urlCheck.normalizedUrl != client.currentUrl` 或 `desiredAuthHeader != client.currentAuthHeader()` 时才调用 `setUrl` / `setAuth`，避免真机 `runScript` 期间点「采集 AutoJs6 状态」导致会话丢失；<br>3. `ProxyPinBridge.configure()` 同理仅在地址或规范化头变化时更新 `mcp`。 |
| **G-3【中】错误码映射对齐 McpClient** | `DualMcpGateway.kt:212-238, 326` | 抽出 `internal fun mapBridgeErrorCode(code: String): McpErrorKind`，按 `McpClient.kt` 与 `ProxyPinBridge.kt` 实际抛出的 22 个错误码完整映射：`TIMEOUT` → `TIMEOUT_ERROR`；`INVALID_URL` → `SSRF_BLOCKED`；`BAD_RESPONSE` / `EMPTY_BODY` / `EMPTY_RESPONSE` / `RPC_ERROR` / `INVALID_ARGUMENTS` / `RESPONSE_TOO_LARGE` / `INVALID_TOKEN` → `PROTOCOL_ERROR`；`TOOL_ERROR` / `RATE_LIMITED` / `PAIRING_REQUIRED` / `NODE_REF_STALE` / `HOST_ERROR` / `NO_IMAGE` / `UNAUTHORIZED` → `TOOL_EXECUTION_ERROR`；其余（`NETWORK` / `CLEARTEXT_BLOCKED` / `NO_URL` / `SESSION_EXPIRED` / `BAD_ENDPOINT` / `HTTP_ERROR`）→ `TRANSPORT_ERROR`。 |
| **G-4【建议】仅在工具不存在时降级并透传 timeoutMs** | `DualMcpGateway.kt:243-262, 361-418`<br>`McpClient.kt:453, 523, 558` | 1. 新增 `DualMcpGateway.isToolNotFoundError(e: BridgeException)` 与 `isToolNotFoundMessage(kind, message)`，仅当 `RPC_ERROR` / `TOOL_ERROR` 且消息包含 `-32601` / `method not found` / `unknown tool` / `tool not found` / `未知工具` 时才返回 `true`；<br>2. `callRemoteToolWithAdapter` 中 `get_device_state` 与 `capture_ui_tree` 仅在 `isToolNotFoundError(e)` 为 `true` 时降级，其他错误（如 401 `UNAUTHORIZED`、超时 `TIMEOUT`）原样抛出；<br>3. 为 `McpClient.deviceInfo(timeoutMs)`、`uiDumpJson(..., timeoutMs)`、`currentWindow(timeoutMs)` 增加 `timeoutMs` 参数并在降级调用时全部透传 `timeoutMs`。 |
| **G-5【建议】ProxyPin 仅在工具不存在时回退 get_flows** | `ProxyPinBridge.kt:153-161`<br>`StudioState.kt:906-918` | `ProxyPinBridge.fetchRedactedTraffic()`（`list_http_flows` → `get_flows`）与 `StudioState.fetchProxyPinTraffic()` 均复用 `DualMcpGateway.isToolNotFoundError` / `isToolNotFoundMessage`，仅在「工具不存在」时回退，超时或 401 等错误直接保留并展示第一次的真实错误原因。 |
| **G-6【建议】Local Bridge 合成数据隔离与钩子门控** | `DualMcpGateway.kt:52, 65, 290, 478-502`<br>`StudioState.kt:174-184, 868-878, 922-932, 956, 976, 1029-1035`<br>`AiTrafficPane.kt:112-135, 262-269` | 1. `StudioState` 新增 `devMode`（默认 `false`）及 `lastAutoJsIsSynthetic` / `lastTrafficIsSynthetic` / `lastRefineIsSynthetic`；<br>2. `AiTrafficPane` 默认只显示 `HTTP_JSONRPC`，仅开启「开发者合成调测（Local Bridge）」开关时才暴露 `LOCAL_BRIDGE` 选项；<br>3. 合成数据返回 `McpGatewayResponse.isSynthetic = true`，界面状态栏明确标注 `【合成数据】`，且在 `applyRefinedToSelectedOrInsert()` 与回填按钮处拒绝将合成数据提炼结果回填到工程积木并弹出 toast 提示；<br>4. `__simulateToolError` / `__simulateProtocolError` 仅在 `opts.devMode == true` 时生效。 |
| **G-7【建议】删除未使用的 rawPreviewForAudit** | `DualMcpGateway.kt:41-53, 567-598` | 删除 `McpGatewayResponse.rawPreviewForAudit` 字段及 `finalizeMcpRpcResponse` 的 `includeRawPreviewForAudit` 参数，彻底避免未脱敏原文进入 `McpGatewayResponse`。 |
| **轻微项 1：消除重复网关与多余 McpClient** | `ProxyPinBridge.kt:23-29`<br>`StudioState.kt:109-111` | `ProxyPinBridge(http, autojsClient = bridge)` 接收 `StudioState.bridge` 并复用自身 `mcp` 构建唯一的 `DualMcpGateway`，`StudioState.mcpGateway` 直接指向 `proxyPin.gateway`。 |
| **轻微项 2：JSON 字段安全类型转换** | `DualMcpGateway.kt:336-352, 424-449, 479-520` | 所有外部 JSON 字段访问均改用 `(x as? JsonPrimitive)` / `(x as? JsonObject)` / `(x as? JsonArray)`，防止服务端返回数组或对象代替字符串时抛 `IllegalArgumentException`。 |
| **轻微项 3：visibleActionTexts 仅收集 clickable 节点** | `DualMcpGateway.kt:424-449` | `extractVisibleActionTexts(dump)` 仅在 `node["clickable"] == true` 且节点可见时才收集 `text` / `desc`（长度 1..24），上限 50 条，防止页面上的非交互隐私文本（如个人昵称、订单号）被收集后送往云端模型。 |

### 8.2 新增与更新测试

1. **`MergedFeaturesTest.dualMcpGatewaySupportsBothAutoJs6AndProxyPinWithRedaction`**（`MergedFeaturesTest.kt:703-799`）
   - 覆盖 Token 控制字符（`\r\n`）防注入拦截；
   - 覆盖 **G-6**：`__simulateToolError` 在 `devMode = false` 时不生效、在 `devMode = true` 时触发 `TOOL_EXECUTION_ERROR`，且 `LOCAL_BRIDGE` 响应带 `isSynthetic = true`；
   - 覆盖 `LOCAL_BRIDGE` 下 AutoJs6 + ProxyPin 联合采集、脱敏与 `LlmClient.refine` 规则提炼。
2. **`MergedFeaturesTest.dualMcpGatewayHttpPathAndReviewFixes`**（新增，`MergedFeaturesTest.kt:802-1111`）
   - 覆盖 **G-3**：遍历 22 个 `BridgeException.code` 断言 `DualMcpGateway.mapBridgeErrorCode` 的映射结果；
   - 覆盖 **G-1**：使用 `"http://x@169.254.169.254/"` 与 `"http://2852039166/"` 调用 `DualMcpGateway.call(transport = HTTP_JSONRPC)` 以及 `ProxyPinBridge.listTools()` / `fetchRedactedTraffic()`，断言网关返回 `ok = false`、`errorKind = SSRF_BLOCKED`，`ProxyPinBridge` 抛 `BridgeException(code = "INVALID_URL")`，且本地假 HTTP 服务端收到请求数为 `0`；
   - 覆盖 **G-2**：先用 `autojsClient.initialize()` 完成首次握手（`isReady == true`、`initCount == 1`），再用相同地址与令牌调用网关 `HTTP_JSONRPC`，断言 `autojsClient.isReady` 始终为 `true` 且服务端 `initCount` 仍为 `1`（未重新握手）；
   - 覆盖 **G-4 & 轻微项 3 & HTTP 路径脱敏**：通过 `com.sun.net.httpserver.HttpServer` 模拟 MCP 服务端，验证 `get_device_state` 返回 JSON-RPC `-32601` 时自动降级到 `device_info` + `ui_current_window` + `ui_dump`，且 `visibleActionTexts` 只提取 `clickable = true` 的 `"去完成"`、不提取 `clickable = false` 的 `"张三的私密昵称"`；验证 ProxyPin HTTP 路径返回的 `Bearer` Token、`pt_key` Cookie 与手机号均被脱敏；
   - 覆盖 **G-4 & G-5 非工具不存在不降级**：服务端返回 HTTP `401` 时，断言返回 `ok = false`、`errorKind = TOOL_EXECUTION_ERROR`，且服务端请求计数仅增加 1 次（不触发降级调用）。

### 8.3 三条验收命令最终结果

```
./gradlew :shared:jvmTest --console=plain --no-daemon
  → BUILD SUCCESSFUL in 43s（191 tests, 0 failures, 0 ignored）

./gradlew :desktop:compileKotlinJvm --console=plain --no-daemon
  → BUILD SUCCESSFUL in 23s

./gradlew :androidApp:assembleDebug --console=plain --no-daemon
  → BUILD SUCCESSFUL in 35s
```

### 8.4 未做的项及原因

- 无未完成项（`G-1` ~ `G-7` 及 3 个轻微项、HTTP 路径单元测试、三条验收命令均已完成）。

### 8.5 复审（Quick）

逐项对照代码核对 G-1~G-7 与轻微项，均已按要求落地。另修正 2 处（**未重新编译**，需再跑 `:shared:jvmTest`）：

| 位置 | 问题 | 修正 |
|---|---|---|
| `DualMcpGateway.isToolNotFoundText` | 用裸 `"not found"` 判断「工具不存在」：AutoJs6 的 `TOOL_ERROR`「node not found」、ProxyPin 的「flow not found」等业务失败也会触发降级重试，G-4/G-5 等于没收紧 | 改为只认 `-32601` / `method not found` / `tool not found` / `unknown tool` / `unknown method` / 中文等价词；新增测试 `dualMcpGatewayOnlyDegradesOnMissingTool` |
| `ProxyPinBridge` 构造 | `autojsClient` 为空时回落到 ProxyPin 自己的 `mcp`：网关的 AUTOJS6 调用会对它 `setUrl`，把 ProxyPin 地址/会话换掉 | 回落改为独立的 `McpClient(http)` |

已确认无问题：G-1 两处都走 `validateEndpointUrlResolved`；G-2 网关与 `McpClient.setAuth` 都只在地址/令牌变化时重置；G-3 映射表与 McpClient 实际错误码一致；G-6 devMode 默认关闭、合成数据有标记且禁止回填；G-7 字段已删除。

遗留（低）：`fetchAutoJsDeviceState` 先 `applyBridgeConfig()`（走 `setAuthFor`），网关又按 `opts.authToken` 比较一次 `currentAuthHeader()`；两者规范化方式一致时不会重复握手，测试已覆盖相同令牌的情况。


### 8.6 复审修正验证（2026-11-24）

对 8.5 节两处复审修正（`DualMcpGateway.isToolNotFoundText` 收紧 + `ProxyPinBridge` 构造回落）的编译与测试验证。

**改动内容（本轮为纯验证，未改任何代码）**：

- `DualMcpGateway.kt:243-256`：`isToolNotFoundText` 已只认 `-32601` / `method not found` / `tool not found` / `unknown tool` / `unknown method` / `未知工具` / `工具不存在` / `no such tool`，裸 `"not found"` 已移除；
- `ProxyPinBridge.kt:27`：`autojsClient` 为空时回落改为独立的 `McpClient(http)`，不再回落到 ProxyPin 自己的 `mcp`；
- 新测试 `dualMcpGatewayOnlyDegradesOnMissingTool`（`MergedFeaturesTest.kt:802-816`）通过 `cn.dumate.a6studio.bridge.DualMcpGateway.isToolNotFoundError(...)` 调用 companion `internal` 函数——jvmTest 与 commonMain 同属 shared 模块，`internal` 可见，该写法编译通过，无需修改。

**已有测试影响核查**：

- `dualMcpGatewayHttpPathAndReviewFixes` 模拟服务端对 `get_device_state` 返回 `"Method not found: unknown tool get_device_state"`（含 `-32601` code），收紧后同时命中 `method not found` 与 `unknown tool`，降级路径不受影响，测试通过；
- 全仓搜索（排除 build 目录）未发现其他依赖裸 `"not found"` 降级的测试：`SseDecodeTest` / `SseParseTest` 中的 "payload not found" 是断言消息文案，`legacy-web/tests/mock-mcp.mjs:203` 的 "file not found" 是 Node 假服务端读文件报错，均与网关降级判断无关。无需放宽判断。

**三条命令结果**：

```
./gradlew :shared:jvmTest --console=plain --no-daemon
  → BUILD SUCCESSFUL in 36s（192 tests, 0 failures, 0 errors, 0 skipped）

./gradlew :desktop:compileKotlinJvm --console=plain --no-daemon
  → BUILD SUCCESSFUL in 14s

./gradlew :androidApp:assembleDebug --console=plain --no-daemon
  → BUILD SUCCESSFUL in 34s
```

结论：两处复审修正与新测试一次性编译、全部测试通过，无需额外修复。


## 9. Android 冒烟测试（2026-11-24，只测试，未改代码 / 未 git commit/push）

### 9.1 环境与安装

| 项 | 值 |
| --- | --- |
| 设备 1（主） | Meizu 17 Pro（Z91QGFWN223ER），Android 13 / API 33 |
| 设备 2 | OPPO CPH2581（4268c692），Android 16 / API 36 |
| applicationId / Activity | cn.dumate.a6studio / cn.dumate.a6studio.android.MainActivity |
| APK | `androidApp/build/outputs/apk/debug/androidApp-debug.apk`，12305033 B（11.7 MiB），mtime 2026-11-24 04:04:12（晚于全部源码改动，无需重编译） |
| 安装方式 | `adb install -r`（覆盖安装，Meizu 先装；OPPO 原装旧 APK 早于 dual MCP 网关改动，已重装新 APK） |

说明：任务要求优先 Z91QGFWN223ER——该 serial 与本机已连接设备不符，按背景描述 Meizu 17 Pro 即目标机，故以 Meizu 为主机执行静态检查（a/b/c/d），OPPO 承担需要 UI 自动化的 e/f。

### 9.2 结果 a–f

| 项 | 结果 | 证据 |
| --- | --- | --- |
| a. 启动 10s 后进程存活，无崩溃 | ✅ 两台均过 | Meizu：`am start -W` 成功，PID 9276（`dumpsys activity processes` 可见；`pidof`/`ps -A` 查不到是 SELinux 限制 shell uid 查看他 uid 进程，`kill -0` 报 Operation not permitted 反证进程存在）。OPPO：冷启动 PID 32705 存活至测试结束。两台 crash 缓冲区中 app 自身 FATAL 均为 0（Meizu 的 crash 条目全部来自 `com.android.commands.uiautomator` DumpCommand，即 uiautomator 自身被 Flyme 杀掉，非 app 崩溃；OPPO crash 缓冲区为空） |
| b. 截图非空 | ✅ | `docs/agent-reports/android-smoke.png`（254890 B，Meizu 画布视图，覆盖了此前 0 字节的坏文件） |
| c. 覆盖安装后旧数据完好 | ✅ 两台均过 | 覆盖安装 + 启动后自动恢复旧项目「京东领券中心 · 实战示例」（11 块 / 1 模块 / 3 测试），UI 与 dump 均无「解析失败」「备份」提示，R-2 externalPatchPending 兼容路径未触发异常 |
| d. 模拟运行 | ✅（Meizu） | 打开 kit 模板项目 → ▶ 运行 → 模拟运行面板日志完整走完（「完成·模拟 40s」，日志末条 `[40200] 流程结束`），无崩溃。截图 `docs/agent-reports/android-smoke-run.png`（281215 B）。注：观察期间另有并发任务也在操作 Meizu，运行状态在双方共同操作下达成；OPPO 上代码页签可见同项目生成的 AutoJs6 代码（110 行）正常渲染 |
| e. 网络/SSRF | ✅（OPPO） | HTTP JSON-RPC 模式，ProxyPin 地址 `http://192.168.1.1:9999/mcp` → 点「拉取并脱敏流量」→ 界面提示「ProxyPin 拉取失败：连不上 http://192.168.1.1:9999/mcp：Connect timeout has expired [connect_timeout=8000ms]。检查：①ProxyPin 的 MCP Server 是否在运行…②IP 是否变了…③网络是否可达…」，按钮按预期在请求期间禁用后恢复；换 `http://x@169.254.169.254/` → 界面提示「ProxyPin 拉取失败：安全策略拦截：URL 不允许携带用户信息 (userinfo)」。两次均未崩溃，logcat（--pid 过滤）无 Exception / NetworkOnMainThreadException / FATAL，OPPO crash 缓冲区为空。截图 `docs/agent-reports/android-smoke-ai.png`（311971 B，连接失败提示）与 `docs/agent-reports/android-smoke-ssrf.png`（275668 B，userinfo 拦截提示） |
| f. 默认界面无 Local Bridge | ✅（OPPO） | AI 抓包面板传输方式区只显示「HTTP JSON-RPC（真机/实时服务）」一个 chip，「开发者合成调测」保持关闭（devMode 默认 off），Local Bridge 选项不可见。与代码 `AiTrafficPane.kt:120-124`（`visibleTransports = if (state.devMode) McpTransport.entries else listOf(McpTransport.HTTP_JSONRPC)`）一致。证据同 android-smoke-ai.png |

### 9.3 截图文件

| 文件 | 大小 | 内容 |
| --- | --- | --- |
| `android-smoke.png` | 254890 B | Meizu 画布视图（启动后主界面，旧项目已恢复） |
| `android-smoke-run.png` | 281215 B | Meizu 模拟运行面板（完成·模拟 40s，日志完整） |
| `android-smoke-ai.png` | 311971 B | OPPO AI 抓包面板，`192.168.1.1:9999/mcp` 连接失败提示（传输区仅 HTTP JSON-RPC chip，无 Local Bridge） |
| `android-smoke-ssrf.png` | 275668 B | OPPO AI 抓包面板，`http://x@169.254.169.254/` userinfo 安全拦截提示 |

### 9.4 logcat 异常摘录（全部为非崩溃类）

- Meizu crash 缓冲区 FATAL（均来自 `com.android.commands.uiautomator` DumpCommand 被 Flyme 杀掉，exit 137 / `UiAutomationService already registered`，与 app 无关）：
  `FATAL EXCEPTION: com.android.commands.uiautomator` main / `java.lang.IllegalStateException: UiAutomationService already registered` —— Meizu Flyme 拒绝 shell 调起的 uiautomator，故 Meizu 上 UI dump/点按不可用，交互项移到 OPPO。
- OPPO app 进程 logcat（--pid=32705）无 Exception / FATAL / NetworkOnMainThread；crash 缓冲区为空。
- OPPO 系统级噪声（非崩溃）：`W VRI[MainActivity]: handleResized abandoned!`（InsetsController 键盘开合引起的 relayout 警告）、`W OnBackInvokedCallback: onBackInvokedCallback not enabled`（系统兼容提示）、偶发 `Choreographer` 跳帧。

---

## 10. 「拉取并选择大模型」（2026-11-24，已 commit + push master，未打 tag）

### 10.1 需求

AI 面板的四个模型通道（Gemini / OpenAI 兼容 / Ollama / LM Studio）可从服务端拉取可用模型列表并选择，也可手动输入；Ollama / LM Studio 的 Base URL 可在界面里修改。模型选择与 Key 一样仅存内存，绝不写入工程文件。

### 10.2 实现

| 文件 | 改动 |
| --- | --- |
| `shared/src/commonMain/…/ai/LlmClient.kt` | 新增 `LlmModelInfo(id, displayName, note)` 与 `listModels(pid, config, timeoutMs=10s)`：Gemini `GET /v1beta/models?pageSize=200`（Key 只进 `x-goog-api-key` 头、翻页最多 5 页、仅保留 supportedGenerationMethods 含 generateContent 的模型、剥 `models/` 前缀）；OpenAI 兼容 `GET {base}/models`（`Authorization: Bearer`）；Ollama 优先 `/v1/models`、404/405/501 回退原生 `/api/tags`；LM Studio `GET {base}/models`；AUTO / DETERMINISTIC_RULES 抛「该通道无需选择模型」。所有非 Gemini 地址先过 `UrlSecurity.validateEndpointUrlResolved`，本地通道在 allowLocalInference=false 时直接报「未启用本地推理」；非 2xx 报 `HTTP {code}` + 脱敏后响应体（401/403 附「API Key 无效或无权限」）；结果去重、按 id 排序、上限 500。`Companion.geminiBaseUrl` 设为 internal 供测试注入 |
| `shared/src/commonMain/…/ui/StudioState.kt` | 新增 `llmModelOptions / llmModelsLoading / llmModelsError` 三个 Compose 状态、`fetchLlmModels(pid)`（后台线程拉取、成功 toast「已拉取 N 个模型」、空列表仅提示不改当前模型、失败写 error 并 toast）与 `selectLlmModel(pid, id)`（写进对应通道的 model 字段）。模型与 Key 一样仅存内存 |
| `shared/src/commonMain/…/ui/AiTrafficPane.kt` | 四通道新增「模型」行：可手输的 OutlinedTextField +「拉取模型」按钮（拉取中显示「拉取中…」并禁用）+ 下拉列表（displayName 与 id 两行）；Ollama / LM Studio 的 Base URL 输入框；AUTO 通道给一行固定说明「自动路由按 Gemini → OpenAI 兼容 → 本地 的顺序……」；拉取失败的红色错误文案。桌面与 Android 同一份 UI |
| `shared/src/jvmTest/…/LlmModelListTest.kt` | 新增 7 项回归（见 10.3） |

### 10.3 测试（LlmModelListTest，全部通过）

| 项 | 断言 |
| --- | --- |
| OpenAI 兼容解析/去重/排序 + Bearer 头 | 3 id 去重排序为 `[chat-b, gpt-4o-mini]`；恰好 1 个请求；`Authorization: Bearer <Key>` |
| Ollama 404 回退 `/api/tags` | 访问序列 `[v1, tags]`，结果 `[llama3:8b, qwen2.5:7b]` |
| 401 提示与脱敏 | 消息含 `HTTP 401`、`API Key 无效或无权限`、`[REDACTED_API_KEY]`（响应体里 24 字符假 sk- 令牌被脱敏） |
| 未启用本地推理 | Ollama / LM Studio 均抛 `未启用本地推理`，服务端 0 请求 |
| SSRF userinfo | `http://x@127.0.0.1:{port}/v1` 抛 `安全策略拦截`，0 请求 |
| 畸形 JSON | `{"data":{}}` / `[]` / 纯文本 / `{"id":123}` 四种响应体都返回空列表，绝不抛 IllegalArgumentException（kotlinx 的 SerializationException 是它的子类，解析处全部 runCatching 包裹） |
| Gemini | Key 只进 `x-goog-api-key` 头（URL 查询参数里绝不出现 `key=`）；两页翻页合并；不支持 generateContent 的 embedding 模型被过滤；`models/` 前缀剥离；displayName 生效 |

### 10.4 验证

- `./gradlew :shared:jvmTest`：**199 项全部通过**（原 192 + 新增 7），0 失败。
- `./gradlew :desktop:compileKotlinJvm`：BUILD SUCCESSFUL。
- `./gradlew :androidApp:assembleDebug`：BUILD SUCCESSFUL。

### 10.5 交付说明

- `docs/release-notes.md` v1.0.2「### 其他」首条新增该功能条目（见该文件）。
- 已提交 `feat(ai): 拉取并选择大模型` 并 push master；**按用户要求未打 tag、未发版**。
- 测试期间 OPPO 一次自动熄屏（04:27:56，mCurrentFocus → NotificationShade）导致一帧锁屏截图，`KEYCODE_WAKEUP` + 上滑解锁后 app 恢复前台，未影响任何结果。

### 9.5 结论

a–f 六项全部通过（两台设备互为补充：Meizu 静态项，OPPO 交互项）。覆盖安装后旧项目数据完好，模拟运行完整走完，连接失败与 SSRF 拦截均以界面提示呈现且无崩溃，默认界面无 Local Bridge。测试全程未改代码、未 git commit/push。
