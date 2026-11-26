# Round 03 Agent Report（合并代码审查修复 N-S1~S4, N-M2~M8）

依据：[docs/合并代码审查.md](../合并代码审查.md)

---

## 0. 检查点与前置清理

- 空目录清理：已检查并确认 `shared/shared` 已不存在。
- 检查点提交：`61cb823`（`merge: AI 积木 / Aj6Kit / ProxyPin / 桌面 MCP（审查前）`）

---

## 1. 逐项修复清单与新增测试

### 【1】N-S1 护栏配置 + N-T1 / N-T2 沙箱测试
- **修改文件**：
  - `shared/src/commonMain/kotlin/cn/dumate/a6studio/catalog/Catalog.kt:631-642`：`kitBoot` 改为在 `Kit.boot({...})` 内生成 `policy: { maxRuntimeMs, riskyActivityRe, forbiddenZones: [[2130, 2400]], skipKeywords: ["下单", "支付", "绑卡", "邀请好友", "开通"] }`，对齐 `Aj6KitRuntime.kt:245-263` 的 `cfg.policy` 读取路径。
  - `shared/src/jvmTest/kotlin/cn/dumate/a6studio/Aj6KitIntegrationTest.kt:18-60`：将 `aj6KitRuntimeParsesAndBootsInRhino` 与 `kitTemplatesInjectRuntimeAndParseCleanly` 改为在 `SimRuntime`（白名单沙箱 `initSafeStandardObjects` + `ClassShutter{false}`）中实际执行验证（N-T1 / N-T2）。
- **新增测试**：
  - `Aj6KitIntegrationTest.kitBootPolicyReachesRuntime`（`Aj6KitIntegrationTest.kt:62-87`）：在 `SimRuntime` 沙箱中将 `state.activity` 设为 `ConfirmOrderActivity`，执行 `kitBoot` + `kitGuard`，断言日志出现 `安全阀：危险页面 ConfirmOrderActivity` 且执行了 `按返回键`（`back`）。
- **单测验证**：`./gradlew :shared:jvmTest --console=plain --no-daemon` → `BUILD SUCCESSFUL in 31s`。

### 【2】N-S2 + N-M7 本地 MCP 服务鉴权、并发与外部补丁确认
- **修改文件**：
  - `shared/src/jvmMain/kotlin/cn/dumate/a6studio/bridge/DesktopMcpServer.kt:40-156`：
    - 启动时通过 `SecureRandom` 生成 32 字节（64 hex）随机令牌 `authToken`（仅存内存，不落盘），使用 `MessageDigest.isEqual` 校验 `Authorization: Bearer <token>`（缺失或错误返回 `401`）；
    - 校验 `Host` 仅允许 `127.0.0.1:<port>` 或 `localhost:<port>`（否则 `403`）；
    - 任何携带 `Origin` 头的请求一律返回 `403`；
    - 强制要求 `Content-Type: application/json`（否则返回 `415`）；
    - 使用 `requestBody.readNBytes(MAX_BODY_BYTES + 1)` 限制请求体不超过 1 MB（`1,048,576` 字节，超出返回 `413`）；
    - 线程池改为 `Executors.newFixedThreadPool(2)`；handler 整体 `try/catch` 返回 JSON-RPC `-32603` 并在 `finally` 中保证 `exchange.close()`；对非字符串 `method` / `name` 安全返回 `-32600` / `-32602`；
    - 新增 `mutateProjectSync` 钩子以支持在 Main 线程原子完成「读取 → `BlockPatch.guardAndApply` 校验 → 写入」。
  - `desktop/src/jvmMain/kotlin/cn/dumate/a6studio/desktop/Main.kt:161-202`：默认关闭本地 MCP 服务；通过 `state.onToggleLocalMcp` 按需启停，并通过 `runBlocking(Dispatchers.Main)` 同步执行补丁读取、校验与写入。
  - `shared/src/commonMain/kotlin/cn/dumate/a6studio/ui/StudioState.kt:121-170, 524-530`：新增 `localMcpEnabled`、`localMcpToken`、`hasExternalMcpChanges`、`pendingExternalDeviceConfirm`；「复制地址与令牌」一并复制 URL 与 Bearer Token；外部补丁生效后标记 `hasExternalMcpChanges = true`，真机运行 `runOnDevice()` 前强制拦截并要求用户在面板确认。
  - `shared/src/commonMain/kotlin/cn/dumate/a6studio/ui/AiTrafficPane.kt:46-105`：新增本地 MCP 服务开关卡片与外部修改真机运行确认横幅。
- **新增测试**：
  - `MergedFeaturesTest.mcpServerRejectsMissingTokenAndForeignOrigin`（`MergedFeaturesTest.kt:245-303`）：覆盖无令牌（`401`）、带 `Origin` 头（`403`）、`text/plain`（`415`）、>1MB 超大请求体（`413`）、畸形非字符串 `method`（返回 `200` + JSON-RPC `-32600`）。
