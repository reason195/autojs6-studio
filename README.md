# AutoJs6 Studio（原生版）

可视化、模块化编写 AutoJs6 脚本的工作台。**一套 Kotlin 代码，两个原生 App**：
Windows / macOS / Linux 桌面端用 Compose Desktop，Android 用 Jetpack Compose。
界面、积木库、代码生成、模拟运行时、MCP 桥接、拾取器、体检、录制全部共享同一份 `shared` 模块。

> 旧的纯前端版本（`index.html` + 浏览器 / Electron / PWA）完整保留在 [`legacy-web/`](legacy-web/)，
> 功能对等，可继续使用；本仓库根目录现在是原生工程。

## 构建与运行

需要 JDK 17+（开发用 21）、Android SDK（platform 35+）。

```bash
# 桌面端（Windows / macOS / Linux）
./gradlew :desktop:run

# 桌面可分发包（Windows 出 msi，macOS 出 dmg，Linux 出 deb）
./gradlew :desktop:packageReleaseDistributionForCurrentOS

# Android APK
./gradlew :androidApp:assembleDebug
# 产物：androidApp/build/outputs/apk/debug/androidApp-debug.apk

# 核心逻辑单测（38 项）
./gradlew :shared:jvmTest
```

其中 `LegacyParityTest` 会把 Kotlin 代码生成器的输出与旧 Web 生成器逐行对比。先生成夹具
（`legacy-web/js/codegen.js` 原样运行）：

```bash
node tools/legacy-gen.mjs     # 写出 build/crosscheck/legacy*.js
./gradlew :shared:jvmTest
```

没有夹具时该用例会打印 SKIP 并跳过，其余 33 项照常执行。

Android 首次构建需要在仓库根目录放 `local.properties` 指向 SDK：

```properties
sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
```

桌面端布局预览/回归截图（无需人工看屏幕）：

```bash
A6STUDIO_SCREENSHOT=/tmp/shot.png ./gradlew :desktop:run
# 可加 A6STUDIO_SCREENSHOT_PANE=run|test|diag|device|help
#   与 A6STUDIO_SCREENSHOT_ACTION=run|tests|audit|recorder
# 窄屏（手机）布局：A6STUDIO_WIDTH=760 A6STUDIO_HEIGHT=900
```

## 工程结构

```
shared/                      一套代码，两端复用
  src/commonMain/            平台无关：模型、积木目录、代码生成、诊断、桥接、拾取/体检/录制、Compose UI
    model/                   Project / Block / TreeOps / 示例项目 / 持久化接口
    catalog/                 60+ 积木定义 + AutoJs6 代码模板 + 代码生成器
    runtime/                 运行时类型 + RunEngine 接口 + 模拟设备状态机
    diagnose/                静态规则 + 运行日志回放
    bridge/                  MCP over HTTP 客户端（Ktor）
    picker/                  控件拾取：候选合成、唯一性校验、兜底表达式、区域下钻
    audit/                   选择器体检（逐条校验 + 可一键修好）
    recorder/                真机录制（设备侧监听脚本 + 事件转积木）
    ui/                      Compose UI：三栏布局、画布、六个页签、三个弹层
  src/jvmAndroidMain/        Rhino 引擎（桌面与 Android 共用）
  src/jvmMain/               桌面：项目存储 + 图片解码
  src/androidMain/           Android：项目存储 + 图片解码
desktop/                    Compose Desktop 壳（窗口 + AWT 文件对话框）
androidApp/                 Jetpack Compose 壳（SAF 文件读写 + 剪贴板）
legacy-web/                 旧 Web 版（HTML/CSS/JS + Electron 壳 + MCP 转发 + Mock）
```

