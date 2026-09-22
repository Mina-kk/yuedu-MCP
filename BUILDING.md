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

## 构建纪律

在 CI / 打包环境出正式 APK 时遵守，避免多个构建并发把机器压垮：

- **单飞**：全局同时只允许一个构建（测试 + assemble + 验约）。发起构建前先确认没有其他 Gradle 在跑。
- **flock 硬锁**：构建命令必须套远程锁，拿不到锁立即失败，不要排队硬闯：

  ```bash
  cd ~/workspace/yuedu-MCP && \
  flock -n /tmp/yuedu-mcp-build.lock bash -c "./gradlew :app:testDebugUnitTest :app:assembleRelease && bash scripts/verify_release_contract.sh"
  ```

- **内存上限**（`gradle.properties` 已设）：`org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m`、`kotlin.daemon.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=384m`；`org.gradle.parallel=false`、`org.gradle.workers.max=2` 不要调高。构建前留意 `free -m`，可用内存低于 ~2GB 时先释放再构建。

不得把密码、密钥或 MCP Token 写入仓库。本 App 不保存任何模型密钥。
