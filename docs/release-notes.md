# 发布记录

每个版本一节，标题固定为 `## v<版本号>`；`tools/release-notes.py` 靠它切分发布说明，
CI 在打 tag 时把对应小节连同产物的 SHA-256 一起写进 GitHub Release。

## v1.0.2 · 2026-11-24

合并审查 round-04 与双 MCP 网关审查的修复版本，重点是安全加固与护栏可靠性。

### ⚠️ 不兼容变更

- **补丁指令的类型名改为短名**：`add` / `update` / `remove` / `rename`。外部 AI、MCP 客户端或提示词里如果还写着完整类名（如 `cn.dumate.a6studio.ai.BlockPatchOp.RenameProject`），会直接解析失败，需要同步修改。

### 安全

- 出站地址校验重写：拒绝带用户信息的 URL，识别十进制 / 十六进制 / 八进制 IPv4 与完整 IPv6 写法，补全云元数据黑名单（含 AWS IPv6、阿里云），并在 DNS 解析后逐个地址再校验
- 双 MCP 网关与 ProxyPin 一律走解析后校验；只在「工具不存在」时才降级，不再吞掉鉴权失败、超时等错误
- 本地 MCP 服务：`studio_get_project` 改为按字符串值脱敏，返回的始终是合法 JSON；通知请求回 202
- 补丁通道禁止修改护栏策略；只校验补丁新增或改动的积木，已有的条件、表达式积木不再导致所有补丁被拒
- 外部 MCP 修改标记写入工程文件，重启后真机运行前仍需确认

### 护栏与运行时

- 真机上按停止不再被 Kit 运行时吞掉，等待循环会立刻退出
- 等倒计时没命中时，先判断超时 / 风控再回退，不再继续导航
- 导入 Jingdou IR 时保留原护栏策略（与默认值合并、只加严）；畸形输入不再报内部错误，认不出的节点保留为注释并在导入说明里列出
- 诊断新增：kitBoot 位置与作用域、Kit / $kit 保留名、正则可编译性

### 其他

- AI 面板可从 Gemini / OpenAI 兼容 / Ollama / LM Studio 拉取可用模型并选择，也可手动输入；Ollama / LM Studio 地址可在界面里修改
- 安装包运行时补上 `jdk.httpserver` 等模块，本地 MCP 在 MSI 安装版里可用；启动失败会显示原因
- 「Local Bridge」合成数据仅在开发者模式可见，带【合成数据】标记，且不能回填到积木
- 若干轻微问题修复（详见 `docs/agent-reports/round-04.md`）

### 下载

| 平台 | 文件 | 说明 |
| --- | --- | --- |
| Windows x64 | `AutoJs6Studio-1.0.2-windows-x64.msi`（约 66 MB） | **推荐**。双击安装，开始菜单和桌面有快捷方式；**需管理员权限**，重复安装会覆盖升级 |
| Windows x64（免安装） | `AutoJs6Studio-1.0.2-windows-x64.zip`（约 65 MB） | 解压后双击 `AutoJs6 Studio/AutoJs6 Studio.exe`，不写注册表 |
| Android | `AutoJs6Studio-1.0.2-debug.apk`（约 12 MB） | 直接安装。debug 签名，安装时需允许「未知来源」 |

三者都自带 JRE，无需另行安装 Java。macOS / Linux 需在对应系统上执行 `./gradlew :desktop:createDistributable` 自行构建。
SHA-256 由 CI 在构建后追加在本文末尾。

## v1.0.1 · 2026-11-22

只改发布流程与版本号，功能与 v1.0.0 当前附件一致——但**请下载 1.0.1**：v1.0.0 的附件被覆盖更新过多次，
哈希对不上任何固定内容，而 1.0.1 是第一个从 tag 到产物都能复现的版本。

- 版本号统一由 `gradle.properties` 的 `a6studio.version` 提供（桌面 `packageVersion`、Android `versionName`），
  `versionCode` 同步 +1
- 发布说明从 `build/`（本机、不入库）移到 `docs/release-notes.md`，按版本分节
- CI 改成「推 tag → 构建 → 计算 SHA-256 → 自动创建/更新 GitHub Release 并上传三件产物」，
  不用再手工 `gh release upload --clobber`
- 免安装 zip 由 `tools/make-zip.py` 生成，条目分隔符固定为 `/`；原来 CI 用的 `Compress-Archive`
  与本地产物分隔符不一致
- **Android 签名固定了**：以前用每台机器/每个 CI runner 各自生成的 `~/.android/debug.keystore`，
  同名版本换个来源签名就变，覆盖安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。现在统一用仓库里的
  `tools/debug.keystore`（公开的 debug-only 密钥），本地与 CI 的包可以互相覆盖升级。
  **装过 1.0.0 的话需要卸载一次**（数据可用 `pm uninstall -k` 保留），之后就能直接升级