关键点：**模拟运行用 Rhino**（AutoJs6 本身就是 Rhino 引擎），模拟器执行的是真正生成的脚本，
而不是手写的解释器。注意版本差异：AutoJs6 6.8.0 用 Rhino `2.0.0-SNAPSHOT`，Maven Central 上
最新正式版是 `1.9.2`，因此本工程用的是 1.9.2 —— 它支持 `let`/`const`/箭头函数/模板字符串/
可选链/解构/`for…let of`，但**不支持** `class`、`async/await`、`for (const x of …)`。
`GeneratedSyntaxTest` 会逐个编译积木生成的代码，防止生成这些引擎不认的语法。

## 给完全不懂代码的人（默认模式）

打开就是「简单模式」，不需要懂任何术语：

- **首次启动有 5 步向导**：分别讲清楚「这是干什么的 / 先看模板 / 怎么运行看效果 / 怎么改成自己的 / 出错怎么办」，每步一屏，右上角可跳过。
- **模板库代替空白页**：5 个现成脚本（打个招呼、打开 App 并点按钮、搜索框输入、自动下滑、京东领券完整流程），每个都标明「需要手机 / 可直接运行」，选完直接能跑。
- **积木说人话**：67 个积木各有一句「它到底干什么」的说明；简单模式下只显示 10 个入门积木（打开 App、点按钮、输入、等待、返回…），高级积木收在「高级」里。
- **参数有解释**：`1800000` 旁边就写「保持多久，1800000 = 30 分钟」。
- **报错翻译成怎么办**：诊断结果和技术日志都改写成可操作的话，例如
  `未找到控件: text("关闭") ← 屏幕上看不到这个文字，可能已经变了`；
  运行状态直接显示「跑完了（模拟用了 40s）」而不是 `完成 · 模拟 40s`。
- **点「运行」自动跳到结果**：手机上不用自己去翻面板，跑完就看得到模拟手机和日志。
- **页签只留三个**：运行 / 检查问题 / 帮助。代码、测试、体检、录制、真机都移到「高级」。

需要完整能力时点顶栏「高级」切换，选择会被记住。所有新手文案集中在
`catalog/Friendly.kt`（积木说明）和 `ui/BeginnerCopy.kt`（错误与状态改写），改文案不用碰逻辑。

---
## 界面

三栏：左侧积木库、中间画布、右侧六个页签（代码 / 运行 / 测试 / 诊断 / 真机 / 帮助）。
窄屏（手机）自动切换成「画布 / 积木 / 面板」三个可切换视图。

1. **画布**：点左侧积木追加到选中位置（选中容器就进容器，选中普通积木就插到它后面），
   卡片上的参数就地编辑，右侧代码实时刷新并高亮该积木生成的代码行（含子积木）。
   长按卡片左侧 `⠿` 上下拖动可排序，`↑ ↓ ⧉ ✕` 分别是上移/下移/复制/删除。
2. **运行**：默认在内置模拟设备上执行生成的脚本，手机屏幕跟着动、日志逐行输出；
   勾选「发送到真机」则通过 MCP 把脚本发给 AutoJs6。
3. **测试**：每个用例是一段可执行断言，可用 `$setScreen` / `$state` / `$run` / `$log` / `assert`。
4. **诊断**：静态规则（空定位、无 `auto.waitFor()`、无收工判定、缺弹窗重读、网络无异常保护等）
   + 运行日志回放。
5. **拾取**：参数旁 `⌖` 连真机取控件，按 `id → id 正则 → text → desc → textContains` 合成候选，
   逐个 `ui_find` 验唯一并评分；可写 `by/value`、单表达式、或 `A.exists() ? A : B` 兜底链。
   截图视图按控件 bounds 叠热区，点空白取坐标，点两下取滑动起止点，拖拽框选区域下钻。
6. **体检（F8）**：把项目里所有 `by/value` 选择器逐个校验：唯一 / 命中多个 / 未找到（附
   `ui_explain_selector` 的首个失败条件）；命中多个时从命中节点反推候选并给一键修好。
7. **录制（F9）**：把监听脚本推到手机，在手机上操作，回 IDE「读取并生成」把点击/滑动/按键转成积木。
   也支持「导出脚本」离线录制 + 「导入文件」。