- **单测验证**：`./gradlew :shared:jvmTest --console=plain --no-daemon` → `BUILD SUCCESSFUL in 31s`。

### 【3】N-S3 + N-M8 补丁递归白名单校验与标识符强校验
- **修改文件**：
  - `shared/src/commonMain/kotlin/cn/dumate/a6studio/ai/BlockPatch.kt:61-299`：
    - 递归白名单校验所有积木（含嵌套 `children` 与所有 `modules`）：积木类型必须在 `Catalog` 中存在、禁止 `rawJs`、禁止写入 `ParamKind.CODE` / `ParamKind.EXPR` 参数、`ParamKind.SELECT` 取值必须在 `options` 中、`ParamKind.NUMBER` 必须为 `ParamValue.Num`、标识符参数通过 `Diagnose.isJsIdentifier` 校验、参数 `key` 与插槽 `slot` 必须属于该积木定义；
    - `apply` 与 `guardAndApply` 在 `root` 和 `modules` 中定位目标积木或父节点，目标不存在时直接报错，绝不静默成功。
  - `shared/src/commonMain/kotlin/cn/dumate/a6studio/diagnose/Diagnose.kt:28-40`：公开 `Diagnose.identifierParams`（含 `varSet`, `varAssign`, `funcDef`, `funcCall`, `forRange`, `forOf`, `tryCatch` 等全部标识符参数映射）。
  - `shared/src/commonMain/kotlin/cn/dumate/a6studio/catalog/CodeGen.kt:58-66`：在 `generateBlock` 层强制校验标识符参数，不合法时生成注释 `// 非法标识符已跳过: ...` 而非可执行代码。
  - `shared/src/commonMain/kotlin/cn/dumate/a6studio/ui/StudioState.kt:911-916`：`applyRefinedToSelectedOrInsert` 仅当选中积木为 `kitClick` 或 `kitWaitFor` 时才执行 `UpdateParam`，否则插入新 `kitClick` 积木。
- **新增测试**：
  - `MergedFeaturesTest.blockPatchRejectsRawJsAndExprAndNested`（`MergedFeaturesTest.kt:200-243`）：覆盖顶层 `rawJs`、嵌套在 `forRange.children["body"]` 中的 `rawJs`、`varSet` 的 `EXPR` 参数与非法标识符、不存在的 `blockId`、非法 `SELECT`/`NUMBER` 参数，以及 `CodeGen` 对非法标识符生成注释的断言。
- **单测验证**：`./gradlew :shared:jvmTest --console=plain --no-daemon` → `BUILD SUCCESSFUL in 30s`。

### 【4】N-S4 深度抓包脱敏与云端发送预览确认
- **修改文件**：
  - `shared/src/commonMain/kotlin/cn/dumate/a6studio/security/Redaction.kt:44-243`：
    - 支持 HAR `{name, value}` / `{key, value}` 结构：当 `name`/`key` 命中敏感键名时自动将同对象的 `value` 脱敏为 `[REDACTED]`；
    - 新增 `header_line`、`cookie_pair`（覆盖 `pt_key`/`pt_pin`/`wskey`/`sid`/`*token`/`*key`）、`basic_auth` 三条正则规则；
    - 字符串以 `{` 或 `[` 开头时先尝试解析为 JSON 元素并递归脱敏；
    - 敏感键名新增 `key`、`wskey`、`pin`、`h5st`、`eid`、`uuid`、`openudid`；
    - 超大字符串先截断到 `64 KB`（`HARD_MAX_INPUT_CHARS = 65_536`）再单遍执行正则掩码。
  - `shared/src/commonMain/kotlin/cn/dumate/a6studio/ui/StudioState.kt:841-873` & `AiTrafficPane.kt:207-244`：发往云端模型（Gemini / OpenAI 兼容）前，先在 UI 生成并展示 `pendingCloudPreview` 脱敏载荷预览，用户点击「确认发送云端」后才发起请求。
- **新增测试**：
  - `MergedFeaturesTest.redactionHandlesHarHeadersAndCookieStrings`（`MergedFeaturesTest.kt:62-104`）：覆盖新增敏感键名、HAR `{name:"Cookie", value:"pt_key=..."}`、原始 Cookie 串与 Header 行、Basic Auth、字符串形式 JSON 内部 `wskey`/`h5st` 递归脱敏及 >64KB 超大输入截断。
- **单测验证**：`./gradlew :shared:jvmTest --console=plain --no-daemon` → `BUILD SUCCESSFUL in 32s`。

