# 更新日志

按版本倒序记录对外可见的变化。已对外发布版本的完整说明见 [GitHub Releases](https://github.com/Mina-kk/yuedu-MCP/releases)。1.3.0–1.3.8 均仅本地测试，未在 GitHub 单独发布；下列对应条目仅作内部迭代说明，不构成已发版本事实。

## [1.3.9] - 2026-10-31

1.3.x 线首次对外 GitHub Release。1.3.0–1.3.8 均仅本地测试，没有单独的 GitHub Release。

- 语料对第三方认证字段及私网地址做脱敏。
- 继承 1.3.8 本地测试迭代的工坊与沙箱修复。
- 本版工坊与沙箱侧改动**未在真机或阅读端实测**；不以沙箱或本机构建声称阅读端通过。

## [1.3.8] - 本地测试迭代（未在 GitHub 单独发布）

本版工坊与沙箱侧改动**未在真机或阅读端实测**；不以沙箱或本机构建声称阅读端通过。

- `get_http_logs` 传入 `contextId`（含 `cap:` 抓包会话）时，`query` 按 url/finalUrl 先过滤再分页；超长回包改为 `resultId` 引用读取。
- 阅读调试日志对超时、JS 异常、Cloudflare 类信号分类，并摘要关键行。
- `reader_web_sync_source` 的 `debugTimeoutSec`（15–180 秒）贯通旧版 WebSocket 调试通道。
- 旧版 WebSocket 调试在空闲超时、总超时或关闭失败时回传失败原因。
- 沙箱 `java.ajax` 解析 URL 后的 JSON 选项。
- `jsLib` 静态提示覆盖旧分支装载差异，不自动改写规则。
- 工坊 `webview_eval` 支持等待 Promise 的设计实现（仅 eval；阅读书源 `webJs` 不变），须在目标 WebView 环境实测。
- 沙箱遇到 Cloudflare 拦截、JS 超时或错误正文时不再判成功。以上为工坊与沙箱侧修复，不以阅读端实测通过作为本版结论。

## [1.3.7] - 本地迭代（未单独 GitHub 发布）

- 目录卷名（空 URL 首项）不再误判为无效章；`auto` 取首个非卷名有效章。
- 搜索/发现空列表且站点明确 403+Cloudflare 或 HTTP 520 时，沙箱取证分类更准确，不把站点拦截当规则错误。以上为沙箱取证边界，不以阅读端实测通过作为本版结论。

## [1.3.6] - 本地迭代（未单独 GitHub 发布）

- 抓页回包补充请求 URL、最终 URL，并沿用既有 URL 跳转链（仅字符串，不含逐跳 HTTP 状态）。
- `inspect_rule` 支持 `countOnly`（只回命中条数）与 `limit`（截断 values 前 N 项，count 仍为全量）。
- 网页快照元数据增加 `expiresInMs`，便于判断上下文是否过期。
- 阅读 Web 自动调试内联日志裁剪与原记录采集截断分开：内联只保留尾部，完整记录走 `read_result`。
- 阅读网页元素扫描上限与本次回包 limit 分离；同步保留相对旧版的字段别名。
- `list_sources` 增加 `query`：按书名/URL 子串过滤后再分页。
- `get_app_info(detail=false)` 只回版本/包名/能力，缺省仍含完整 workflow。

## [1.3.5] - 本地迭代（未单独 GitHub 发布）

- 修复同页目录源在沙箱 `auto` 链路中被误判失败：未配置独立 tocUrl 且详情页即目录页时，回填详情页 URL 后可继续推进目录/正文；显式 tocUrl 规则取空不兜底。

## [1.3.4] - 本地迭代（未单独 GitHub 发布）

- 修复 Release 首次 `inspect_rule` / `validate_source` 类初始化抛 `ExceptionInInitializerError`，随后同进程全是 `NoClassDefFoundError`。模板改为字符串扫描，校验器花括号正则移出 `<clinit>`。Android ICU 把未转义 `}` 当量词是未用真机首次 cause 验证的假设，不是已证实根因。
- 组件加载失败仍返回旧中文「运行组件加载失败」，并附加 `code=APP_COMPONENT_LOAD_FAILED`；异常链脱敏 URL/令牌，进程内保留首次 LinkageError。
- `inspect_rule` 改为逐元素提取后再套 `##正则##`；正文 `replaceRegex` 作用在 formatKeepImg + 逐行 trim 的纯文本上。`--` 通道无下一章上下文、最多 10 页，空 `nextContentUrl` 跳过。
- 阅读 Web 旧版探测先打哨兵 `/getBookSource`，失败再读 `/getBookSources` 有界前缀；截断 JSON 若 `data` 数组在前仍识别为阅读，不拉全库、不扫端口。

## [1.3.3] - 本地迭代（未单独 GitHub 发布）

- 修正 MCP 初始化与 `get_app_info` 的书源工作流提示：阅读端调试与网页实际结果优先，沙箱测试不再被要求为前置步骤，阶段到达不冒充验证通过。
- 固定书源校验器的 Release 类名并纳入混淆映射检查；运行组件加载异常改为可辨认的工具错误，操作日志记录异常类型，便于定位同类故障。
- 修复逐次抓包将公网 IPv6 地址误识别为私网的情况，同时保留回环与私网地址阻断。

## [1.3.2] - 本地迭代（未单独 GitHub 发布）

- 书源制作改为阅读端分阶段调试主流程；沙箱仅辅助分析，不再作为保存草稿或进入阅读调试的条件。清理技能与参考文档中把沙箱能力限制当作阅读规则限制的说明。
- 阅读执行证据关联书源内容与连接配置，区分历史记录、当前结果和未确认内容；后续失败或不完整尝试不再沿用旧成功。
- 调试完整性判断统一检查结束信号与连接结束情况，导出区分部分覆盖、未完成和身份不匹配，允许草稿导出但不冒充已验证。
- 未识别到已知接口时继续提供阅读功能的实际检测入口；连接提示不以单一阅读版本判定身份，保留用户设置的实际端口。
- 沙箱不再额外注入 `url` / `isFromBookInfo`；静态变量提示使用语法树检查作用域，覆盖模板表达式，并避免把合法声明与属性名误判成未定义变量。
- 默认探测词使用两字及以上的 `checkKeyWord`，否则使用「我的」；实际验证推荐站内真实书名。搜索关键词静态提示不阻止动态规则保存。

## [1.3.1] - 2026-10-26（本地迭代，未单独 GitHub 发布）

- 修复 Release 混淆导致公开 JSON 字段改名的问题，保持阶段证据的 `category`、`reason`、`facts` 等字段稳定；发布检查覆盖字段映射，不只检查类名。
- 阅读工具最终回包的 `nextAction` 统一为工具名或 `null`，自然语言指引分离为 `nextActionHint`，调用参数放在 `nextActionArguments`。同步已自动完成四阶段调试且无失败信号时不再要求重复调试。
- 同步的完整阅读端执行记录保存在任务上下文，通过 `readerExecutionResultId`、`readerExecutionContextId` 与 `read_result` 分页读取；内联日志裁剪不再要求重新请求站点。
- 正文整段为明确转码/解码失败提示或纯问号、替代字符占位时标为 `content_unavailable`，不再仅因非空而判成功；该结果不直接判定规则错误，正常短正文和包含相关词语的叙事不受影响。
- 明确 `already_present` 的幂等语义：内容已确认但本次未写入，不重复保存和回读；仍自动调试阅读中现有的书源。
- 更新内置技能和公开说明，区分内容确认、执行日志与可用性证据；失败信号不再一律引导修改规则，沙箱限流不能代替阅读端限流证据。

## [1.3.0] - 2026-10-26（本地迭代，未单独 GitHub 发布）

**沙箱与阅读端分离**
- `check_source` 区分验收环境限制与书源真错误：外部 jsLib 跳过、正文过大截断、二进制响应跳过、翻页上限、阅读端存在但沙箱缺少的 Java/扩展接口、签名不支持等归为沙箱分歧，不再判为规则失败；翻页上限不再判正文未验证。
- 未定义类报错只在名称属于阅读端已有接口时归为沙箱分歧，其余仍按规则错误处理。
- 自动探测词搜索无结果单独标为 `probe_keyword_empty`，提示换站内真实书名重测，不代表规则错误。
- 搜索/发现列表为 0 且页面是站点限流提示（如搜索间隔、HTTP 429）时标为 `rate_limited`，`failureKind=site`，`sandboxDivergence` 附 `site_rate_limited`，不再误报为规则错误。
- 失败全部来自沙箱分歧、探测词或站点限流时结论为 `inconclusive`。
- `auto=true` 搜索失败中止时，提示可传 `detailUrl` / `tocUrl` / `contentUrl` 单独验证后续阶段。
- 相关提示文案统一为「保留原写法，属验收环境限制」，不再引导为通过沙箱而改写规则。

**回包字段**
- `check_source` 新增 `sandboxDivergence[]`（`stage`、`reason`、`readerBehavior`）与 `ruleEditPolicy`（`doNotEditFor`、`reason`）；书源未声明 User-Agent 时附 `default_user_agent` 提示。
- `save_source` 新增 `injectedFields`、`normalizedFields`、`changedFields`、`editWithoutReaderEvidence` 等字段，标出保存时被补全或改动的字段；最近一次沙箱验收失败后、阅读端尚未执行就修改规则时给出提示（`editHint` 仅在 `editWithoutReaderEvidence=true` 时出现）。
- `reader_web_sync_source` 回读确认后的 `nextAction` 改为 `reader_web_debug`，旧版阅读也能直接走阅读端调试；结构化检验接口可用时再选 `reader_web_verify`。
- 阅读端调试 `structuredResult` 新增 `failureCategories`。

**技能文档**
- 书源技能补充硬红线：沙箱分歧不作为修改规则的依据，交付以阅读端执行结果为准；新增「沙箱报错但阅读能跑」排障章节。

**REA 逆向参考更新至 v49（上游 b8ce3c7）**
- 同步 reverse-engineer-anything 技能与参考文档，新增运行环境参考；补充手机端工具映射与不可用清单。

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