8. **多设备**：常用设备地址写进项目，下拉直接切换；令牌默认只在内存，可勾选记住（写本机存储）。

## 快捷键

`F5` 运行 · `F6` 测试 · `F7` 诊断 · `F8` 体检 · `F9` 录制
`Ctrl+S` 保存 · `Ctrl+Z` 撤销 · `Ctrl+E` 导出 JS · `Esc` 关闭面板/取消选中
`Delete` 删除选中积木

## 积木覆盖的 AutoJs6 能力

| 类别 | 覆盖 |
| --- | --- |
| 流程 | 注释、`auto.waitFor()`、`console.show`、`log`、`toast`、`sleep`、变量、if/else、for、for…of、带保护的 while、try/catch、函数、调用、重试、`exit()` |
| 应用/设备 | `app.launchPackage` / `app.openUrl` / `app.kill`、`currentPackage`、`waitForPackage`、`device.keepScreenOn` / `device.vibrate`、剪贴板、`shell` |
| UI 无障碍 | `text/desc/id/className/textContains/textMatches` 定位、`findOne` / `exists` / `waitFor`、点击 / 长按 / 输入 / 读文本、滚动、`back` / `home`、坐标点击 |
| 手势 | `swipe`、列表上滑、左边缘右滑切回上个应用、`press` |
| 图像 / OCR | `images.requestScreenCapture`、`findImage`、`captureScreen`、`ocr.recognizeText`、截图保存 |
| 数据 | `files` 读写、`storages`、`http.get` / `http.postJson`、`threads.start` |
| 调试 | 断言、计时、控件摘要 |
| 实战模板 | 京东领券中心 8 步流程（进入 / 签到 / 开浮层 / 遍历任务 / 执行任务 / 跨 app / 关弹窗重读 / 收工判定） |

> 标注「⚠ 依赖插件」的能力（如 YOLO 检测）未内置积木，避免生成不存在的 API。

## 内置示例

示例项目按 `https://www.dumate.cn/s/LTkRUX1b` 里三个 go.dshtui.com 分享会话（同一次
`jd_频道_领券中心.js` 调试的第 2/3/4 段）沉淀的实测规则搭成：

- 入口固定深链 `pro.m.jd.com/mall/active/VAjs3vpayA513UwxL5XC4eGBXqY/index.html`；
- 签到标志物是按钮从「天天领豆」变成「更多任务」，`id=coupon-left-entry` 文案会变，坐标不能缓存；
- 完成任务会弹无文字空弹窗，**必须先关弹窗再重读**，否则任务数读成 0 会误收工；
- 浮层首屏可能全是抽奖/券，0 任务要下滑扫描（最多 12 轮）后再判收工；
- 跨 app 任务跳到京东金融后立即手势右滑切回，在京东落地页等倒计时。

模拟器把这套状态机做进去了，点「运行」能真的走完：签到 → 浮层 → 3 个任务 → 空弹窗 → 收工。
三个内置测试用例也全部通过（`./gradlew :shared:jvmTest` 覆盖）。

## 与 AutoJs6 6.8.0 的实测对齐

参考 AutoJs6 官方仓库（`SuperMonster003/AutoJs6`，v6.8.0）与旧版构建对话里记录的真机实测结论，
本次原生移植逐条核对并落实：