### 下载

| 平台 | 文件 | 说明 |
| --- | --- | --- |
| Windows x64 | `AutoJs6Studio-1.0.1-windows-x64.msi`（约 66 MB） | **推荐**。双击安装，开始菜单和桌面有快捷方式；**需管理员权限**，重复安装会覆盖升级 |
| Windows x64（免安装） | `AutoJs6Studio-1.0.1-windows-x64.zip`（约 65 MB） | 解压后双击 `AutoJs6 Studio/AutoJs6 Studio.exe`，不写注册表 |
| Android | `AutoJs6Studio-1.0.1-debug.apk`（约 12 MB） | 直接安装。debug 签名，安装时需允许「未知来源」 |

三者都自带 JRE，无需另行安装 Java。macOS / Linux 需在对应系统上执行 `./gradlew :desktop:createDistributable` 自行构建。
SHA-256 由 CI 在构建后追加在本文末尾。

功能、已知限制与验证方式同 v1.0.0（见下一节），本版只改版本号与发布流程。

### 主要修复（自首次发布的补丁，已包含在 1.0.0 的当前附件里）

这一轮把代码审查出来的问题逐个修了，每个都有回归测试或实测证据（单测 63 → 90 项）：

- **导入 .js 不再打乱逻辑顺序**：原来的导入器先发积木再补原生代码，`let a = 1` 后面跟 `sleep(100)`
  会被写成 `sleep(100); let a = 1`，`if (ready) { ... toast('x') }` 里的 toast 更会被搬到条件外面无条件执行。
  现在按花括号/圆括号/字符串/注释跟踪上下文，同一行多语句、跨行调用、CRLF 换行都能正确落位。
- **模拟器不再把电脑交出去**：导入的脚本就是别人写的代码，模拟器原来可以直接
  `java.lang.System.getProperty('user.home')` 拿到活的 Java 对象，顺着就能 `Runtime.exec`、删文件。
  现在 Rhino ClassShutter 拦掉 `java.*` / `javax.*` / `jdk.*` / `com.sun.*` 等；`importClass` /
  `importPackage` 变成一条有说明的日志而不是报错。用到 Java 类的脚本请发到真机跑。
- **原生代码块可以多行编辑**：导入脚本产生的「原始 JavaScript 代码」参数换成等宽多行输入框
  （最多 14 行，内部滚动），37 行的函数不用再挤在一行里看。
- **界面不再被手机请求拖住**：关掉拾取器/体检面板会真的取消正在跑的 `ui_find`（单次最长 15 秒），
  体检重跑先掐掉上一轮，中途取消不会把「体检中」永久钉住。
- **中文不再变乱码**：MCP 的 SSE 回复按整段字节解码，跨 4 KB 分块的三字节汉字不会再裂成 U+FFFD
  （`ui_dump` 的中文标签、由它生成的选择器都受影响）。
- **数据不会静默丢**：项目文件读不出来时先备份成 `project.broken-<时间戳>.json` 再载入示例，并提示
  备份路径；保存失败（磁盘满、权限）不再假装「已保存到本机」。
- 其他：地址带尾斜杠时令牌不再失效、地址规范化统一；桌面令牌文件权限 0600；Android 相对路径
  与桌面一样限制在应用私有目录内；拾取器与代码生成共用一份转义（控制字符、U+2028/29 一致）；
  删掉不生效的「生成 async 函数」开关（Rhino 1.9.2 不支持）；去掉五处 `!!` 崩溃点。

## v1.0.0 · 2026-11-21（已被 v1.0.1 取代，请用 1.0.1）

可视化、模块化编写 AutoJs6 脚本的工作台，一套 Kotlin 代码同时产出 **Windows / macOS 桌面端**与 **Android App**。

### 下载

| 平台 | 文件 | 说明 |
| --- | --- | --- |
| Windows x64 | `AutoJs6Studio-1.0.0-windows-x64.msi`（66 MB） | **推荐**。双击安装，开始菜单和桌面有快捷方式；**需管理员权限**（装到 Program Files），重复安装会覆盖升级 |
| Windows x64（免安装） | `AutoJs6Studio-1.0.0-windows-x64.zip`（64 MB） | 解压后双击 `AutoJs6 Studio/AutoJs6 Studio.exe`，不写注册表 |
| Android | `AutoJs6Studio-1.0.0-debug.apk`（11 MB） | 直接安装。debug 签名，安装时需允许「未知来源」 |

三者都自带 JRE，无需另行安装 Java。macOS / Linux 需在对应系统上执行 `./gradlew :desktop:createDistributable` 自行构建。

SHA-256：

