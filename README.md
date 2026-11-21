# AutoJs6 Studio

可视化、模块化编写 AutoJs6 脚本的工作台。**三端同一套代码**：Windows / macOS 用浏览器或 Electron 壳，Android 用浏览器或 PWA 加入主屏幕。

## 直接打开

- Windows / macOS：双击 `index.html`（Chrome / Edge / Safari）。
- Android：把整个目录拷到手机，用文件管理器以浏览器打开 `index.html`；或在电脑上 `python -m http.server 8790`，手机访问 `http://<电脑IP>:8790`，再从浏览器菜单「添加到主屏幕」。

## 三端打包

| 平台 | 方式 | 命令 |
| --- | --- | --- |
| Windows / macOS | Electron 壳 | `cd desktop/electron && npm install && npm start` |
| Windows / macOS | 纯浏览器 | 直接打开 `index.html` |
| Android | 浏览器 / PWA | 打开 `index.html` 或添加主屏幕 |
| Android | Capacitor 原生壳 | 见 `mobile/README.md` |

## 四个核心页

1. **画布**：左侧点一下积木就追加到选中位置；也可以拖拽。积木卡片上的参数就地编辑，右侧代码实时刷新。**选中任意积木（含容器），右侧代码会高亮它生成的行**，容器会连同子积木一起高亮。
2. **运行**：默认在内置模拟设备上执行生成的脚本，手机屏幕会跟着动；勾选「发送到真机」则通过 MCP 桥接把脚本发给 AutoJs6。
3. **测试**：每个用例是一段可执行断言，可用 `$setScreen` / `$state` / `$run` / `$log` / `assert`。
4. **诊断**：静态规则（空定位、无 `auto.waitFor()`、无收工判定、缺少弹窗重读等）+ 运行日志回放。
5. **拾取（P0+P1+P2）**：积木参数旁的 `⌖`，连着真机时直接从界面树点选控件；工具会按 `id → id 正则 → text → desc → textContains` 合成选择器，逐个用 `ui_find` 验唯一性并给稳定性评分，确认后写回 `by/value`。切到「截图」可以看真机画面、点绿框选控件，点空白处直接取坐标（坐标类积木写 `x/y`）；**在截图上拖拽可框选一块区域下钻**，只列该区域的节点，节点树因此被 400 条上限截断时也能继续找目标，「清除区域」恢复全量。没有唯一候选时会提示最高分候选与命中数，而不是让你盲填。拾取结果还能写成**表达式**：一键写 `id("x")` 这类单表达式，或按候选评分生成 `A.exists() ? A : B` 兜底链（文案变了还有下一条兜住）；带 `fx` 标记的积木会优先用表达式，而不是 `by/value`。**滑动（swipe）积木的四个坐标也能 `⌖`**：在截图上点两下取起止点（或点两个控件取其中心），画面标 `①` `②` 并连线，一键回填 `x1/y1/x2/y2`；点第三下重新取起点，「重取」清空。写入可 `Ctrl+Z` 撤销。真机没连时也可以用 `tests/mock-mcp.mjs` 假设备练手。

6. **体检（P3）**：顶栏「⌕ 体检」（`F8`）把项目里所有 `by/value` 选择器逐个丢给 `ui_find` 校验——唯一、命中多个、还是根本找不到。找不到时会调 `ui_explain_selector` 告诉你卡在哪个条件；命中多个时从命中节点反推候选，验到唯一就给出「可改为 …」并支持一键修好（先快照，`Ctrl+Z` 可退）。面板顶部给「N 个选择器 · 唯一 x · 问题 y · 可修 z」，「复制报告」输出 Markdown 表格；每行右侧「拾取器」直接跳到该积木的拾取流程。体检只读页面、不改脚本。

