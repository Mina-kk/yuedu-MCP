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

CI 见 `.github/workflows/ci.yml`：每次推送到 main 或提交 PR 时运行上面同一条命令，测试报告和 debug APK 作为构建产物保留 7 天。CI 不接触 Release 签名。

## 单测范围：默认不访问真实网站

- 默认 `:app:testDebugUnitTest` 整类排除标注 `@Category(LiveNetworkTest::class)`（`com.mina.legadostudio.testsupport.LiveNetworkTest`）的真实网站测试，当前为 `ZjreadLiveTest`，不是“跳过”。MockWebServer 等本地回环用例属于默认范围，照常执行。新增真实网站测试也必须加此标记；该分类不是操作系统级网络沙箱。
- 真实联网用例只在显式命令下运行，并且依赖本地 `work/zjread` 素材（`zjread_js_lib.js`、`rhino_probe.js`）；显式开启后素材缺失会直接失败，不会静默跳过：

```bash
./gradlew :app:liveTestDebug                                              # 全部联网用例
./gradlew :app:liveTestDebug --tests '*ZjreadLiveTest.pbkdf2Timing'      # 单个用例
```

- `liveTestDebug` 会自动注入 `-Dlegadostudio.liveTests=true`（测试内的第二道 opt-in 门闩），maxParallelForks=1，每次执行（不走 up-to-date / 构建缓存），报告与二进制结果与默认任务隔离：`app/build/reports/tests/liveTestDebug`、`app/build/test-results/liveTestDebug`。
- `--offline` 只约束依赖解析（离线、不下载依赖），不影响测试本身是否联网。

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
