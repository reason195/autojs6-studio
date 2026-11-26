# tools/debug.keystore

**只给 debug 构建用的公开密钥，不是发布密钥。**

Android 默认给 debug 包签名用 `~/.android/debug.keystore`，而这个文件在每台机器、每个 CI runner 上都
是新生成的。结果是同一个版本号的 APK 签名各不相同，装过上一版的人无法覆盖升级
（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），每次 CI 出包都得先卸载。

把密钥提交进仓库（口令 `a6studio`，别名 `a6studio`）后，本地与 CI 签出来的包签名一致，可以互相覆盖安装。

- 生成命令：`keytool -genkeypair -keystore tools/debug.keystore -storetype PKCS12 -alias a6studio -keyalg RSA -keysize 2048 -validity 10000 -storepass a6studio -keypass a6studio -dname "CN=AutoJs6 Studio Debug, OU=debug, O=reason195, C=CN"`
- 正式上架前必须换成自己的 release key，并把这个文件从 debug 配置里摘掉。