7. **真机录制（方案 1）**：顶栏「● 录制」（`F9`）会把一段监听脚本 `script_run` 到手机上（不等待结束），你在手机上点按、滑动、按返回/Home，事件被写成 JSONL，IDE 轮询 `files_read` 实时列出，点「生成积木」把每段操作转成积木：点按优先用**设备侧算好的唯一选择器**（`id`→`text`→`textContains`→`desc`），没有唯一选择器就退成 `点击坐标`；滑动写 `swipe(x1,y1,x2,y2,dur)`，返回/Home 写 `按键`。生成前自动快照，可 `Ctrl+Z`。已知边界：触摸监听拿不到长按时长，长按会被记成点击；要求无障碍服务处于开启状态。

8. **鉴权与直连**：设备页多了「令牌（可选，只存内存）」——把 `mcpServers` 里的 Bearer token 粘进去即可，桥接会带 `Authorization` 头；令牌**不写进项目文件**，刷新页面即清空。浏览器直连要求服务端 CORS 放行 `authorization` 头，若服务端不放行，用随包的 `bridge/mcp-bridge.mjs` 代理（它已放行）。

9. **多设备**：常用设备写在 `js/config.js` 里（默认已放魅族 `192.168.1.17:9637`、一加 `192.168.1.6:9637`），打开应用、空项目、示例项目都会带上，设备页下拉直接选；换 IP/换机器改这个文件即可。设备地址写进项目，令牌默认只在内存（切换设备自动带回，刷新要重填）；需要省事就勾上「记住令牌」，它会把令牌写进本机浏览器存储并按地址回填（公用电脑别勾，取消勾选即清除）。
10. **真机直连（LAN）**：设备页可填 `http://<手机IP>:9637/mcp` + 令牌，绕过本机中转直连手机；首次连接手机会弹「确认 AutoJs6 Studio」，59 秒内点允许即完成配对。触摸坐标实测有「1/100 像素上报」的机型（魅族），录制脚本与导入都会自动归一化到 `device.width/height` 尺度。
11. **离线录制（不依赖 MCP）**：「录制」面板里的「**导出脚本**」会下载 `a6studio-record.js`——传到手机用 AutoJs6 打开运行，操作手机即开始录制，按**音量下键**停止，产出 `/sdcard/studio-record.jsonl`；把该文件拷回电脑，用面板「**导入文件**」选中它，再点「生成积木」即可。这条路径不需要 MCP 连接、不需要配对，适合通道不通时先用。

快捷键：`F5` 运行、`F6` 测试、`F7` 诊断、`Ctrl+S` 保存、`Ctrl+Z` 撤销、`Ctrl+E` 导出 JS、`Ctrl+K` 搜索积木。

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

> 标注「⚠ 依赖插件」的能力（如 YOLO 检测）未内置积木，避免生成不存在的 API；需要时用「输出表达式」写原生调用。

## 内置示例与三个分享链接

示例项目按 `https://www.dumate.cn/s/LTkRUX1b` 里三个 go.dshtui.com 分享会话（同一次 `jd_频道_领券中心.js` 调试的第 2/3/4 段）沉淀的实测规则搭成：

- 入口固定深链 `pro.m.jd.com/mall/active/VAjs3vpayA513UwxL5XC4eGBXqY/index.html`；
- 签到标志物是按钮从「天天领豆」变成「更多任务」，`id=coupon-left-entry` 文案会变，坐标不能缓存；
- 完成任务会弹无文字空弹窗，**必须先关弹窗再重读**，否则任务数读成 0 会误收工；
- 浮层首屏可能全是抽奖/券，0 任务要下滑扫描（最多 12 轮）后再判收工；
- 跨 app 任务跳到京东金融后立即手势右滑切回，在京东落地页等倒计时。

模拟器把这套状态机做进去了，因此示例脚本点「运行」能真的走完：签到 → 浮层 → 3 个任务 → 空弹窗 → 收工。

## 真机桥接

1. AutoJs6 插件中心启用「MCP Server」，记下地址。
2. 如果浏览器直连被跨域拦截，启动随包桥接：

