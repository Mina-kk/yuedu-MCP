# 阅读书源 MCP

内置 **Legado 运行时 + MCP Server**。App 内不调用任何模型、不保存任何模型 Key。书源的制作、修复和调试全部由外部 MCP 客户端完成。

- 包名：`com.mina.legadostudio`
- 当前版本：`1.2.2`（versionCode 190，以 `app/build.gradle.kts` 为准）
- 许可证：GPL-3.0
- 上游致谢：DandanLLab/legadoSkill、LegadoTeam/legado、morluto/rea
- 下载：[Releases](https://github.com/Mina-kk/yuedu-MCP/releases)（每个版本附带已签名 APK）

## 界面

iOS 简约白风格：液态玻璃顶栏/底栏（高斯模糊）、大圆角卡片、底部悬浮标签栏。底栏为 **MCP / 书源 / 技能 / 验证中心 / 日志**。

验证中心运行时提供圆形 **M** 字悬浮球：可自由拖动到任意位置，靠近屏幕左右边缘松手时自动吸附半隐（点按展开）；人工验证气泡弹出时自动贴边完整显示，不打断当前操作。

## 连接 MCP

MCP 页分三个页签：**连接**（服务开关 / 接入信息 / 健康检查）、**设置**（端口、令牌、阅读 Web 服务、书源类型、外观）、**前置条件**（通知、电池策略、端口占用等）。

1. 底栏 **MCP** →「连接」页签开启服务（默认端口 **58823**）
2. 「接入信息」卡按你的客户端界面形态给出对应填法，每一项都可一键复制：
   - **服务器链接（所有客户端）**：`http://127.0.0.1:58823/mcp`；客户端不在同一台设备时切「局域网」，使用 `http://<局域网IP>:58823/mcp`
   - 客户端有 **Token / Bearer Token 输入框** → 只粘贴令牌本身，不要加 `Bearer` 前缀
   - 客户端只有 **自定义请求头（名称 + 值）两个框** → 名称填 `Authorization`，值填 `Bearer <令牌>`（名称填成 `Bearer` 是常见错误，会 401）
   - 客户端没有任何鉴权输入 → 建议换用支持鉴权的客户端；不要为局域网或远程接入关闭令牌校验
   - **「配置文件客户端（JSON）」折叠卡**：标准 `mcpServers` JSON（Claude Code / Cline / Cherry Studio 等）整段复制，跟随连接模式
3. 鉴权统一使用 MCP 规范的标准头：**`Authorization: Bearer <令牌>`**
4. 调用 `get_app_info` 可查看版本、运行能力及构建标识 `buildRevision`（源码提交标识）与 `buildType`（`debug`/`release`）。反馈问题时请提供版本和构建标识，不要提交访问令牌或未脱敏日志。

#### 连接模式：标准 / 精简（省 token）

「接入信息」卡顶部有 **连接模式** 选择：**标准**（默认）直接复制完整描述的服务器链接；**精简** 生成的链接带 `?compact=1`（如 `http://127.0.0.1:58823/mcp?compact=1`），本机/局域网、复制服务器链接、「配置文件客户端（JSON）」整段配置与「仅复制 URL」都统一跟随所选模式。切换只改这里生成的地址：在客户端换成新地址重新连接才生效，已连接会话不自动切换，必要时刷新客户端的工具目录。

精简会话的 `tools/list` 只回每个工具的首句描述、入参 `description` 同样截断（单句超过 100 字符硬截并加 `…`）；工具数量、名称顺序、`inputSchema` 的 `type`/`required`/`enum`/数值约束逐字不变，73 个工具与抓取能力与标准模式完全相同（非基础/进阶权限）。被截掉的完整定义随时经只读资源模板 `studio://tools/{name}` 按名读回（返回 `{"name","description","inputSchema","annotations"}`）。不带该参数的默认行为逐字不变。

`fetch_page` 首次自动预览的默认长度：普通连接 `responseMode=auto` 为 6000 字符；`preview` 模式无论连接类型缺省 6000；同一 compact 会话的 `auto` 未传 `maxChars` 时降为 2000。显式 `maxChars`（0..12000）始终优先；`reference` 模式与 auto 命中缓存本就不回正文（仅返回 `pageId` 引用），不涉及预览长度。正文仍完整保存在 `pageId`，可照常经 `read_page` 或 `inspect_rule`/`analyze_html`/`eval_js` 引用恢复。

### 书源类型开关

MCP 页可选择目标书源类型：**自动 / 文本 / 音频 / 图片 / 文件 / 视频**（对应 `bookSourceType` -1、0–4）。`save_source` 保存时自动写入所选类型（「自动」保留 JSON 原样）；`fetch_page` 按类型过滤二进制内容：文本类型跳过图片/音视频等二进制响应，非文本类型保留对应媒体内容，「自动」不干预抓取，适合图文混合等类型不确定的站点。

## MCP 工具一览

共 73 个工具。

| 分组 | 工具 |
|---|---|
| 语料 / 知识 / 技能 | `match_sources` `get_corpus_source` `get_corpus_shard` `search_knowledge` `read_knowledge` `list_skills` `get_skill` `get_skill_reference` `save_skill` `delete_skill` |
| 任务上下文 | `create_context` `get_context` `list_contexts` `update_context` `clear_context` `read_page` `read_result` |
| 抓取与解析 | `fetch_page` `analyze_html` `inspect_rule` `eval_js` `webview_render` `webview_eval` |
| 字体反爬 | `fetch_font` `get_font_map` |
| 书源 | `validate_source` `list_sources` `get_source` `save_source` `export_source` `debug_source` `check_source` `analyze_sources` `export_checked_sources` `archive_source_versions` `import_reader` `list_projects` `get_project` `save_project` `delete_projects` |
| 验证与 Cookie | `browser_verify` `get_verification_status` `get_domain_modes` `set_domain_mode` `get_cookies` `set_cookie` `clear_cookies` `apply_webview_cookies` |
| 阅读 Web 服务 | `reader_web_status` `reader_web_page` `reader_web_sync_source` `reader_web_verify` `reader_web_debug` `reader_web_search` |
| 抓包 | `capture_once` `webview_capture` `list_captures` `get_capture` `poll_capture` `get_capture_resource` |
| 状态与日志 | `get_app_info` `app_status` `set_http_log_recording` `get_http_log_recording` `get_http_logs` `get_http_log` `get_logs` `get_log` `get_crash_logs` `get_crash_log` `export_diagnostic` `get_diagnostic_snapshots` `get_diagnostic_snapshot` |

- `save_skill` / `delete_skill`：管理自定义 Skill，内置 Skill 不可覆盖或删除。
- `fetch_font` / `get_font_map`：抓取 woff/woff2/ttf/otf 字体并分页读取 cmap（码点→glyphId），用于字体加密站点；glyphId 不等于汉字，还原需字形比对。
- `apply_webview_cookies`：人工过完 CF/JS 挑战后，把 WebView 采到的 Cookie（及可选 User-Agent）按名合并进目标书源 header 并保存。
- `export_diagnostic`：创建诊断快照；`get_http_log_recording`：只读查询 HTTP 事务记录开关。
- `reader_web_status`：只读探测已保存的阅读 Web 服务：网页入口是否可打开（`webPageReachable`）、官方接口画像（`apiProfile`，只是可选增强）、鉴权状态与 `configRevision`。不写书源、不启动检验（见下节「阅读 Web 服务」）。
- `reader_web_page`：在 App 内**持续的 WebView 会话**里操作用户保存的阅读网页——`open`/`snapshot`/`click`/`fill`/`scroll`/`back`/`close`，每次操作后回同源快照与可交互元素列表。没有 URL、host、请求头或 JS 参数，因此不能把会话引到别的站点。
- `reader_web_debug`：把**阅读中已保存**的书源交给阅读 App 自己的调试通道 `/bookSourceDebug`（真实运行，不是本机沙箱）。不需要新版 REST；日志恒为 `unverifiable`。
- `reader_web_search`：在阅读当前搜索范围内**真正执行一次全局搜索**（固定 `/searchBook`），只收录阅读返回的结构化 JSON 数组帧。它是全局搜索，**不能**当作单源验证。
- `reader_web_sync_source`：把本 App 已保存项目的成品书源写入阅读，写后回读核对；写入或确认已存在后，服务端**强制**在阅读端跑一次 `/bookSourceDebug`（`keyword` 缺省「我的」），回包 `readerExecution` 与 `deliveryStatus`。保存后的默认主路径，但不是网页操作的前提。
- `reader_web_verify`：用阅读 App 自己的 Web 服务检验一个**已在阅读中保存**的书源。**可选快捷**；旧版只出调试日志（见下节「阅读端检验」）。
- `import_reader`：旧式推送兜底——经 `legado://import/bookSource` 唤起本机阅读客户端的**导入勾选页**。回包 `launched=true`/`confirmation=pending_user`/`confirmed=false`（兼容字段 `imported` 已废弃、恒为 false），只表示勾选页已打开，最终是否导入由用户在阅读里确认，**不代表导入成功**，更不能当作阅读端检验通过；确认后仍需 `reader_web_debug`、`reader_web_search`、`reader_web_page` 或 `reader_web_verify` 核实，需要可核对的写入请用 `reader_web_sync_source`。

### 阅读 Web 服务（先看网页，接口只作可选加速）

这六个工具共用 MCP 页「设置」→「阅读 Web 服务」里**用户已保存**的地址与令牌；模型不能指定主机、端口、路径或请求头：HTTP 请求只发往该 origin，两个 WebSocket 工具只连由该 origin **按固定规则派生**的地址（同主机，端口 = 用户保存的端口 + 1，路径固定 `/bookSourceDebug` 或 `/searchBook`，协议随 origin 取 `ws`/`wss`），**不扫描端口、不回落到默认端口、不向任何其它私网主机发请求**。**身份标准是用户填的那个阅读 Web 网页**，不是 Legado 的官方接口：接口匹配与否只决定能不能用结构化快捷方式，不决定「是不是阅读」，也不要求用户升级。

#### 默认闭环：网页优先

```
reader_web_status()                      只读探测：网页入口能不能开、保存的是哪个端口
  → reader_web_page(action=open)          在同一 WebView 会话打开用户保存的 origin
  → snapshot / elements                   拿页面文本 + 可交互元素（selector / 文本 / 链接）
  → click / fill / scroll / back           AI 按固定 DOM 操作在网页里导航（click 须观察到页面变化才算生效）
  → 书源管理 / 调试页面                  用户可见的页面就是工作区
  → reader_web_debug(bookSourceUrl, keyword[, timeoutSec])
                                          在阅读 App 里真实运行已保存书源的调试通道 /bookSourceDebug
  → reader_web_search(keyword[, timeoutSec, maxResults])   （可选）
                                          可选：在阅读当前搜索范围内执行一次全局搜索 /searchBook，代价大
```

- 目标永远是**用户保存的 origin**：`reader_web_page` 没有 URL、host、请求头、JS 参数，多传一个 `url` 会直接报错 `INVALID_ARGUMENT`，不存在「换个地址试试」的路径。两个 WS 工具也只能由这份保存配置派生目标，模型同样不能指定主机或端口。
- 页面是 SPA 也能用：会话在 MCP 调用之间保持，登录态、JS 状态、滚动位置跨调用延续；空闲 120 秒或地址配置变更时销毁，下次 `open` 重开。
- 官方分支、第三方分支、改了默认端口的阅读都用同一条路：只要网页能打开、元素能点，就能干活。
- 对非官方分支同样适用：`apiProfile=mismatch` 只说明没识别到官方接口，**不代表页面不能用**，继续用 `reader_web_page`。

**证据口径**：书源能不能交付，**以阅读端的真实执行结果为准**——在阅读里真正跑了搜索与调试，并有可引用的页面反馈或调试/搜索结果。工坊 `check_source` 的 `full` 只是本机沙箱本次样本四阶段初筛，**只可用于初筛，不能作为最终判断**，也不能声称阅读端通过。已配置阅读 Web 时：

- **执行证据优先**：`reader_web_debug`（阅读 App 真实运行已保存书源的调试通道）给出单源执行结果，回包 `structuredResult.logVerdict` 机读四阶段到达情况（`all_stages_reached_no_failure`/`incomplete`/`failure_signals`）；`reader_web_search` 是可选的全局搜索补充。两者都不依赖新版 REST。
- `reader_web_page` 里页面实际显示的搜索结果、目录、正文，或 `reader_web_verify` 归属本次的结构化结果是另一条路。
- `reader_web_debug` 的日志恒为 `unverifiable`：它证明「阅读端确实执行过并给出这些日志」，但没有通过/失败结论，**不得写成 `PASSED`**。
- `reader_web_search` 是**全局搜索**：`byOrigin` 按完整 `origin` 精确键计数，带 `#probeUA` 的探测源与无后缀的正式源不是同一键，不能按同域/同名归并命中；目标源被禁用、不在搜索范围或本轮没有它时，不能据此判定该源失败。它不是单源验证。
- **网页根路径返回 200、登录页能打开、接口探测有响应，都不等于书源通过**。
- 阅读端未配置/无法访问或未取得上述执行证据时，明确标注“阅读端未覆盖”，不得以沙箱替代。两侧证据互不替代。
- **服务端交付门槛 `deliveryStatus`**：`check_source` 回包恒带 `verdictScope=sandbox_only`、`readerSideExecuted`（阅读端执行过）与 `readerSidePassed`（四阶段到达且无失败信号）；沙箱失败全部来自环境限制时 `summary=inconclusive`（附 `failureKind`），不等于规则失败；已配置阅读 Web 时附 `requiredNextActions` 与 `forbiddenClaims`；`reader_web_sync_source`/`reader_web_debug` 回包附 `deliveryStatus`（`reader_not_executed`/`reader_failure_signals`/`reader_debug_incomplete`/`reader_debug_ran`；`reader_debug_incomplete` 表示 TIMEOUT、无终止信号或截断，不能假定日志完整；重跑且不得改用沙箱结论）与日志信号 `logSignals`；`export_checked_sources` 附本进程内阅读端执行记录 `readerExecutions` 与 `deliveryStatus`（只标注、不拒绝导出）。阅读端日志出现失败信号时，沙箱 4/4 也不能交付为可用；任何情况下都不得说「通过/一致/验证成功」。

**副作用**：`click` / `fill` 会改变页面状态（登录、翻页、提交筛选、切换分组、提交搜索），不是只读操作，工具标注为可写。`read`/`snapshot`/`scroll` 本身不改页面，但页面可能自行加载或提交。**保存、覆盖、删除书源等写操作必须先征得用户确认**，不要仅凭一次点击就替用户落盘。

`reader_web_status()` 只读、无副作用，返回：

- `status`：`not_configured` / `unreachable` / `auth_required` / `ok` / `web_page_available` / `web_page_unavailable` / `api_only_available` / `web_page_unreachable` / `web_page_probe_incomplete`（见下文）。
- `webPageReachable` / `webPageStatusCode` / `webPageContentKind`：根路径能否作为页面入口打开；HTTP 200 HTML 只证明网页在线。
- `webPageProbeFailureKind`：根路径失败的性质 `none` / `http_status` / `auth_wall` / `body_unreadable` / `network` / `redirect_refused` / `not_probed`——把「有状态码但读不到正文」与「完全没有响应」分开，避免笼统说网页不可用。`webPageProbeErrorCode`（`TIMEOUT`/`UNREACHABLE`/`REDIRECT_BLOCKED`）与它配合看。
- `webPageProbeDiagnostic`：根路径那次请求的脱敏诊断，字段为 `method` / `path` / `stage` / `phase`（`none`/`connect`/`headers`/`body`/`parse`，卡在哪一步）/ `httpStatus` / `exceptionCategory`（`DNS`/`CONNECT`/`RESET`/`EOF`/`PROTOCOL`/`TLS`/`TIMEOUT`/`UNKNOWN`）/ `attempts` / `reusedConnection` / `authRetry`。不含主机、端口、查询串和令牌。`network` 类失败时先看它，而不是只看一句「网络错误」。
- `webUiSessionSupported`：本构建是否装了网页会话工具（`reader_web_page`）的纯功能标志，不含地址、端口或个人信息；为 `false` 时才说明网页通道不可用，与「网页是否在线」无关。
- `apiProfile` / `apiProfileDetail`：官方接口画像 `structured` / `legacy_read` / `mismatch` / `unknown` / `not_probed`，只是可选能力提示。
- `authRequired` / `authReady` / `tokenRequired` / `bearerAuth` / `tokenSaved`：鉴权状态；网页登录由用户在页面里完成，不阻塞页面操作。
- `capabilities`：`webPage` / `read` / `check` / `write` / `legacyDebug`，取值 `supported` / `unsupported` / `unauthorized` / `unknown`。
- `allowedNextActions`：建议下一步，`web_page_available` 时优先 `reader_web_page`。
- `originEcho` / `configRevision`：回环回显实际端口、私网主机掩码；模型据此确认端口与配置版本，不必猜。

各状态的含义与行动：

- `not_configured`：用户还没填地址 → 提示填写（同机可填 `http://127.0.0.1:<阅读端口>`），本段登记未覆盖。
- `unreachable`：根路径与任何接口都没有响应 → 核对端口、回环与 Wi-Fi/VPN；不要换端口重试抓页。
- `auth_required`：地址**有响应**，只是鉴权没过（令牌缺失/无效、401/403、Bearer 挑战）→ 既不是离线也不是「不是阅读」；先补令牌，或直接在网页里登录后用 `reader_web_page`。
- `ok`：网页可开且识别到官方接口 → 网页工具与结构化快捷都可用。
- `web_page_available`：**网页在线，官方接口不匹配**（分支/版本不同）→ 用 `reader_web_page` 继续；不断言不是阅读、不要求升级。**根路径 2xx 拿到 HTML 但没识别到官方接口，正确结果就是这一条**，不是失败、也不是「不是阅读」。
- `web_page_unavailable`：服务有响应（HTTP 状态已到），但根路径不是可用页面入口（404/5xx/被拒的越域跳转）→ 先核对端口是否指向阅读的 Web 服务入口，再判断页面本身。
- `api_only_available`：**官方接口有正面证据，但根路径没有可用页面** → 只说明接口可达：网页通道这一轮没证实，书源是否可用也未证实；不要据此说「不是阅读」，也不要据此说书源通过。可先用结构化快捷，或让用户确认阅读界面显示的 Web 地址。
- `web_page_unreachable`：根路径在网络层就没拿到响应（超时/连不上），但服务在别的路径有响应 → 先分清是网页入口不可达还是地址填错，不要笼统报「网页不可用」。
- `web_page_probe_incomplete`：根路径已回状态码但正文没读完，页面是否可用**尚未确认** → 重试一次或用 `reader_web_page` 实际打开看，不要当成失败结论。
- 状态判定只描述**本轮探测到的事实**：`webPageStatusCode` 与 `webPageProbeFailureKind` 说明根路径发生了什么，不代表页面模板固定、也不代表可以从 HTML 模板反推服务身份。

- `check` 由新版公开接口与检验状态只读路由的响应判定；`read` 由旧版公开读路由的有界前缀判定；`webPage` 由根路径探测判定。
- `write` 与 `legacyDebug` 无法在**不产生副作用**的前提下探测，恒为 `unknown`——未知不等于不支持，对应工具会在真正使用时尝试并如实回报。
- 服务不可达或超时给 `unknown`，不误报 `unsupported`；`401`/`403`（含 Bearer 挑战）说明接口存在、只是令牌缺失或错误。
- `status=auth_required`（`authRequired=true`、`authReady=false`）：`allowedNextActions` 给 `configure_reader_web_token`，网页可开或只是登录墙时同时给 `reader_web_page`；此时**不推荐 `reader_web_sync_source`/`reader_web_verify`**（结构化路由过不了鉴权），请先在「阅读 Web 服务」填写与阅读相同的令牌（改地址会清除旧令牌），或直接在网页里登录后用 `reader_web_page` 继续。
- 能力值 `unauthorized` 表示接口存在但当前凭据过不了鉴权：既不能当 `unsupported`（版本不支持），也不能当可用。
- `authReady=true` 只表示鉴权没挡住或站点不需要鉴权，**不代表令牌一定正确**；读写是否真被接受以实际回包为准（可能返回 `TOKEN_REQUIRED`/`TOKEN_INVALID`）。

### reader_web_page：操作用户保存的阅读网页

```
reader_web_page(action="open")            → { mode: "web_ui", title, path, text, elements[] }
reader_web_page(action="fill", selector="input[name=q]", text="关键词")
reader_web_page(action="click", selector="button[type=submit]")
reader_web_page(action="press_enter", selector="input[name=q]")  → 可编辑输入框回车提交；先 fill
reader_web_page(action="snapshot", textOffset=3000, elementOffset=30, limit=30)
reader_web_page(action="scroll", deltaY=600)
reader_web_page(action="back") / (action="close")
```

- 动作固定八个：`open` / `snapshot` / `click` / `press_enter` / `fill` / `scroll` / `back` / `close`；其它动作名一律拒绝。
- `click`/`press_enter`/`fill` 必须给 `selector`（CSS 选择器，页面内字面量，**不是脚本**），`index` 选第几个匹配；`fill` 必须给 `text`，空串表示清空；`press_enter` 只对可编辑表单控件派发回车。`scroll` 只接受 `deltaY`（±20000）；`open`/`snapshot`/`back`/`close` 不接受 DOM 参数。文件类输入框不支持填充（`UNSUPPORTED_INPUT`）。
- `click`/`press_enter` **只有观察到导航或 DOM 变化才算生效**：派发后若在观察窗口内没看到变化，回 `errorCode=NO_EFFECT`；页面正在导航或用签名无法比较时回 `ACTION_UNVERIFIED`。两者都只是「事件发出去了」，**不得写成点击成功或搜索成功**，应以随后 `snapshot` 的真实页面为准。
- 回包每次都是同 origin 的新快照：`title`、`path`（已剔除敏感查询参数）、正文预览（默认 ≤3000 字符）、`elements`（默认 30 条，≤80）。用 `textOffset`/`nextTextOffset`、`elementOffset`/`nextElementOffset`、`limit` 续读，`hasMoreText`/`hasMoreElements` 标明还有多少。单次序列化硬性 ≤11000 字符，元素多时自动收缩，不会返回半截 JSON。
- 每个元素带 `order`、`index`、`tag`、`text`、`selector`，以及可用的 `type`/`name`/`placeholder`/`value`/`href`/`disabled`；`selector` 可直接回传给 `click`/`fill`。密码框、hidden 输入的值不回显。
- 错误码结构化返回，按 `errorCode` 决策：`NOT_CONFIGURED`（先去设置页填地址）、`NOT_OPENED`/`CLOSED`/`TIMEOUT`/`LOAD_FAILED`/`CONFIG_CHANGED`/`OFF_ORIGIN`（重新 `open`）、`TARGET_NOT_FOUND`/`INVALID_SELECTOR`（重新 `snapshot` 拿新 selector）、`INVALID_ARGUMENT`（参数写错）、`UNSUPPORTED_INPUT`（文件框）。另有两条**不是成功**的码：`NO_EFFECT`（事件已派发，但观察窗口内没看到导航或 DOM 变化）与 `ACTION_UNVERIFIED`（事件已派发，页面变化无法确认）。遇到它们不要声称「已经点开/已经搜到」，重新 `snapshot` 看当前真实页面再决定。
- 页面跨 origin 跳转或重定向会被拦截（`OFF_ORIGIN`），会话随即销毁；子资源允许公网 CDN，但跨 origin 的回环/私网地址被阻断。不写入任何日志或截图，也不注册 JS bridge。
- `close` 不访问网络；空闲 120 秒自动销毁。改保存地址会让下一次调用直接销毁旧会话（`CONFIG_CHANGED`），不会带着旧站点的登录态去新地址。
- `fetch_page` 命中与已保存 origin **完全相同**的 URL 时不抓页，改回 `status=reader_web_origin` / `nextAction=reader_web_page` 的引导（`networkRequest=false`，只带掩码后的 `originEcho`）；其它端口、scheme、主机与公网地址不受影响。该引导回包同时带 `requestIssued=false`、`requestedTool=fetch_page` 与 `requestNote`：这是 MCP 自己生成的提示，**没有向阅读发出任何 HTTP 请求**，不能当作服务端回显或接口返回来推断阅读用了什么方法。

**端口由用户填写、不可猜**：模型只从 `reader_web_status` 的 `originEcho` 读当前真实端口，不要按默认端口或示例值去试。客户端里若把某个别名指向别的服务，本 App 无法控制，也不该据此改地址。

### 阅读端真实执行：reader_web_debug 与 reader_web_search

这两个工具在**阅读 App 里真实发起执行**，不依赖新版 REST 接口；目标是固定规则从用户保存的 origin 派生的 WebSocket——**同主机、端口 = 用户保存的端口 + 1**（`http`→`ws`、`https`→`wss`），路径分别是固定的 `/bookSourceDebug` 与 `/searchBook`，不带查询串、不跟随重定向、不走系统代理；端口已到 65535 时报 `PORT_OVERFLOW`，**不换端口、不扫描、不向其它私网主机发请求**。令牌以 `legado` 子协议发送，只发往这两个派生地址。

`reader_web_debug(bookSourceUrl, keyword, timeoutSec?)`：

1. 固定本次配置快照；先 `GET /getBookSource?url=` **精确确认该书源已保存在阅读**，不存在直接 `SOURCE_NOT_IN_READER`，不运行调试；
2. 在阅读 App 里运行该书源的调试通道 `/bookSourceDebug`，返回 `debugLogs`、`frameCount`、`closeCode`、`closeReason`、`completionSignal` 与 `executed`/`lookupConfirmed`；
3. 回包恒为 `status=legacy_debug_only`、`machineVerdict=unverifiable`。它**证明的是「阅读端确实跑过并给出这些日志」**，日志可用于定位修规则，但**没有通过/失败结论，不得写成 `PASSED`**，也不登记为本实例 `check_source` 的证据；
4. `timeoutSec` 5..120（默认 120）；`executed=unknown` 表示请求已发但无法确认首帧是否送达。运行调试可能改动阅读里该书源的调试状态，属于写操作，工具标注为可写。

`reader_web_search(keyword, timeoutSec?, maxResults?)`：

1. 在阅读**当前搜索范围**内真正执行一次全局搜索（阅读里所有已启用书源），收集固定 `/searchBook` 的结构化文本帧；
2. 只收录**真正的 JSON 数组帧**，每本只取 `name`/`author`/`bookUrl`/`origin`/`originName`/`latestChapterTitle`；遇到非数组、含非对象的帧直接 `INCOMPATIBLE_FRAME` 拒绝，「用日志冒充书籍」不成立；
3. `status`：`completed`（阅读给出 `Search finish` 且至少有结果）/ `no_data`（结束但没收到任何数组帧）/ `empty`（有数组帧但无书）/ `incomplete`（结束原因不是搜索完成）/ `partial`（达到帧数、单帧大小、总量或 `maxResults` 上限被截断）；错误码含 `NOT_CONFIGURED`/`TOKEN_REQUIRED`/`TOKEN_INVALID`/`ENDPOINT_MISSING`/`SERVICE_BUSY`/`HANDSHAKE_TIMEOUT`/`TIMEOUT`/`UNREACHABLE`/`REDIRECT_BLOCKED`/`INCOMPATIBLE_FRAME` 等；
4. **它是全局搜索，不是单源验证**：命中某本书只说明「阅读当前搜索范围里有一个源返回了它」；`origin`/`originName` 相同也不能单独证明就是目标书源。目标源被禁用、不在阅读当前搜索范围内，或阅读这次没有让该源参与搜索时，结果里没有它**不等于该源失败**，应登记为「本次全局搜索未覆盖该源」，并改用 `reader_web_debug`（单源、真实执行）或在阅读网页里直接在该源上搜索；
5. `timeoutSec` 5..300（默认 120），`maxResults` 1..100（默认 30）。搜索会真实访问网络、写入阅读的搜索历史，属于写操作。

这两个工具任何一方在目标版本/分支上不被支持（例如该分支没有 `/searchBook`、没有 `/bookSourceDebug`、只认不同端口）时，会以 `ENDPOINT_MISSING`/`HANDSHAKE_TIMEOUT`/`UNREACHABLE` 等如实回报——**按实际回包汇报不支持，不要伪造结果**，改走 `reader_web_page` 在阅读网页里操作，或在新版接口可用的版本上用 `reader_web_verify`。

### 可选快捷：结构化同步与检验

网页能看到书源管理/调试页面时，AI 可以直接在页面里干活。若该阅读版本**恰好**提供官方结构化接口，可以少点几次、少读几段页面，用两个快捷工具；接口不匹配就继续走网页，不需要升级。

`reader_web_sync_source(projectId, overwrite?)` 把本地**已保存项目**的 `sourceJson` 原文写入阅读，不接受另传书源：

1. 从项目 `sourceJson` 解析 `bookSourceUrl`，固定地址+令牌快照，`getBookSource?url=` 精确预检；
2. 不存在才 `saveBookSource`；已存在且实质内容相同 → `already_present`，不写；内容不同 → 默认 `conflict` 拒写（只回两侧哈希与不同的顶层字段名），`overwrite=true` 才覆盖；
3. 写完必回读核对全部非元数据顶层字段与嵌套规则；仅本地省略而阅读补 `enabledCookieJar=true` 视为阅读默认规范化（回包 `normalizedFields`），显式 `false` 与其它差异仍须拦截；一致才 `written`，否则 `written_unconfirmed` 并列出 `mismatchedFields`。

请求顺序固定为 `GET /getBookSource`（精确预检）→ `POST /saveBookSource`（写入）→ `GET /getBookSource`（回读）；预检无法明确证明不存在时不写，已存在但不同且未明确授权覆盖时也不写，**绝不以 `GET /saveBookSource` 写入**。地址与端口全部取自用户保存的配置快照。GET 遇到连接被重置、提前断流或协议数据不完整时，会在新连接上补发一次（与首发共用同一总超时）；**POST 永不自动重发**。回包增加：

- `writeAttempted`：是否走到了写入这一步。
- `writeOutcome`：`not_attempted`（预检失败、已存在或冲突拒写，没走到写入）/ `not_sent`（连接都没建立，可确认阅读没收到）/ `unknown`（连接建立后断开或超时，POST **可能已到达**；不重发，请先在阅读的书源列表确认是否已写入）/ `rejected`（阅读明确拒绝）/ `accepted`（阅读接受了写入；是否生效仍看回读）。写入被接受但回读失败时报 `READBACK_FAILED`，`writeOutcome=accepted`。
- `requests` / `trace`：本次实际发出的请求列表（方法、固定路径、阶段、状态码或异常类别），成功也列出；`diagnostic`：失败那次请求的脱敏诊断（字段同 `webPageProbeDiagnostic`）。

`already_present`/`written` 才会给出 `nextAction=reader_web_verify`。`presentInReader` 只表示精确预检或回读实际存在，不等于 `confirmed`；存在但 `conflict`/`written_unconfirmed` 时仍自动调试阅读当前版本，回包 `readerDebugDeliveryStatus` 与 `deliveryStatus=reader_fields_unconfirmed`、排查用 `nextAction`，绝不能宣称本地字段已生效。确实未回读到源才报 `not_in_reader`。阅读自己维护的顶层元数据不参与实质比较；原始哈希可能因默认规范化不同而不同，须看 `normalizedFields` 与实质差异。同步结果不是阅读端检验结论。

**保存/覆盖书源必须用户确认**：`overwrite=true` 只在用户明确同意覆盖阅读里的同 URL 书源后才可传；`written_unconfirmed` 不算生效。同理，在 `reader_web_page` 里点「保存」「覆盖」「删除」等按钮等同于改动阅读数据，也要先得到用户确认。

**默认闭环**：`check_source` 本机初筛（**只是初筛**）→ `save_source`/`get_source` →（用户在 MCP 页填好阅读 Web）`reader_web_status` →
- **阅读端真实执行**：`reader_web_debug(bookSourceUrl, keyword=实际关键词)` 单源在阅读里真跑调试（日志 `unverifiable`，证明执行不证明通过）、`reader_web_search(keyword)` 真跑全局搜索（全局范围，不冒充单源）；没有新版 REST 也可以走；
- **页面观察**：`reader_web_page` 打开阅读网页，导航书源管理/调试页看真实反馈；
- **可选快捷**：官方接口匹配时 `reader_web_sync_source(projectId)` → `reader_web_verify(bookSourceUrl, keyword=实际关键词)`。

`check_source` 的沙箱结果不可代替阅读端执行证据。探测为 `auth_required` 时先请用户补令牌或让用户在网页里登录；同步返回 `conflict` 时默认**不写**。阅读 Web 未配置、不可达、鉴权未通过或用户未填地址时，这一步如实标为未覆盖，**不**改用 `import_reader` 的唤起回执冒充导入成功。

### 阅读端检验（reader_web_verify，可选快捷）

若网页里能直接点到书源管理/调试页面，用它就够了；只有该版本提供官方结构化接口时，`reader_web_verify` 才能给出机器可判的结论。它**不是**「是不是阅读」的判据——接口不匹配不代表网页不能用，也不要求用户升级。

MCP 页「设置」→「阅读 Web 服务」填写阅读的 Web 服务地址（只接受 `http(s)://主机:端口`，端口以阅读设置为准，不预填），可选填访问令牌（加密保存，界面遮罩、不回显）。**令牌与地址绑定**：修改地址后必须重新填写该地址上的令牌，旧令牌会在保存新地址时清除，不会跟着发往新地址。随后 `reader_web_verify(bookSourceUrl, keyword?, timeoutSec?)` 会：

地址怎么填：**端口必须照抄阅读 App「Web 服务」界面当前显示的实际端口**，不要套用示例的默认端口；同机只替换主机，不更改端口。以下三种场景分别处理，本 App 不按地址或网络类型拒绝请求：

- **同机**（阅读与本 App 装在同一台设备）：阅读的 Web 服务同时监听回环，用 `http://127.0.0.1:<阅读端口>` 即可，**不要求 Wi-Fi**，只开蜂窝数据也能连。保存时只归一与 `127.0.0.1` 严格等价的写法：`127.0.01`、`127.1`、`127.0.1`、`127.00.0.01`、`localhost` → `127.0.0.1`，端口保持用户输入；IPv6 回环写 `[::1]`。**其它回环主机原样保留**（`127.0.0.2`、`127.0.255.1` 等是不同主机，绝不改写成 `.1`，避免把令牌发错主机）；含义有歧义的缩写（`127.2`、`127.0.0`、`127.0.01.1`）直接拒绝，提示写完整四段。界面上的「改用同机回环」按钮由用户主动点选，不会自动改写已填的地址。
- **异机、同一 Wi-Fi**（或同一个可达 VPN）：用阅读设置里显示的 Wi-Fi 地址，`127.0.0.1` 指向的是客户端自己。
- **蜂窝**：运营商网络大多在 NAT 后，`10.x` 等私网地址通常无法被另一台设备反连；同机回环仍然可用。界面按当前网络类型给出不同提示，但不会据此拒绝或改写地址。

「测试连接」执行与 `reader_web_status()` 相同的**能力探测**（同一套固定路由与只读约束），把结果翻译成一句可行动的话，**不把版本差异压成「不像阅读」**：

1. **网页优先**：先看根路径能否作为页面入口打开。能打开但官方接口不匹配（`status=web_page_available`）时显示「网页在线，使用通用网页工具」——即继续用 `reader_web_page`，不断言不是阅读、不要求升级。
2. **可达性与鉴权**：地址为空时按钮直接提示先填写；未配置、不可达/超时、鉴权未通过（`authRequired && !authReady`）直接显示探测返回的真实 `message`，不猜测能力。探测本身抛错时按 `ReaderWebGuidance` 显示失败阶段与脱敏后的错误文本。
3. **`capabilities.check=supported`**：显示「已连通阅读 Web，结构化检验接口可用；可同步书源后调用 `reader_web_verify`」。
4. **旧版 WebSocket 调试**（`LEGACY_READER`/`CHECK_UNSUPPORTED` 或接口不匹配分支）：显示「已连通阅读 Web，但无结构化检验接口；可同步书源后尝试旧版 WebSocket 调试（仅日志，不等于检验通过）」，或直接用 `reader_web_debug` 对阅读里已保存的书源单独跑调试——网页工具照常可用。
5. **其它/尚未确认**（如 `check=unknown`）：显示「已连通阅读 Web；检验能力尚未确认」，按实际调用 `reader_web_verify` 的结果为准。

底层探测仍按实际响应对应到 `LEGACY_READER`（已连通阅读、只是没有检验接口）、`NOT_READER`（有响应但未识别，不能据此断言网页不是阅读）、`CHECK_UNSUPPORTED`（已连上、接口不支持）等码；`502/503` 只报 `HTTP_ERROR`，不当「版本不支持」。界面上「测试连接」会先保存你刚填的地址/令牌，然后只做只读探测：不写入、不检验任何书源。

受保护的书源/检验路由若返回 `401`/`403`，按是否保存了令牌分别报 `TOKEN_REQUIRED`（未填）或 `TOKEN_INVALID`（已填但不对），不会混进「路由不存在」。

`LEGACY_READER`/`CHECK_UNSUPPORTED` 或 `NOT_READER`（仅表示官方接口画像不匹配）时，`reader_web_verify` 只有在固定公开读路由 `GET /getBookSource?url=` **精确确认目标书源已保存**后才尝试旧版 WebSocket 调试；预检失败、书源不存在都不运行 WS，更不会写入。调试只返回 `legacy_debug_only`/`machineVerdict=unverifiable` 与日志，**不产出 `PASSED`**，也不登记本实例证据；需要可判定结论时继续在阅读 Web 书源管理/调试页取证，或用 `reader_web_debug` 单独跑单源调试（同样只有 `unverifiable` 日志），或在该版本支持结构化检验时用 `reader_web_verify`。旧版 `NanoHTTPD` 可能以 `Content-Type: text/html` 返回真实 ReturnData JSON，客户端以正文判别，不能仅凭头误判接口。未配置、不可达、超时、令牌缺失/错误均不降级，如实报错。令牌只发给受保护路由，公开探测与旧版公开读不带令牌。

连接成功后 `reader_web_verify(bookSourceUrl, keyword?, timeoutSec?)` 会：

1. 读 `getJsSourceApiTokenRequired` 判断令牌保护，再按 URL 取 `getBookSourcesForManagement` 里阅读内的完整 BookSource；这两步固定在同一个地址快照上，中途改配置不会一半打旧主机、一半打新主机。
2. `startBookSourceCheck` 提交该书源（与阅读保存版本不一致时阅读端会拒绝，并返回脱敏后的错误提示）；
3. 轮询 `getBookSourceCheckStates` 到该书源不再是 `NEEDS_CHECK`、会话结束或超时，返回阅读端 `status`（`PASSED`/`FAILED`/`UNCHECKABLE`）、`detail`、`checkedAt` 与 `sessionToken`。只认本次检验写入的新状态（`revision` 已变、`sourceRevision` = 本次提交版本、`checkedAt` 已更新）；读到上一次检验遗留或异版本状态时返回 `stale_state`，不会当作通过；阅读端未返回本次 `sourceRevision` 时直接报 `SOURCE_REVISION_UNKNOWN`。

   状态还必须归属本次会话：快照 `sessionToken` 非空时必须等于本次 `startBookSourceCheck` 返回的令牌，否则报 `session_replaced`（被其它客户端的检验取代）；为 `null`（本次刚结束）时，只有先在本会话下观察到该 `revision` 的 `NEEDS_CHECK` 才认领，否则报 `session_unconfirmed`。两种情况都不会返回 `passed`。

这里的 `PASSED` 只表示**阅读端本次检验通过**，不是本 App `check_source` 的四阶段样本检查结论，也不代表章节全文、解密或目标版本兼容已验证。

边界：一次检验在调用开始时固定地址与令牌快照，检验期间在界面上改配置只影响下一次调用，进行中的请求不会转向别的主机、也不会换用新凭据。只检验已保存书源，**不导入、不覆盖**阅读里的书源（要把本 App 成品放进阅读，优先在 `reader_web_page` 的书源管理页导入/粘贴，或用 `reader_web_sync_source` 先预检并回读核对；阅读自身的检验会更新该源的检验状态、分组、错误备注或响应时间）。结果只属于阅读端，不登记为本实例 `check_source` 的证据，`export_checked_sources` 不会把它当作四阶段验证；阅读服务未开启、令牌缺失/错误、超时会明确报错；接口不匹配时不会自动降级为「不是阅读」，而是让你继续用网页工具；版本缺少检验接口时按上节降级到旧版调试通道并标注 `unverifiable`。请求只发往用户保存的 origin 与上面列出的固定接口：不跟随重定向，使用独立的无 Cookie、无 HTTP 日志客户端，**不走系统代理**，错误文本不回显令牌或地址，也不会扫描任何端口或未列出的路径。

## 制作流程

```
match_sources 语料命中（同域/同模板族现成书源）
  → create_context 建任务，全程显式传 contextId
  → fetch_page 抓页存快照；read_page / inspect_rule / analyze_html / eval_js 在快照上作业
  → 编写 BookSource JSON → debug_source 逐阶段调试（正文→目录→详情→搜索→发现）
  → check_source(auto=true, refresh=true, searchKey="实际关键词") 按搜索结果贯通详情/目录/正文取证
  → save_source 保存，get_source 按保存结果的项目 ID 回读确认
  → reader_web_status 探测（网页能否打开、真实端口）
  → reader_web_debug(bookSourceUrl, keyword) 在阅读里真实运行单源调试（日志 unverifiable）
  → （可选）reader_web_search(keyword) 全局搜索补充（不是单源验证）
  → reader_web_page(action=open) 打开阅读网页，在书源管理/调试页核对实际反馈
  → 接口匹配时可选 sync_source → verify
```

阅读端这一段是默认闭环：只要用户在 MCP 页「设置」→「阅读 Web 服务」填了地址，AI 保存书源后就应在**阅读端真实执行**——用 `reader_web_debug` 跑该书源的真实调试（看 `structuredResult`），并在 `reader_web_page` 里核对书源管理/调试页面的实际反馈，而不是只交本机 `check_source` 沙箱报告。沙箱只做初筛，阅读端执行与页面证据才是交付依据。两个 WS 工具都不依赖新版 REST；官方接口匹配时可再加 `reader_web_sync_source`/`reader_web_verify` 抄近路。阅读 Web 未配置/不可达时如实标注该段未覆盖，**不**拿 `import_reader` 的唤起回执冒充导入成功。

**客户端下载新 APK 后必须重新连接 MCP**：工具目录在会话建立时读取，旧会话里不会有 `reader_web_debug` / `reader_web_search`。请在客户端断开重连（或重启客户端），让 `tools/list` 刷新到 73 个工具；精简连接里描述被截断时，按名读 `studio://tools/reader_web_page`（或 `studio://tools/reader_web_debug`、`studio://tools/reader_web_search`）取完整定义。如果客户端里的某个别名指向的是别的服务，本 App 无法控制，也不该据此改阅读 Web 地址。

`save_source` 仅在同一完整 `bookSourceUrl` 且 JSON 内容相同时幂等更新；同 URL 规则不同会新增版本并报告冲突，**不再按同域或去掉 URL 尾部 `/` 自动合并、删除**。回包只给 `projectId`、`sha256`、`sourceChars`、`versionCount`、`conflict` 等摘要，不回传整份书源，全文用 `get_source` 读回；返回的 `VALIDATED` 只表示静态结构校验通过，不表示联网可用。技能包 `legado-book-source` 内置完整工作流与参考文档：`get_skill` 读主文件，`get_skill_reference` 分页读参考（语料 / 验证 / 基础 / 排障 / JS API 等），`search_knowledge` → `read_knowledge` 查验证码、编码、Web JS 逆向等知识库；另附《书源 JS 入门教程》知识包。

### eval_js 与书源 JS 环境

`eval_js` 与书源 `<js>` / `{{}}` 段使用项目内置的 Rhino 与 analyzeRule 引擎。`java` 由工坊的 `StudioJsApi` 桥接，并注入 `cookie`、`cache`、`source` 等同名对象（`debug_source` 时与书源变量互通）。同名不代表与各版本原版阅读的全部签名、状态和平台能力一致；具体支持范围见内置 Skill 的 `js-api.md`「Studio 沙箱可用 API 清单」。导出后仍需在目标阅读版本验证。

对称加解密支持原始字节，以及明确编码的输入和输出：`decryptStr(String)` 只解码标准 Base64 密文，Hex 密文应先用 `java.hexDecodeToByteArray` 转成字节；`encryptBase64` / `encryptHex` 支持 UTF-8 明文字符串或原始字节。搜索请求的普通模板会对 `{{key}}` 作表单编码（包括请求选项的 body 内）；`@js:` 与 `java.post` 的 body 按脚本构造值发送，需要编码时应显式处理，避免重复编码。

CSS/XPath 子规则在列表元素自身上求值，裸 `@attr`（如 `chapterUrl: "@href"`）取当前节点属性，多段落 `@text` 合并全部段落。`set_cookie` 默认 `merge=true` 按 Cookie 名合并（不会冲掉该域其他 Cookie，如登录态），`merge=false` 为整串替换。

### 校验闭环

`validate_source` 只检查静态结构：`searchUrl` 必须配 `ruleSearch.bookList`、`exploreUrl` 必须配 `ruleExplore.bookList`。`check_source(auto=true, refresh=true, searchKey="…")` 使用搜索结果继续检测目录与正文，返回逐阶段 `evidence` / `verdict`；`auto` 默认关闭，`refresh=false` 可能复用快照；未传 `searchKey` 而源有 `searchUrl` 时自动用「我」探测并在 `warnings` 标注。

正文报告的 `data.contentDiagnostics` 记录逐页可观测状态、执行路径及分页停止原因，不含 URL、请求头或正文。`httpStatus` 只记录可确认的原始 HTTP 状态；WebView 或缓存回调路径无法确认时保持未知。缓存回调的 `reportedStatus` 只表示其返回状态，不能等同于实时 HTTP 观测；是否真正命中快照应查看 MCP 顶层 `cache` 统计。已知非成功状态、分页循环／达到页数上限，以及明确的加载／失败占位输出不能仅因非空而判为正文通过；兜底、整页 HTML 和分页不完整等有限模式标记为 `content_unverified`。正常短正文不受最小字数门槛限制。`full` 只表示本次静态校验及四阶段样本检查通过，不证明章节全文完整、解密语义正确、目标阅读版本兼容或站点长期可用。诊断细节见内置 Skill 的 `troubleshoot.md`。

`analyze_sources` 只读分析本地书源（可传 `ids`）或外部 JSON 数组 `sources`：同 URL 但规则不同是冲突；`list_sources` 逐条列出各版本，同 URL 多版本用项目 ID `get_source` 取回。`export_checked_sources(ids=[…])` 仅导出显式选中的本地项目，排除开关 `exclude.excludeLogin`、`excludeCaptcha`、`excludeFailed`、`excludeUnverified` 默认全部关闭；`collapseIdentical=true` 只折叠 JSON 完全一致且无冲突的副本。过滤只采信本 App `check_source` 最近 30 分钟的证据；MCP 单次结果超过 11,500 字符会报错，请缩小 `ids` 或分批导出。书源页「分析/过滤」提供同样的静态预览、逐条检测和不受该回包上限约束的本地导出/分享/导入；外部数组静态分析**不会执行陌生书源的 JS 或批量探测网络**。

### 语料命中（省 token 第一步）

内置 **17743 个唯一身份书源、1544 个规则特征族**：由 29908 条候选（旧底库 26861 条 + YCK 快照 3047 条）按身份键全库去重与版本仲裁后重建，其中旧底库入选 15898 条、YCK 新入选 1845 条；1443 个待复核键与 2842 个隔离键不进入主库。重建后全局序号 `i` 与族编号 `f` 整体重新编号，与旧版本编号不对应。

特征族按类型、请求方式和规则风格等特征分组，仅作结构参考，不保证同一 CMS。同一身份键在主库只保留一个版本：规则实质相同的副本合并为一条，规则不同且可信时间可比较时保留较新者，实质冲突且无法以可信时间区分时整键转待复核，不猜「最新」。入选只表示静态质量筛选通过，没有逐条联网验证；2474 条入选记录缺少可信的规则时间，均不声称最新，也不能把总数当成独立站点数或在线可用数。

- `match_sources(域名或站名)`：返回 `i`（序号）、`d`（域名）、`f`（族 ID）、`t`（类型）、`g`（特征位掩码：CookieJar / 登录 / 验证码 / Cloudflare / 禁用等）；
- 命中同域 → `get_corpus_source(i)` 取完整书源做底本最小修改；
- 未命中同域 → `get_corpus_shard(f)` 读同族 ≤6 个最完整代表样例，参考结构改写；
- 都未命中才走 `fetch_page` 探索。语料样例改写通常比抓页探索省一个数量级调用。

### 验证中心（验证码 / CF / WAF）

遇到站点验证时，MCP 工具返回结构化 JSON 而不是裸错误，按 `status` 行动：

| status | 含义与行动 |
|---|---|
| `verification_required` | 需要人工验证：`browser_verify(url, waitSec=90)` 阻塞等待用户在验证中心完成，随后重试原工具 |
| `webview_mode_enabled` | 该域已自动切换 WebView 抓取（`autoRetried=true` 时本次即为成功结果） |

- 每域验证模式：`get_domain_modes` / `set_domain_mode(domain, auto|always|webview)`。**`always` 适合「一搜一验」的站点**——缓存 Cookie 无效，每次都走人工验证。同一域等待中的验证自动复用，不重复创建。
- 被 JS 盾拦截的站点自动改用 WebView 通道抓取；成品源在官方阅读 App 内过盾靠书源 `loginCheckJs` + `java.startBrowserAwait`。

### 并行多书源

- 每个任务各自 `create_context` 并全程显式传 `contextId`，任务间缓存、引用、笔记完全隔离；未传 `contextId` 时优先复用本连接最近一次 `create_context` 创建且仍存活的任务，否则使用本连接的默认任务（回包 `contextIdSource` 标明 `explicit`/`last_created`/`auto-created`），多客户端同时连接不串数据；
- 多个书源可同时 `debug_source` 而不互相等待；
- 书源页「导入至阅读」支持最多 **4 个书源同时排队**，新导入不会顶掉尚未被拉取的端点。

## 书源与技能

书源页按站点域名分组、组内按保存时间倒序；「分析/过滤」展示重复候选与冲突，导出只影响本次预览，不删除项目也不更改阅读客户端已有源；逐条「检测」会联网运行该源的规则/JS，需用户主动确认。每个书源版本可查看 JSON 详情、复制、分享与导入至阅读。技能页可查看内置 Skill 并控制启用/停用；自定义 Skill 支持新增、导入、导出和删除，`save_skill` / `delete_skill` 不能改写内置 Skill。

## 日志与诊断

底栏 **日志** 分五段：操作日志 / HTTP / 抓包 / 崩溃 / 诊断快照，可多选删除。

- 操作日志与 HTTP 日志按天翻看、支持**按天导出为文本**（可发给电脑/AI 排查，含请求/响应头与正文）；HTTP 详情页含请求/响应头、正文与重定向链；
- 「抓包」页签是三个入口：逐次抓包（发一次请求看逐跳结果）、浏览器抓包（可见可交互的 WebView）、抓包历史（按 `cap:contextId` 分组持久化，重启可回看）；
- 诊断快照只含版本、MCP 状态和前置条件，不含 HTTP 或崩溃正文。

排查请用 MCP：`get_logs` / `get_log` / `get_http_logs` / `get_http_log` / `get_crash_logs` / `get_crash_log` / `get_diagnostic_snapshots` / `get_diagnostic_snapshot`。

抓包取证：`capture_once` 发单次请求并逐跳记录重定向；`webview_capture` 做一次性无头 WebView 抓包（跑完即销毁）；「浏览器抓包」页的交互会话同样以 `cap:contextId` 入库。AI 侧读取：`list_captures` 找会话 → `get_capture(contextId)` 分页回看（活动会话用 `poll_capture(contextId, afterLogId)` 增量拉新）→ `get_http_log(id)` 看单条详情 → 字体/图片等二进制资源用 `get_capture_resource(logId)` 读取。日志与摘要含完整 URL/query，可能带令牌等敏感参数，分享前请自行检查。

构建说明见 `BUILDING.md`。

## 任务上下文

`create_context` 创建任务后全程传 `contextId`：`fetch_page` 保存网页返回 `pageId`（相同请求 5 分钟内复用快照），`read_page` 分段读取，或把 `pageId` 直接交给 `inspect_rule` / `analyze_html` / `eval_js`；工具结果超长时自动转 `resultId` + 预览，用 `read_result` 分段取回。`update_context` 保存阶段笔记，`get_context` 恢复进度，`clear_context` 收尾。

上限 **16 个任务**、每任务 32 项 / 200 万字符；快照 5 分钟内复用、任务闲置 30 分钟过期。应用重启后任务自动恢复，但恢复的条目标记 `stale`，先 `refresh=true` 刷新再依赖其内容。`fetch_page` 支持 `responseMode=reference`（只存快照不回传正文）与 `maxChars` 省流量；最终确认传 `refresh=true` 全程实时。调试输出有意截断（正文 4000 字符、目录 20 章、搜索 10 条等），均带全量计数。`app_status` 可查看活跃连接与任务占用状态。

`app_status` 返回的 `toolUsage` 是当前服务运行期间的工具调用概览：全汇总（calls/errors/cancelled/平均与最大耗时/文本量）与按累计文本字节排序的前 10 个工具（`totalTools`/`listed`/`truncated` 标明是否截断）。文本量按 `TextContent` 的 UTF-16 字符数与 UTF-8 字节数计，**不是 token 数、也不是完整 HTTP 回包大小**；失败按 `isError` 或未捕获异常计，取消单列不计失败。统计只含工具名与数值，不含参数、正文、URL、header、会话 ID 或错误消息；`app_status` 自身不计入；服务重启后清零重新统计。
