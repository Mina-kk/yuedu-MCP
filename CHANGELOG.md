# 更新日志

按版本倒序记录对外可见的变化。每个版本的完整说明见 [GitHub Releases](https://github.com/Mina-kk/yuedu-MCP/releases)。

## [1.2.2] - 2026-10-23

**阅读端调试**
- 新增 `reader_web_page`、`reader_web_debug`、`reader_web_search`（工具总数 67 → 73）：在用户填写的阅读 Web 网页或调试通道里真实运行书源，不猜默认端口，端口只取用户保存的地址（回包 `originEcho`）。
- `reader_web_sync_source` 写入或确认存在后自动在阅读端调试一次；回包附 `deliveryStatus`、`readerExecution`。调试日志超过 2000 字符时只保留尾部（`debugLogsTruncated`），全量用 `reader_web_debug` 查看。
- 阅读端调试回包新增 `structuredResult`（`logVerdict`、`stagesReached`、`failureSignals`、各阶段 `*Reached`），可直接机读四阶段是否到达；全局 `reader_web_search` 改为可选，不再作为默认下一步。
- `reader_web_status` 区分网页可用（`web_page_available`）、接口不匹配、鉴权未通过（`status=auth_required`，能力记 `unauthorized`）等状态；接口不匹配不代表不是阅读，网页照常可用。
- 阅读 Web 地址按场景处理：同机可用回环地址（无需 Wi-Fi），异机用阅读显示的局域网地址；令牌与地址绑定、加密保存。

**规则引擎与沙箱**
- 修复 JSON 列表中字段规则使用 `$.x` 时报 `Unexpected End Of File`：列表项保留原始 JSON 对象，按字段规则类型解析。
- 字段解析失败时报错带字段名与规则（`fieldErrors`）。
- 修复 release 包中 Jsoup、JsonPath、XPath 对象在 `eval_js` 里方法「找不到函数」：补齐混淆保留规则。
- `check_source` 失败全部来自沙箱环境限制（缺 Android/Java 类、引擎内部异常）时判为 `inconclusive`，与规则真正失败的 `unusable` 区分，并给出 `failureKind`。
- `check_source` 回包字段 `readerSideVerified` 拆为 `readerSideExecuted`（阅读端执行过）与 `readerSidePassed`（四阶段到达且无失败信号）。

**工具与回包**
- `fetch_page` 等未传 `contextId` 时复用最近一次 `create_context` 创建的上下文，回包标 `contextIdSource`（`explicit`/`last_created`/`auto-created`）。
- `save_source` 回包精简为 `projectId`、`sha256`、`sourceChars`、`versionCount`、`conflict` 等，不再回传整份书源；全文用 `get_source`。
- `debug_source.refresh`、`eval_js.dumpVars` 的参数类型改为 boolean（仍兼容字符串 `"true"`）。
- `reader_web_sync_source`、`delete_projects`、`clear_cookies` 标注 `destructiveHint=true`。
- `get_app_info` 新增 `versionName`、`versionCode` 与 `buildRevision` 说明。
- `save_source` 的 `source` 参数同时接受 JSON 字符串与对象；`fields` 增量模式自动以同地址最新版本为基底合并。
- `fetch_page` 识别连接重置类错误为疑似限流（`suspectedRateLimit`）。

**文档**
- 技能包与参考文档同步上述口径：沙箱结论只作初筛，交付以阅读端执行结果为准；补充跨分支 `Packages.java.lang.Thread.sleep` 写法等常见坑。
- 去掉文档与知识库中的具体实例地址、写死端口与站点通过/失败结论。

## [1.2.1] - 2026-10-17

- `app_status` 新增 `toolUsage` 字段：当前服务运行期间各工具的调用次数、失败/取消数、耗时与返回文本量（UTF-16 字符与 UTF-8 字节）概览，另列按文本量排序的前 10 个工具。统计只含工具名与数值，不含调用内容；`app_status` 自身不计入；服务重启后清零。
- `fetch_page` 首次自动预览默认：普通连接 auto 为 6000 字符，`preview` 模式无论连接类型缺省 6000；`?compact=1` 会话的 auto 未传 `maxChars` 时降为 2000。显式 `maxChars`（0..12000）始终优先；`reference` 与 auto 命中缓存本就不回正文。正文仍完整保存在 `pageId`，可经 `read_page` 等引用恢复。
- 工程改进：复用页面预览与大结果引用的响应格式化代码，补充缓存引用和分页读取回归检查；现有接口与默认行为保持不变。
- MCP 页明确标识标准/精简连接模式，并统一接入信息复制与通知栏展示。
- 书源语料按身份键对旧底库与 YCK 候选整体去重、筛选和重建：主库 17743 条唯一身份书源、1544 个规则特征族；无法裁决的键与隔离候选不进入主库。语料序号和族编号已重新编排，不保证站点在线可用。

## [1.2.0] - 2026-10-14

- 改善 MCP 连接稳定性：修复主动终止会话（`DELETE`）及无会话请求校验失败后的服务端实例登记残留，并协调并发关闭与空闲回收。
- 新增 `?compact=1` 连接参数：`tools/list` 返回精简版工具目录以节省上下文；完整工具定义可经只读资源 `studio://tools/{name}` 按名读回。
- 整数型工具参数校验更严格：传入小数或超出范围的数字时会明确报错，不再静默使用默认值。
- `get_app_info` 新增 `buildRevision` / `buildType` 字段，便于问题反馈和版本诊断。
- REA 逆向分析参考文档更新至 v41，并补充第三方许可声明。
- 完善协议、会话生命周期及工具目录的自动化回归；默认单元测试与真实站点测试分离，构建方法见 `BUILDING.md`。

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