| 实测结论 | 本项目中的落点 |
| --- | --- |
| AutoJs6 用 Rhino `2.0.0-SNAPSHOT`（支持 class / async / optional chaining） | 桌面与 Android 共用同一 Rhino 引擎跑模拟；`GeneratedSyntaxTest` 保证每个积木生成的代码都能被本引擎解析 |
| `for (const x of …)` 在 Rhino 1.9.x 里**解析失败**，`for (let x of …)` 正常 | `forOf` 积木改为生成 `let`；这就是新增语法守卫发现的真实缺陷 |
| 魅族触摸事件按 **1/100 像素**上报（`7606,30482` → `1080x2340` 屏） | 设备侧脚本先归一化再判点击/滑动，导入侧 `normalizeEvents` 再兜一次（两层保险） |
| `files_read` 的 `encoding` 只能传 `utf-8`，`utf8` 会报 `INVALID_ARGUMENTS` | 桥接固定传 `utf-8`，并把 `INVALID_ARGUMENTS` 翻译成可读提示 |
| 首次工具调用返回 `PAIRING_REQUIRED`（手机上 59 秒内点允许） | 桥接把该错误单独分类，界面提示「现在去手机上点允许，然后重连」 |
| `screen_capture` 限流 30 次/分钟 | 分类为 `RATE_LIMITED` 并带上 `retryAfterMs` |
| `ui_find` 返回的 selector 用 bounds 固定节点，**不适合写进脚本** | 只用它验「能否点到」；真正写回积木的选择器由我们按 `id → idMatches → text → desc → textContains` 自行合成并逐个验唯一 |
| 手机 MCP 端口在听但不应答（录制脚本占住主线程） | 超时错误给出「停止手机上脚本、重启 MCP Server」的具体指引 |
| AutoJs6 工作目录不存在导致 `HOST_ERROR` | 归为该错误码并提示在手机建「脚本」文件夹 |
| `ui_dump` 上限 400 节点 | 超限时提示改用区域下钻（`ui_find` + `boundsInside`） |

---
## 真机桥接（已实测连通）

**先在手机上做两件事**，否则一定连不上（这两条是实测踩出来的）：

1. AutoJs6 → **插件** → MCP Server 的开关必须是**打开**的（关着时端口可能还残留监听，连接会超时）。
2. 同页「**设置**」→ 打开「**允许局域网连接**」（默认关闭！关着时手机本地能应答、电脑连不上）。
3. 在同一个设置页复制「**令牌 / Token**」，粘贴到 Studio 真机页的令牌框。

地址填 `http://<手机IP>:9637/mcp`（手机 IP 在设置页「当前局域网地址」里直接看得到）。

### 实测记录（魅族 17 Pro / Android 13 / AutoJs6 6.8.0 / MCP 插件 1.3.1）

| 环节 | 结果 |
| --- | --- |
| Ping 握手 | 366–540 ms |
| device_info | `meizu 17 Pro`、Android 13 (API 33)、无障碍已开启、电量 100% |
| ui_dump | 正常返回界面树与当前窗口 |
| script_run | `status: finished`，控制台回读到脚本输出 |

### 为什么以前一次都没连上（四个真实 bug，已修）

1. **SSE 解析漏掉注释帧**：服务端把 `:` 注释帧和 `data:` 放在同一块里返回
   （`":

id: 8
event: message
data: {…}"`），旧解析器只认行首 `data:`，拿到空结果。
   现在由 `SseFrames` 正确分帧，并有单测锁住这个格式。
2. **keep-alive SSE 读不到头**：`bodyAsText()` 会一直等到连接关闭，而 SSE 不关，
   于是每次都在 socket 超时（实测 15 秒）后报「手机无响应」——真实原因是被自己等死了。
   现在读到一块就尝试解析，拿到完整 JSON 立刻返回。
3. **device_info 是嵌套结构**：实际字段是 `build.model` / `host.versionName` /
   `accessibility.operational`，旧代码按平铺字段找，所以界面永远显示 `-(-)`。
4. **错误信息误导**：401 被翻译成「手机无响应」，空响应体也一样，导致排查方向完全跑偏。
   现在分别提示「去手机复制令牌」和「检查插件开关 / 局域网开关」。

### 自检命令

```bash
# 真机活体测试（会真的连手机跑一段脚本）
A6_MCP_URL=http://<手机IP>:9637/mcp A6_MCP_TOKEN=<令牌>   ./gradlew :shared:jvmTest --tests "cn.dumate.a6studio.LiveMcpTest"
```

