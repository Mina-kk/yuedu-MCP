# 更新日志

按版本倒序记录对外可见的变化。每个版本的完整说明见 [GitHub Releases](https://github.com/Mina-kk/yuedu-MCP/releases)。

## [Unreleased]

- CI：新增 GitHub Actions，每次推送和 PR 都会运行单元测试并构建 debug APK（`.github/workflows/ci.yml`），不使用任何签名密钥。
- 文档：README「MCP 工具一览」补齐全部 67 个工具，新增「字体反爬」「抓包」分组。
- 测试：新增 `ReadmeToolTableContractTest`，双向校验 README 工具表与代码中注册的工具一致，并核对工具总数和版本号。
- 测试：`ZjreadLiveTest` 在本地没有 `work/zjread` 素材时自动跳过，不再让 CI 失败。
- 内部重构：`StudioMcpServer.kt`（1813 行）按领域拆分为 9 个工具注册文件（`McpProjectTools.kt`、`McpCaptureTools.kt` 等），工具名、描述、参数和注册顺序都不变。

## [1.1.008] - 2026-10-12

- 书源：人机验证错误不再被误报为网络超时；验证状态可短时复用，减少重复验证。
- 调试：`debug_source` 回传书源 java.log、缓存快照、正文字数和内容预览，报错时附带对应行内容。
- 上下文：调试会话失效后提示失效时间和最后活动时间，可续期继续使用。
- 新增工具：`webview_eval`、`webview_render`、`import_reader`（推送书源到阅读）、`archive_source_versions`（清理旧版本）。
- 书源管理：`list_sources` 支持按网址折叠，只看最新版；`check_source` 标明是否复用缓存及快照时间。

## [1.1.003] - 2026-10-02

- 图文验证码（image_code）闭环：`java.getVerificationCode(imageUrl)` 真实实现，`browser_verify` 新增 `imageUrl` 参数。
- `java.getStringList(...)` 返回真正的 JS `Array`，与官方阅读一致。
- 抓包魔数嗅探：无明确 MIME 的响应按 PNG/JPEG/GIF/WebP/WOFF/WOFF2/zip 等魔数归类，二进制资源正确落盘。
- 任务上下文 LRU 兜底：满 16 个任务时回收最久未用的空闲任务。
- 数据脱敏：HTTP/抓包导出对 `Authorization`、`Cookie`、`Set-Cookie`、`api-key` 等敏感头脱敏。

## [1.0.141] - 2026-09-22

- 知识库新增《第三方搜索逆向实战》专题；书源支持格式化导出并调用系统分享；扩充语料样本。
- 修复日志页全选删除偶发闪退；修复分享导出 JSON 的兼容性。

## [1.0.130] - 2026-09-19

- 悬浮窗改为可拖动的圆形 M 字悬浮球，贴边自动吸附半隐藏；新增书源 JS 入门教程知识包。

## [1.0.128] - 2026-09-13

- **不兼容变更**：MCP 鉴权统一为标准头 `Authorization: Bearer <令牌>`，不再接受 `X-Studio-Token`，旧配置需重新复制接入信息。
- MCP 页拆为「连接 / 设置 / 前置条件」三个页签；接入信息按客户端形态给出逐格填法。

## [1.0.126] - 2026-09-13

- 修复任务上下文存储初始化顺序缺陷；规则链路保留元素对象，裸 `@attr` 可取当前节点属性；多段落 `@text` 按官方语义拼接。
- 书源类型新增「自动」；`eval_js` 注入官方同名对象 `cookie` / `cache` / `source`；`set_cookie` 默认按名合并。

## [1.0.122] - 2026-09-12

- 内置 4256 个书源、696 个模板族；新增 `match_sources` / `get_corpus_source` / `get_corpus_shard`。
- 验证中心重构：结构化返回 `verification_required`，`browser_verify` 支持 `waitSec` 阻塞等待，新增每域验证模式。

## [1.0.119] - 2026-09-09

- 底栏新增「验证中心」入口，底栏顺序调整为 MCP / 书源 / 技能 / 验证中心 / 日志。

## [1.0.118] - 2026-09-09

- 新增任务上下文工具（`create_context` 等）；`fetch_page` 返回 `pageId` 可复用，大结果转 `resultId` 分段读取；会话可观测字段扩充。

## [1.0.111] - 2026-09-07

- 书源格式化 JSON 详情与一键复制；HTTP 日志按天分页；过滤本地回环与私网请求。

## [1.0.110] - 2026-09-06

- 应用内外观切换（浅色 / 跟随系统 / 深色）；修复深色模式下文字不可见等问题。

## [1.0.109] - 2026-09-06

- 修复开启服务开关时状态回弹。

## [1.0.108] - 2026-09-06

- MCP 状态卡展示局域网地址；HTTP 详情页隐藏底部标签栏。

## [1.0.107] - 2026-09-05

- 界面视觉优化；书源页按类别筛选；HTTP 详情页返回键精准回退。

## [1.0.105] - 2026-09-05

- `save_source` 同一 URL 默认覆盖，`newVersion=true` 才追加版本；技能支持启用/停用。
