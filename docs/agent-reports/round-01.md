# AutoJs6 Studio 第一轮收尾报告

## 范围

本轮继续执行 `docs/plans/2026-04-22-autojs6-studio-implementation-plan.md`，重点收尾 D2（L 项修复）、D3（验证）、E1-E4（告警清零检查）、F1-F2（提交与报告）。

## 本轮实际修复

| 项 | 状态 | 说明 | 验证 |
|---|---|---|---|
| L1 | 已修复（前序提交） | `detectAll` 先校验 selector，再做端口检查/调用；失败不覆盖旧选择器。 | `AuditControllerTest` |
| L2 | 已修复 | SSE 读取改为增量扫描，只在新增字节窗口检查帧边界，避免截图 base64 流 O(n^2)。 | `SseKeepAliveTest.sseReaderIncrementalScanHandlesLargePayload` |
| L3 | 已修复 | `hierarchyDump` 对 child count / children 数量做上限（512/256），超限截断并附 note。 | `DeviceHierarchyTest.hierarchyDumpCapsLargeNodeFanout` |
| L4 | 已修复 | `buildHierarchy` 跳过 depth<0 / 空 block；日志输出字符串转义。 | `DeviceHierarchyTest.buildHierarchySkipsInvalidEntriesAndEscapesText` |
| L5 | 部分修复 | MCP 错误消息中对 URL userinfo/query 中的 token/key/secret/password 做脱敏；initialize 错误隐藏 sessionId 明文。未做全量日志审计。 | 代码审查；现有 bridge 测试通过 |
| L6 | 已修复（前序提交） | desktop/server 不捆绑 Node.js；README 标注 Node.js 为可选。 | 文档/打包配置审查 |
| L7 | 未验证 | 需要真机/插件原生流式 SSE 验证。代码路径保留 SSE 流式读取，但本轮无真机。 | 未跑真机 |
| L8 | 已修复 | `RunController` stop/clearLogs/disconnect 加 `@Synchronized`，避免运行态并发写。 | `RunControlTest` |
| L9 | 已修复 | Console `userScrollEnabled` 改为 `derivedStateOf` 跟随滚动位置；仅在贴底时自动滚动。 | 代码审查；desktop 测试通过 |
| L10 | 已修复 | `SelectorInputPanel` IME composition 期间不覆盖 TextFieldValue，避免中文输入法候选区被重置。 | 代码审查；desktop 测试通过 |
| L11 | 未完整修复 | `nodeTextVar` 仍把变量名拼入日志标签（非 JS 注入，但未做标识符校验）；var/fn 参数标识符校验、CodeGen 插值校验未系统补齐。 | 未新增专门测试 |
| L12 | 已修复 | `scrollNode` 先 `findOne(2000)`，空节点时 log，不再对 null 直接滚动。 | 相关生成测试通过 |
| L13 | 已修复（前序提交） | simulated source 和目录远端路径用 `Js.quote`；失败结果清理 pendingRun。 | `RunControlTest` |
| L14 | 已修复 | 选择器体检报告对 `|`、换行、CR 转义，避免 Markdown 表格错位。 | `AuditControllerTest.reportEscapesMarkdownCells` |
| L15 | 已修复 | `captureLayout` 使用 `createTempFile(...).toFile()`，避免固定 `/tmp` 路径。 | 代码审查；desktop 测试通过 |
| L16 | 未修复 | `$run` 在同一 Simulator scope eval，子脚本顶层 `var` 仍污染父脚本。需定义隔离语义，暂未改动行为。 | 未改 |
| L17 | 已修复 | GitHub Actions checkout pinned to full SHA。 | workflow 审查 |
| L18 | 已修复 | MSI 目录构造后校验 staged files，缺失即失败。 | 脚本审查 |
| L19 | 已修复 | 代码库版本号升级到 1.1.0（build.gradle.kts、android versionName/versionCode、shared.mjs、android/Dockerfile、README、BUILDING、verify-installers、build-msi、release-notes）。 | 一致性检查 |
| L20 | 部分修复 | 清理 SimRuntime 重复 `getPackageName`、`httpResult` 重复分支和未转义 URL JSON；修复 `openTextFile` extensions filter。未全量清理报告列出的所有死代码点。 | desktop/shared 测试通过 |
| L21 | 已修复 | simulated device disconnect/restart 清理 uiWatcherJob/pendingRun/lastOutputIsVolatile。 | `RunControlTest.simDisconnectClearsWatcherAndPendingState` |
| L22 | 已修复 | `openTextFile` 应用 extensions 过滤，文件读取放到 `Dispatchers.IO`。 | desktop 编译/测试通过 |
| L23 | 已修复 | 录制流 expected-idle timeout 缩短为 10s，超时提示检查设备与无障碍。 | `RecorderControllerTest` 通过 |
| L24 | 已修复（前序提交） | `ensureJbr21` 支持 macOS/Linux JBR 下载与 JAVA_HOME 设置。 | 脚本审查 |

## D3 验证

执行命令：

```bash
./gradlew :shared:jvmTest --console=plain --no-daemon
./gradlew :desktop:jvmTest --console=plain --no-daemon
./gradlew :shared:jvmTest :desktop:jvmTest --console=plain --no-daemon --warning-mode all
```

结果：

- `:shared:jvmTest`：**BUILD SUCCESSFUL**
- `:desktop:jvmTest`：**BUILD SUCCESSFUL**
- 联合测试：**BUILD SUCCESSFUL**
- 本轮新增的编译告警：已清理（`GeneratedSyntaxTest` Rhino deprecation 通过 `@file:Suppress("DEPRECATION")` 处理）。

## E1-E4 告警清零

- **E1/E2（构建配置/编译 deprecation）**：项目代码范围内未见新增 deprecation warning。`--warning-mode all` 仍报告 `The Provider.forUseAtConfigurationTime method has been deprecated`，堆栈来自 Gradle/插件内部，非本仓库 build script 直接调用；本轮未升级插件链，记录为外部阻塞。
- **E3（真实 JVM 启动 smoke）**：未在本轮执行桌面 GUI 真机 smoke；JVM 单测已通过。
- **E4（VS Code Bridge）**：未在本轮启动真实 VS Code extension host 验证；本轮未改 bridge extension 行为。

## F1 提交

本轮变更作为收尾提交提交，提交前已运行 `:shared:jvmTest :desktop:jvmTest`。

## 已知阻塞 / 剩余项

1. **L7 真机验证**：需要 Android 真机 + AutoJs6 MCP 插件，验证原生 SSE 流式与大截图/大日志场景。
2. **L11 标识符校验**：var/fn 参数、CodeGen 插值、nodeTextVar 标签需要系统性标识符校验/安全引用。
3. **L16 `$run` 隔离**：需要定义子脚本作用域语义，避免破坏现有用户脚本预期。
4. **L20 剩余死代码/小问题**：报告中提到的部分死代码未全量清理，需逐条确认行为影响。
5. **Gradle plugin deprecation**：`Provider.forUseAtConfigurationTime` 来自插件链，需升级 Gradle/AGP/KMP/Compose 版本组合后复查。
6. **E3/E4 真实 smoke**：桌面 JVM 启动 smoke、VS Code bridge 真机 smoke 未执行。

## 结论

本轮完成了 D2 中可在无真机环境验证的大部分 L 项修复，并恢复 D3 测试全绿。主要剩余风险集中在真机桥接（L7）、代码生成标识符安全（L11）、模拟器 `$run` 作用域语义（L16）以及插件链 deprecation。
