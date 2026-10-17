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

## 全库去重重建语料

```bash
python -m unittest discover -s scripts -p 'test_*.py'
python scripts/reconcile_corpus.py \
  --base app/src/main/assets/corpus \
  --src /path/to/curated-sources.json \
  --stage /path/to/corpus-stage \
  --report /path/to/reconcile-report.json \
  --merge-source source-label
```

只把静态筛选后的主候选作为输入。工具按身份键对底库与新快照的全部候选整体去重并做版本仲裁，凡是规则实质相同的副本合并为一条；新库由 `build_corpus.py` 在全新 `--stage` 目录重建，序号 `i` 与族编号 `f` 整体重新编号，因此与旧编号不对应。同一身份键的规则不同时，先按静态质量级别取舍，同级别只采纳唯一的最高可信 `lastUpdateTime`；可信时间缺失、相等或冲突的整键转入待复核（`reviewKeys`），静态不合规的转入隔离（`quarantineKeys`），两者都不进主库，也不存在「照抄列表日期当最新」的推断。

`--stage` 必须是不存在或空的新目录，`--report` 也必须位于底库、输入与 stage 之外；底库与输入全程只读，不会被改写（临时文件只落在系统临时目录并在结束时清理）。先在 stage 里完成构建与整体校验，再备份现有 `app/src/main/assets/corpus`，然后整体切换（替换整个语料目录，不是原地覆盖分片），最后重新构建 APK 并跑测试。再次应用同一输入应得到相同的记录、族与序号（`rec`/`fam`/`n` 一致）；传 `--report` 时 `provenance.auditSha256` 取自含生成时间戳的报告文件，因此两次运行的 `index.json` 不必逐字节相同。索引中的 `provenance` 记录公开来源标签、计数和输入哈希，`mergePolicy` 为 `global-dedupe-rebuild`，不代表联网可用性证明。

重建后的底库（`provenance` 最后一条 `mergePolicy` 为 `global-dedupe-rebuild`）本身就是整体重建的结果，再次重建时会把全部 `n` 条记录作为候选，不存在需要另行声明的追加尾，因此无需 `--base-sources`；历史里遗留的旧 `firstSourceIndex` 只属于被替换的追加血缘，不会影响边界。仍停留在追加策略的底库（`provenance.firstSourceIndex`）继续保留原有的尾部去重保护，只把该边界之前的记录作为候选。`--base-sources` 仅在需要显式声明且与解析出的边界一致时才被接受。

`scripts/append_corpus.py` 是旧的增量追加策略（保留底库、新记录序号与族编号接续、原分片字节不变），仅作历史参考；其输出仍走同一套 staging、备份与整体切换流程。

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
