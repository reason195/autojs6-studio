# Android 端

AutoJs6 Studio 本身是纯前端应用，Android 上有三种用法：

## 1. 浏览器直接打开（零安装）

把整个目录拷到手机（`/sdcard/AutoJs6Studio/`），用 Chrome / Edge / 夸克以「文件」方式打开 `index.html`。编辑、运行模拟、测试、诊断都可用。

## 2. PWA 添加到主屏幕（推荐）

电脑上起一个静态服务：

```bash
cd AutoJs6Studio
python -m http.server 8790
```

手机与电脑同一 Wi-Fi，访问 `http://<电脑IP>:8790`，浏览器菜单里「添加到主屏幕」。之后像 App 一样全屏打开，工程存在本机 `localStorage`。

## 3. Capacitor 原生壳

```bash
npm init -y
npm i @capacitor/core @capacitor/cli @capacitor/android
npx cap init "AutoJs6 Studio" com.a6studio.app --web-dir=.
npx cap add android
npx cap open android   # Android Studio 里 Build APK
```

`web-dir` 指向工程根目录即可；`capacitor.config.json` 的 `webDir` 设为 `"."`，`appId` 自定义。

## 真机联调

在 Android 上打开时，浏览器访问 `http://127.0.0.1:8765` 会指向手机自身；若要把脚本送到另一台跑 AutoJs6 的手机，请填那台机器的 `IP:端口`，或先在本机跑 `bridge/mcp-bridge.mjs` 做 CORS 转发。