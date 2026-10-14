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

## MCP 本地 HTTP 协议回归

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*McpProtocolHttpIntegrationTest' \
  --tests '*McpApplicationContractTest' \
  --tests '*McpSessionReaperIntegrationTest' \
  --tests '*McpCompactToolsContractTest' \
  --offline --no-build-cache --rerun-tasks
./gradlew :app:testDebugUnitTest :app:assembleDebug --offline --no-build-cache --rerun-tasks
./gradlew :app:liveTestDebug --offline
```

`McpProtocolHttpIntegrationTest` 的 14 个用例使用真实 `embeddedServer(CIO)`（`127.0.0.1`、动态端口）与 CIO 客户端，经过生产 `configureStudioMcp` 门禁，注册独立 SDK fixture（counter 工具、静态资源、资源模板）。不依赖 Android、不访问公网、不登记或重置全局 `McpSessions`；请求和整用例均有超时，最后关闭客户端、fixture Server 和引擎。JSON / SSE 响应按 JSON-RPC id 解析，不依靠正文子串判断成功，也不锁定响应 Content-Type。

当前依赖仍为 `kotlin-sdk-server:0.15.0`，没有升级依赖或修改生产兼容逻辑。回归锁定的是以下实测 legacy 行为，不是对所有协议要求的符合性声明：

- `initialize` 正文版本 `2024-11-05` / `2025-03-26` / `2025-06-18` / `2025-11-25` 均同值协商；`notifications/initialized` 返回 202 空正文。同一会话持续调用 counter，工厂只创建一次；未知正文版本回退到 `2025-11-25`。
- 后续非法 `MCP-Protocol-Version` 头返回 400 / `-32000`，缺头兼容；另一个已支持版本的头也被接受，SDK 检查支持集合而非与协商值严格一致。
- 每次后续请求仍验证 Bearer；`/health` 免鉴权，响应有 `Cache-Control: no-store`。Host / Origin 的恶意主机、`null` Origin、畸形值被拒绝，无 Origin 被允许；允许主机名但端口不同的 Host / Origin 也被接受，这是 SDK hostname-only 匹配的行为记录，不是严格同源安全保证。
- 缺会话为 400 / `-32000`，保留请求 id；未知会话为 404 / `-32000` 且省略 id；重复 initialize 为 400 / `-32600`。SDK helper 对无会话请求会先创建 fixture Server 再拒绝，测试也关闭这些实例。语法错误为 400 / `-32700`，primitive 为 400 / `-32600`；这些错误省略 id 而非显式 `null`。未知方法为 200 / `-32601`，typed params 类型错误为 200 / `-32602`，均保留 id；断言同时检查 result/error 互斥。
- 工具 schema / annotations / 文本结果与资源列表、模板、读取结果的外层 uri / mimeType 被核对；未知工具返回 `isError` 工具结果，未知 URI 为 200 / `-32002`。Accept 必须同时包括 JSON 和 SSE，否则 406 / `-32000`；非 JSON Content-Type 为 415 / `-32000`，这两种传输前置错误省略 id。

[Current 2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/basic/lifecycle) 已改为逐请求 `_meta` 版本 / 能力字段与 `server/discover`，没有 initialize 握手。当前 SDK 不支持该 modern 路径：`2026-07-28` 传输版本头被拒绝，legacy 会话内 `server/discover` 为未知方法，无会话请求仍要求 initialize；已建立 legacy 会话仅携带 modern `_meta` 的 tools/list 可以成功，但这只是被 legacy 处理，不能当作 modern 支持。正文版本回退握手同样不代表支持 Current。

这些用例验证的是 JVM 上生产 HTTP 包装与可控 SDK fixture，不覆盖 Android 服务生命周期 / 真机网络，也不覆盖应用实际工具实现、完整 Current 规范或外部 MCP 客户端验收。APK 构建成功不等于这些边界已通过；显式 live 测试的结果也须与默认本地回归分开看待。

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
