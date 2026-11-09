# 重构执行日志 / 验证基线

- 项目根：`/root/projects/yuedu-MCP-v2`
- 分支：`refactor/rust-vue-reader-semantics`
- 起点：`e129d35e7cba08f8dcacc2cf05dc3e626b5965dd`
- 正常 GitHub git 连接超时；公开仓库经 ghfast 仅下载无凭据 Git pack，随后 remote 改回官方 URL，逐一按官方 GitHub API 树核对 1970 个 blob SHA，无差异。
- JDK17、SDK36、Build Tools36 已安装；Gradle8.13 来自腾讯镜像，与官方 distribution SHA256 `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78` 一致。
- Rust/WASM target/wasm-bindgen、Node/npm 和 Android emulator 均存在，版本有工具输出。现有模拟器不清除其他项目数据。
- 规则研究拉取 14 个候选元数据及固定提交源码；首轮+补充 365 文件，分支筛选见 branch-selection.md。
- 完整源码 Python baseline：94 tests，1 fail（Windows大小写假设），无skip。初次局部快照的 corpus skip 不再存在。基线完整日志位于本 profile reports/yuedu-python-baseline.log。
- Android baseline Gradle 正在首次下载依赖；尚未报告通过。
- `gpt-6-astra` 与 `Mistral-deepseek-v4-1-flash-1017` 均在配置服务 catalogue 中，真实 chat probe返回 OK。CLI工具第二回合遭 Mistral 严格schema拒绝 reasoning_content；未修改全局Hermes，使用任务内回环严格schema adapter，仅移除拒绝字段，不改模型ID/消息内容/tool calls。adapter先RED测试再GREEN 2tests，并实际Hermes terminal→model往返成功。
- worker分别独占：规则研究、JS/请求研究、Web宿主、Rust核心、旧UI清理；前端视觉实现等待其他准备完成后读取用户正在写入的vault最新提交。

以上是执行证据记录，不是最终验收。任何未完成项不得被本日志视作通过。