```
47ab68034b0afeff382ef8b3ed9be00e218e3b69c11ff755fed301de703bd98e  AutoJs6Studio-1.0.0-windows-x64.zip
ae654a9e6c826d6a04bcce22c66817877a86a95c72fab9c9710d2433c73d064e  AutoJs6Studio-1.0.0-windows-x64.msi
0882524db1f13b8eaab92816d0c8cb2f355658989e42302cefe4cfd04dab8bbb  AutoJs6Studio-1.0.0-debug.apk
```

### 本版重点

- **运行不会再卡死界面**：模拟和测试都在后台线程跑，模拟手机画面实时刷新；Rhino 按指令数轮询检查，纯 JS 死循环也能被打断。
- **可停止 / 超时保护**：顶栏「▶ 运行」在运行中变成「■ 停止」（`Shift+F5`，真机模式下会请求手机停止脚本）；单次脚本默认 20 秒墙钟预算（`sleep` 的虚拟时间不算），超时后状态显示「超时」并在诊断里给出「可能卡在死循环」的结论。
- 停止后不会只留一个空界面：日志、模拟屏幕停在最后一帧，状态分别为「已停止」/「超时」。
- **Windows 安装包（MSI）可用了**：`./gradlew :desktop:packageMsi` 直接产出 MSI，带开始菜单项、安装目录选择和固定升级 GUID。

### 主要能力

- **画布编辑**：左侧点积木追加，参数就地编辑，右侧代码实时高亮；长按卡片左侧 `⠿` 拖动排序
- **模拟运行**：内置模拟手机，用 **Rhino**（与 AutoJs6 同引擎）执行真正生成的脚本，不接手机也能跑通
- **测试**：每个用例是一段可执行断言（`$setScreen` / `$state` / `$run` / `$log` / `assert`），运行中可停止
- **诊断**：静态规则 + 运行日志回放，问题可点击跳转（含运行超时/异常归类）
- **真机 MCP**：连接 AutoJs6 跑脚本、读回控制台
- **控件拾取**：从界面树点选，按 `id → text → desc` 合成选择器并验唯一；支持截图取坐标、框选下钻
- **选择器体检**：逐个校验项目里所有选择器，能自动收窄的给一键修好
- **真机录制**：把手机操作转成积木

### 简单模式（默认开启）

面向完全不懂代码的人：首次启动 5 步向导、5 个可直接运行的模板、67 个积木各配通俗说明、参数带换算提示（`1800000 = 30 分钟`）、诊断与日志改写成人话（新增「跑太久被自动停下了」等状态）。需要完整能力时点顶栏「高级」。

### 真机连接（实测于魅族 17 Pro / Android 13 / AutoJs6 6.8.0）

手机上要先做两步，默认都是关的：

1. AutoJs6 → **插件** → MCP Server 开关打开
2. 同页「**设置**」→ 打开「**允许局域网连接**」，并复制该页的令牌

然后在桌面端「真机」页填入 `http://<手机IP>:9637/mcp` 和令牌。实测 ping 366–540ms，`device_info` / `ui_dump` / `script_run` 全部连通。

### 已知限制

- 模拟器是常用 API 的行为近似，真机差异（H5 渲染时机、无障碍树刷新、厂商弹窗）只能在真机上验证
- 本版 Rhino 为 1.9.2（AutoJs6 用 2.0.0-SNAPSHOT），不支持 `class` / `async`；生成模板已避开这些语法，并有语法守卫测试
- 超时预算按真实时间计，脚本里大量 `sleep` 不会触发超时（虚拟时钟），但真机上同样会真的等待
- MSI 的安装信息（程序描述）只能是英文：jpackage 把它写进 MSI 摘要信息，WiX 3.x 遇到非 ASCII 会以 `light.exe ... exit 311` 失败；中文说明保留在应用界面与本文档
- Android 为 debug 签名，仅供测试；上架需自行签名
- macOS/Linux 安装包未随本版提供

### 验证情况

- `./gradlew :shared:jvmTest`：**90 项单元测试全部通过**（代码生成与旧版逐行对比、语法守卫、树操作/撤销、模块作用域、Rhino 运行完整示例、死循环超时与手动停止、三个内置测试用例、拾取器、录制、MCP SSE 解析等）。
- Windows 发布物三件套同一次构建产出并逐一核验：MSI 用 `msiexec /a` 解包检查 `.cfg`（skiko 路径 / resources 目录各一条、无重复），再从解包布局启动跑通京东领券模板；zip 解压后同样启动运行正常；APK 由 `assembleDebug` 重新构建。
- 桌面端逐页截图核对过：代码页、运行页（3 个任务完成 → 空弹窗 → 收工）、测试页、诊断页、真机页、帮助页、体检与录制弹层、760px 窄屏布局。
- APK 已在一加 CPH2581 与魅族 17 Pro 安装运行；真机 MCP 链路（ping / device_info / ui_dump / script_run / console_tail）在魅族 17 Pro 实测连通。

旧版纯前端实现完整保留在仓库 `legacy-web/` 目录。