```bash
cd bridge
MCP_UPSTREAM="http://<手机IP>:<端口>/mcp" node mcp-bridge.mjs
# 浏览器里填 http://127.0.0.1:8765/mcp
```

桥接只是一层带 CORS 的透明转发，不改协议；调用的是 `initialize` → `notifications/initialized` → `tools/call`（`device_ping` / `device_info` / `script_run` / `console_tail` / `ui_dump` / `ui_find` / `ui_explain_selector` / `ui_current_window`）。

### 没真机也想试拾取器

```bash
node tests/mock-mcp.mjs          # 127.0.0.1:8792，内置 9 个假节点
# 真机页地址填 http://127.0.0.1:8792/mcp，点连接
```

Mock 里故意放了两个同名「立即领取」，用来验证「不唯一时不允许直接填入」这条规则。

## 真机录制的限制（实测）

录制走的是设备侧脚本监听触摸，对系统状态有要求，不是所有机型都能用。实测魅族 17 Pro（Android 13 / AutoJs6 6.8.0）：

- 脚本能启动，`events.observeTouch()` 与 `events.onTouch()` 注册也不报错，但 `onTouch` 回调**一次都不触发**，录出来的文件只有 `start` / `ready` 两行。
- 原因是系统没有把触摸事件分发给无障碍服务（`settings get secure touch_exploration_enabled` 为 `0`），而这台机同时启用了 9 个无障碍服务，AutoJs6 抢不到分发。
- 另外，`observeTouch()` 一旦常驻，**同进程的 MCP Server 就不再应答**（端口在听、HTTP 请求全超时）。所以本版是「录制期不通信」：点开始录制 → 在手机上操作 → 在手机上停止脚本 → 回页面点「读取并生成」。

录制不可用时，下面几条都在同一台机上验证过：

- **⌖ 控件拾取器（推荐）**：点积木参数旁的 ⌖，从界面树选控件，或在截图里点控件/点空白取坐标，唯一性校验通过后直接填进积木；支持框选下钻与 swipe 两点取点。
- **导入文件**：把手机上的 JSONL 用「导入文件」导回，同样生成积木，完全不依赖 MCP。
- **adb 直取**：手机连 USB 时可直接 `adb -s <设备号> shell cat "/storage/emulated/0/脚本/studio-record.jsonl"`，绕开 MCP，适合排查。

## 目录

```
index.html          入口
css/app.css         样式
js/catalog.js       积木定义 + 代码生成模板
js/config.js        本机常用设备（改这里即可）
js/store.js         项目模型 / 树操作 / 持久化 / 示例项目
js/codegen.js       代码生成器
js/runtime.js       模拟运行时（AutoJs6 API 桩 + 京东状态机）
js/diagnose.js      静态规则 + 运行日志诊断
js/bridge.js        MCP 客户端
js/picker.js        控件拾取器（界面树、选择器合成、唯一性校验、回填）
js/audit.js         选择器体检（逐条校验、解释失败原因、批量修好）
js/recorder.js      真机录制（设备侧监听脚本 + 事件转积木）
js/editor.js        画布、拖拽、参数绑定
js/app.js           装配、运行/测试/诊断、导入导出
bridge/             MCP 转发（Node，可选）
tests/              Mock MCP 服务 + 拾取器单测（不依赖真机）
desktop/electron/   桌面壳
mobile/             Android 打包说明
```

## 边界

- 模拟运行时覆盖的是常用 API 的**行为近似**，不是 AutoJs6 引擎；真机差异（H5 渲染时机、无障碍树刷新、厂商弹窗）只能在真机上验证。
- 真机调用依赖 AutoJs6 MCP 插件与网络可达，失败时页面会保留模拟运行能力。
- 生成的是标准 AutoJs6（Rhino）脚本，可直接导入 AutoJs6 运行。