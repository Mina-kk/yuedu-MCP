# 构建阅读书源 MCP

## 环境

- JDK 17
- Android SDK 36 / Build Tools 35+
- Gradle 8.13（可用工程自带的 `./gradlew`，或本机已安装的 Gradle 8.13）

## 调试构建

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## Release 签名构建

签名凭据只通过环境变量传入，不要写入仓库：

```bash
export RELEASE_STORE_FILE=/absolute/path/studio.jks
export RELEASE_STORE_PASSWORD='...'
export RELEASE_KEY_ALIAS=studio
export RELEASE_KEY_PASSWORD='...'
./gradlew clean :app:testDebugUnitTest :app:assembleRelease
bash scripts/verify_release_contract.sh
```

`verify_release_contract.sh` 会校验 Release 混淆 mapping 中 MCP/Runtime 的 JSON 契约类未被混淆。

不得把密码、密钥或 MCP Token 写入仓库。本 App 不保存任何模型密钥。
