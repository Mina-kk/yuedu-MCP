# 构建与开发

## 环境要求

- JDK 17 或兼容版本
- Android SDK 36，Build Tools 35 或更高版本
- 工程自带的 Gradle Wrapper（Gradle 8.13）

以下命令在仓库根目录运行。Windows 可将 `./gradlew` 替换为 `.\gradlew.bat`。

## 调试构建与测试

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

APK 输出至 `app/build/outputs/apk/debug/app-debug.apk`，测试报告位于 `app/build/reports/tests/testDebugUnitTest/`。

GitHub Actions 配置见 `.github/workflows/ci.yml`，运行单元测试并构建 Debug APK，不使用 Release 签名凭据。

## 构建标识

`get_app_info` 的 `buildRevision` 和 `buildType` 用于版本诊断。源码提交标识按以下顺序读取：

1. Gradle 参数 `-PbuildRevision=<commit>`。
2. 环境变量 `GITHUB_SHA`，供 GitHub Actions 使用。
3. 未指定时使用 `development`。

提交标识需为 7–40 位小写十六进制字符；也允许开发默认值 `development`。

```bash
./gradlew :app:assembleDebug -PbuildRevision="$(git rev-parse HEAD)"
```

发布构建应使用干净源码并指定准确的提交标识，避免诊断信息与实际源码不符。

## 真实站点测试

默认单元测试排除 `LiveNetworkTest` 类别的真实站点测试，本地回环 HTTP 和 MockWebServer 用例正常执行。新增真实站点测试也应使用该类别。

```bash
./gradlew :app:liveTestDebug
```

当前站点测试需要自行准备 `work/zjread/zjread_js_lib.js` 和 `work/zjread/rhino_probe.js`；这些本地材料不随仓库提供。显式执行时缺少材料会报错。运行前应审查脚本，并确认允许访问对应站点。

联网测试单独生成报告至 `app/build/reports/tests/liveTestDebug/`，不复用旧测试结果。类别过滤不是网络沙箱；`--offline` 只限制 Gradle 依赖下载，不会阻止测试代码发起 HTTP 请求。

## MCP 接入兼容性

服务使用 Streamable HTTP 传输和 `initialize` 握手，当前可协商 `2024-11-05`、`2025-03-26`、`2025-06-18`、`2025-11-25` 协议版本。尚不支持 [2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/basic/lifecycle) 的逐请求版本元数据及 `server/discover` 模式。

鉴权为静态 Bearer 令牌，不提供 OAuth 流程。当前 SDK 的 Host/Origin 检查按主机名匹配，不能视为严格同源或完整 CORS 策略；局域网及远程接入应保留令牌校验并限制访问范围。

## Release 签名构建

签名凭据通过环境变量提供。以下为占位示例，请勿将实际密码、密钥或访问令牌提交到仓库或写入公开日志。

```bash
export RELEASE_STORE_FILE=/absolute/path/to/keystore.jks
export RELEASE_STORE_PASSWORD='your-store-password'
export RELEASE_KEY_ALIAS='your-key-alias'
export RELEASE_KEY_PASSWORD='your-key-password'
./gradlew :app:testDebugUnitTest :app:assembleRelease \
  -PbuildRevision="$(git rev-parse HEAD)"
bash scripts/verify_release_contract.sh
```

产物位于 `app/build/outputs/apk/release/app-release.apk`。`verify_release_contract.sh` 检查混淆后 MCP/Runtime 的 JSON 契约是否保持。

自行签名的 APK 与官方包证书不同，不能直接覆盖官方安装。官方更新包使用一致证书；升级前建议备份重要书源和设置。本 App 不保存任何模型密钥。