### 【5】N-M2 协程取消信号传播 + N-T4 测试隔离本机推理
- **修改文件**：
  - `shared/src/commonMain/kotlin/cn/dumate/a6studio/ai/LlmClient.kt:147-170`：移除吞掉 `CancellationException` 的 `runCatching`，改为显式 `catch (e: CancellationException) { throw e }` 后再捕获普通 `Exception`。
  - `shared/src/commonMain/kotlin/cn/dumate/a6studio/ui/StudioState.kt:832-833, 897-898`：`fetchProxyPinTraffic` 与 `runAiRefine` 均在 `catch (e: Exception)` 前先重抛 `CancellationException`。
  - `shared/src/jvmTest/kotlin/cn/dumate/a6studio/MergedFeaturesTest.kt:150`：测试中显式使用 `LlmConfig(allowLocalInference = false)` 关闭本地推理（N-T4），不再尝试连接本机 `11434` / `1234` 端口。
- **新增测试**：
  - `MergedFeaturesTest.llmRefinePropagatesCancellation`（`MergedFeaturesTest.kt:159-208`）：通过慢响应本地测试端点验证协程取消时 `LlmClient.refine` 原样抛出 `CancellationException`，不降级吞异常。
- **单测验证**：`./gradlew :shared:jvmTest --console=plain --no-daemon` → `BUILD SUCCESSFUL in 36s`。

### 【6】N-M3 / N-M4 / N-M5 / N-M6 护栏积木代码生成修复
- **修改文件**：
  - `shared/src/commonMain/kotlin/cn/dumate/a6studio/catalog/Catalog.kt:609-612, 678-738`：
    - `kitClick`：检查返回值 `_r`，当 `_r.reason === "guard" || _r.reason === "abort"` 时立即执行 `$kit.finish("failed"); exit();`，其余失败调用 `$kit.reportLeftover(value); $kit.stat("failed");`（N-M3 / N-M4）；
    - `kitWaitFor`：未命中时调用 `$kit.reportLeftover(value); $kit.stat("failed");`，并检查 `if ($kit.overBudget() || $kit.atRisk()) { $kit.finish("failed"); exit(); }`（N-M3 / N-M4）；
    - `kitWaitCountdown`：改为生成 `var _cd = $kit.waitCountdown({ fallbackDwellMs: ${minSec * 1000L}, graceMs: ${graceSec * 1000L} }); if (!_cd) { $kit.backToBean(); }`，去掉末尾多余的 `$kit.back()`（N-M5）；
    - **N-M6 方案选择说明**：在「从 `byOpts` 移除 `id`」与「修改运行时 `findAll`」两种方案中，**选择了方案一：从 `Catalog.kt` 的 `byOpts` 中移除 `id`（仅保留运行时原生支持的 `text` 与 `desc`），并在 `AuditController.kt:70-71` 与 `StudioState.kt:1158` 的 `applyAuditFixes` 中对 `kitWaitFor`/`kitClick` 仅接受 `text`/`desc`**。
- **新增测试**：
  - `Aj6KitIntegrationTest.waitCountdownUsesConfiguredDwell`（`Aj6KitIntegrationTest.kt:89-123`）：验证 `fallbackDwellMs` / `graceMs` 毫秒换算、`backToBean()` 条件调用、无多余 `$kit.back()`，并在 `SimRuntime` 中实测虚拟时钟按配置推进。
- **单测验证**：`./gradlew :shared:jvmTest --console=plain --no-daemon` → `BUILD SUCCESSFUL in 37s`。

---

## 2. 测试与构建汇总

### 2.1 `:shared:jvmTest`（来自 `shared/build/reports/tests/jvmTest/index.html`）
- **测试总数**：178
- **失败数**：0
- **忽略数**：0
- **总耗时**：10.914s
- **成功率**：100%

### 2.2 双端编译构建
- `./gradlew :desktop:compileKotlinJvm --console=plain --no-daemon` → **`BUILD SUCCESSFUL in 16s`**
- `./gradlew :androidApp:assembleDebug --console=plain --no-daemon` → **`BUILD SUCCESSFUL`**

---

## 3. 未纳入本轮指令范围的审查项说明

以下审查文档中的条目不属于本轮指令要求的【1】~【6】项，故本轮保持未改动：
- `N-M1`（`UrlSecurity.kt` 的 DNS 解析后 IP 校验与 userinfo/数字 IP 拦截）
- `N-M9`（`FlowIrImporter.kt` 的完整 `policy` 透传与递归深度限制）
- `N-M10`（`desktop/build.gradle.kts` `nativeDistributions` 显式声明 `jdk.httpserver` 模块）
- `N-M11`（`Aj6KitRuntime.kt` 真机 `ScriptInterruptedException` 重抛）
- `N-L1` ~ `N-L20` 轻微项