没设环境变量时该用例自动跳过，不会影响常规测试。

## 真机桥接

AutoJs6 插件中心启用「MCP Server」，在「真机」页填地址：

- 真机直连：`http://<手机IP>:9637/mcp`
- 或经本机中转：先跑 `legacy-web/bridge/mcp-bridge.mjs`（带 CORS 的透明转发），再填
  `http://127.0.0.1:8765/mcp`

调用链是 `initialize` → `notifications/initialized` → `tools/call`，同时兼容 JSON 与
`text/event-stream` 响应。令牌走 `Authorization: Bearer`，默认只存内存。

没真机也能试拾取器：`node legacy-web/tests/mock-mcp.mjs` 起一个假设备（内置 9 个节点，
故意放两个同名「立即领取」验证「不唯一不允许直接填入」）。

## 已验证与未验证

**已在本次改版中实测：**

- `./gradlew :shared:jvmTest` 49 项通过（代码生成、树操作/撤销、模块作用域、Rhino 运行完整示例、
  三个内置测试用例、拾取器候选/评分/兜底链/区域层级、录制解析与坐标归一化）。
- `./gradlew :androidApp:assembleDebug` 产出 11.8 MB debug APK。
- `./gradlew :desktop:run` 启动成功，逐页截图核对过：代码页（含语法高亮般的基础着色与选中行高亮）、
  运行页（模拟手机跑完整流程，日志显示 3 个任务完成、空弹窗、收工）、测试页（3 个用例可见可编辑）、
  诊断页、真机页、帮助页、体检弹层、录制弹层、以及 760px 窄屏的手机布局。

- 简单模式（首次向导 5 步、模板库 5 个模板、积木人话说明、报错翻译）已在桌面端与
  一加 CPH2581 / 魅族 17 Pro 上截图核对；模板「选完直接运行」的闭环在两端都跑通，
  手机端显示「跑完了（模拟用了 40s）」。

**真机 MCP 已验证（2026-11-22，魅族 17 Pro）**：Ping 366–540ms、device_info、ui_dump、
script_run 全部跑通；界面右上角显示「真机: meizu meizu 17 Pro」。

**尚未在真机上验证：**

- MCP 连接、拾取器取控件、体检、录制的真机链路——本机没有挂 Android 设备，只做了协议实现与
  纯逻辑单测（用 `legacy-web/tests/mock-mcp.mjs` 的夹具数据）。上真机后的行为以 AutoJs6 为准。
- macOS 打包（需要 Mac 环境）、Android release 签名。

**已知差异（相比 legacy-web）：**

- 画布暂未实现「从积木库拖拽建新积木」，改为点击追加 + 长按卡片拖动排序（桌面和手机手势都可用）。
- 模拟器的 API 覆盖是行为近似，不是完整 AutoJs6 引擎；真机差异（H5 渲染时机、无障碍树刷新、
  厂商弹窗）只能在真机上验证。
- 示例项目全部用表达式定位京东页面，因此「体检」对示例项目显示 0 个可体检项——这是预期行为，
  自己拖入 `clickNode` 这类用 `by/value` 的积木后才有体检对象。

## 边界（沿用旧版实测结论）

真机录制对系统状态有要求，实测魅族 17 Pro（Android 13 / AutoJs6 6.8.0）：

- `events.observeTouch()` 注册不报错，但 `onTouch` 一次都不触发，录出的文件只有 `start` / `ready`
  两行——系统没把触摸分发给无障碍服务（9 个无障碍服务同时开启时 AutoJs6 抢不到分发）。
- `observeTouch()` 一旦常驻，**同进程的 MCP Server 就不再应答**（端口在听、HTTP 全超时）。所以本版
  是「录制期不通信」：开始录制 → 在手机上操作 → 在手机上停止脚本 → 回页面「读取并生成」。

录制不可用时，⌖ 拾取器、导入文件、adb 直取都能用；详见 `legacy-web/README.md`。