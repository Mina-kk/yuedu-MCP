# 验证中心 playbook（验证码 / CF / WAF）

`fetch_page`、`inspect_rule`、`debug_source`、`check_source`、`eval_js` 遇到站点验证（图形验证码、Cloudflare、WAF 盾）时不报裸错误，返回结构化 JSON。按 `status` 字段行动，**不要把验证响应当成规则故障去改规则**。

## 统一响应结构

```json
{
  "status": "verification_required | webview_mode_enabled",
  "sessionId": "（仅 verification_required）验证会话 ID",
  "url": "需要验证的 URL",
  "domain": "站点域",
  "mode": "AUTO | ALWAYS | WEBVIEW",
  "autoRetried": false,
  "evidence": { "code": 403, "finalUrl": "...", "marker": "cloudflare", "viaWebView": false },
  "message": "下一步提示"
}
```

- `evidence.marker`：命中的验证特征名（如 cloudflare、captcha）；`viaWebView`：本次抓取是否已走 WebView 通道。
- `fetch_page` 在系统自动切 WebView 模式时会**原地重试一次**：若重试成功，你直接拿到正常抓取结果（带 `autoRetried=true`），无需任何动作。

## 三种每域验证模式

| 模式 | 语义 | 适用 |
|---|---|---|
| `auto`（默认） | 先普通抓取；被拦且 30 分钟内验证过 → 自动切 WebView；否则发起人工验证 | 绝大多数站点 |
| `webview` | 该域所有抓取强制走应用内 WebView（慢但抗 JS 盾） | JS 动态渲染 / CF 持续拦截的站点 |
| `always` | 不依赖缓存 Cookie，每次访问都要求人工验证 | **一搜一验、URL 次次不同**的站点 |

读写模式：`get_domain_modes` / `set_domain_mode(domain, "auto|always|webview")`。

## 标准处理流程

1. 收到 `verification_required` → 调用 `browser_verify(url, waitSec=90)` 阻塞等待用户在手机验证中心完成验证。
   - `waitSec=0` 立即返回（会话状态 `WAITING`），适合先把任务挂上继续干别的；之后用 `get_verification_status(sessionId)` 轮询。
   - `waitSec=1..120` 阻塞等待完成；完成后自动用 WebView 通道取证一次，返回 `evidence`（code / finalUrl / marker）。
2. 验证完成 → **重试原工具**（原参数原样再调一次）。快照缓存对验证后的首次重试不碍事。
3. `evidence.marker` 仍非空，或同一站每个请求都触发验证 → `set_domain_mode(domain, "always")`。
4. 普通抓取反复 403 但验证已完成 → 系统会自动把该域切到 `webview` 模式（你会先看到 `webview_mode_enabled`），之后抓取走 WebView，无需手动改书源 URL。需要恢复普通路径时调用 `set_domain_mode(domain, "auto")`。

## 图片验证码（手机验证中心）

`browser_verify(url, imageUrl=图片地址, waitSec=0..120)` 会先**单次**通过受限 HTTP 通道拉取公网图片，再建立 `image_code` 会话；用户可在本手机验证中心看图、输入验证码，完成后拿到 `answer`，也可用 `get_verification_status(sessionId)` 轮询。`java.getVerificationCode(imageUrl)` 同样拉图并等待用户输入最多 120 秒，用户取消/超时返回空串；**图片抓取失败会报错，不会展示空图或冒充取消**。图片 URL 不可指向私网，重定向和 DNS 解析到私网也会拒绝；仅放行不超过 2MB、真实格式为 PNG/JPEG/GIF/WebP 的图片。图片字节不会记入 HTTP 日志。

图拉取不走 WebView；站点返回 HTTP 403、CF/HTML 挑战或其它非图片响应时，先在本手机验证中心打开**原站页面**人工过盾，再重试原图片请求。WebView Cookie/通行态不保证能被 HTTP 图请求复用，若仍失败应报告该限制；不要反复请求一次性验证码 URL，也不要声称已支持 WebView 拉图。

`java.startBrowserAwait(url, title?, refetch?, html?)` 在沙箱已接通：调用时创建 webview 验证会话，用户在手机验证中心 WebView 里过盾后，沙箱用 WebView 通道抓取该 URL（`html` 非空时作 `loadDataWithBaseURL` 交接文档）返回整页源码——与官方「弹浏览器过盾后返回 StrResponse」语义一致，用户取消/超时返回空 body。这条链路让「CF/登录盾 → startBrowserAwait 过盾 → 继续抓」的成品源逻辑在沙箱也能端到端验收。官方阅读 App 的具体弹窗交互仍需在真机上对照验收。

## 不要做

- 不要循环调用 `browser_verify` 创建一堆会话：同一域的等待中会话会自动复用，重复创建只会打扰用户。
- 不要在验证完成前重试原工具：结果一样被拦，浪费调用。
- 不要试图用 `set_cookie` 手工拼 CF/验证码 Cookie：cf_clearance 与 TLS 指纹绑定，OkHttp 通道复用 WebView 拿到的 Cookie 通常无效——这正是系统自动切 WebView 抓取的原因。
- 「一搜一验」站点不要指望缓存 Cookie，直接 `always`。
- 过完挑战后 cookie 不会自动进书源：用 `apply_webview_cookies(url, projectId 或 bookSourceUrl)` 把 WebView 采到的 Cookie 与 UA 一键合并进书源 `header`（同名覆盖、未点名保留；返回值不含 cookie 明文）。浏览器抓包页手动过挑战的页面在「结束并保存」时会同步采 cookie，之后同样可调用本工具回填。

## 调试期验证 vs 成品源过盾

| 场景 | 机制 |
|---|---|
| MCP 调试期（本 playbook） | 验证中心 + 每域模式 + WebView 抓取通道 |
| 成品源在官方阅读 App 内运行 | 书源内 `loginCheckJs` + `java.startBrowserAwait`（见 [`troubleshoot.md`](troubleshoot.md) 第 4 节） |

**覆盖范围必须逐链路分清**：工坊 `debug_source`／`check_source` 的阶段抓取会在取得响应后、`bodyJs` 之前执行书源 `loginCheckJs`；独立 `eval_js` 不自动执行该钩子，JS 内部二次请求也不应假定会再次触发它。验证中心和每域模式是工坊自己的交互机制，不能据此推出成品在目标阅读版本中也能过盾。两边的响应桥接、Cookie 与 WebView 通道可能不同，应分别验证，不能互相代替。

调试通过验证中心过完盾、规则写好后，如果目标站在真实使用中也会频繁出盾，应把 `loginCheckJs` 过盾逻辑写进书源，保证导入阅读 App 后仍能工作。验证码类站点的识别特征与处理见知识库：`search_knowledge("验证码")` → `read_knowledge("knowledge/图文验证码.md")`。

## 阅读端检验口径（reader_web_status / reader_web_debug / reader_web_search / reader_web_page / 可选 sync + verify）

本 playbook 解决本 App 调试期的过盾；**阅读端闭环**是另一条通道，操作用户在 MCP 页填写的那个阅读 Web 网页。`check_source(refresh=true)` 即使返回 `full`，也只是工坊沙箱本次样本初筛（**只可初筛**），不是阅读端验证通过，更不能当最终判断。书源交付必须以阅读端真实执行与页面证据为依据：`reader_web_debug`（阅读 App 真实运行已保存书源的调试通道）、`reader_web_search`（阅读当前搜索范围内的真实全局搜索）、`reader_web_page` 里实际显示的书源管理/调试反馈，或 `reader_web_verify` 归属本次的结构化结果。未配置或无法访问时明确写“阅读端未覆盖”。两侧证据互不替代，按 SKILL.md「阅读端闭环」的顺序执行。

**默认顺序**：`reader_web_status()`（探测网页入口与真实端口）→ `reader_web_sync_source(projectId, keyword=站内真实书名)`（写入或确认已存在后，服务端**强制**在阅读端跑 `/bookSourceDebug`，回包 `readerExecution` + `deliveryStatus`）→ `reader_web_debug(bookSourceUrl, keyword=实际关键词)`（单源真实执行，日志 `unverifiable`；空关键词服务端改用「我的」；读 `structuredResult.logVerdict` 判断四阶段是否到达）→（可选）`reader_web_search(keyword)`（全局搜索补充，代价大，非单源验证）→ `reader_web_page(action=open)`（同一 WebView 会话打开）→ `snapshot`/元素列表 → `click`/`fill`/`scroll`/`back` 导航到书源管理或调试页面 → 按页面实际显示取证。**判定身份的是这个网页**，不是 Legado 官方 API；`reader_web_verify` 只在接口恰好匹配时作可选快捷。

**交付门槛 `deliveryStatus`（服务端判定）**：`not_in_reader` 只用于回读确实不存在；`presentInReader=true` 而 `confirmed=false` 仍自动调试现存阅读版本，但标 `reader_fields_unconfirmed`，调试状态另见 `readerDebugDeliveryStatus`，**不得称本地字段已生效**。`reader_debug_incomplete` 包括 TIMEOUT、无终止信号或日志截断；已有失败行需照实转述，却不能称日志完整或通过。`reader_failure_signals` 指本次失败行，旧记录保留在 `historicalFailureSignals`，交付以最近一次 debug 为主。`stagesReached` 仅表示进入阶段（例如「开始解析搜索页」「开始解析详情页」；「按详情页解析」只是空列表回退提示，不代表进入详情），不等于该阶段完成。任何情况都禁止「通过 / 一致 / 验证成功」话术。

**两个 WS 工具的目标与口径**：目标只由用户保存的 origin 派生——同主机、端口 = 用户保存端口 + 1（`http`→`ws`、`https`→`wss`）、路径固定 `/bookSourceDebug` 或 `/searchBook`，不带查询串、不跟随重定向、不走系统代理；65535 时 `PORT_OVERFLOW`。**端口只由用户配置推导，不固定、不猜、不扫描、不向其它私网主机发请求**。`reader_web_debug` 先 `GET /getBookSource?url=` 精确确认书源已保存（否则 `SOURCE_NOT_IN_READER`，不运行），回包恒为 `legacy_debug_only`/`machineVerdict=unverifiable`：证明阅读端确实执行过，但**不产 `PASSED`**。`reader_web_search` 只收真正的 JSON 数组帧，`status` 为 `completed`/`no_data`/`empty`/`incomplete`/`partial`；它是**全局搜索**，目标源被禁用或不在阅读当前搜索范围时结果里没有它**不代表该源失败**，登记为「本次全局搜索未覆盖该源」。分支不支持对应 WS 时按 `ENDPOINT_MISSING`/`HANDSHAKE_TIMEOUT`/`UNREACHABLE` 等如实回报。

**结构化同步与诊断怎么读**：`reader_web_sync_source` 的请求顺序固定为 `GET /getBookSource`（精确预检成功）→ `POST /saveBookSource`（写入）→ `GET /getBookSource`（回读）；预检未证实不存在或已有源未获覆盖授权时不得 POST，`GET /saveBookSource` 不能当写入。旧版阅读可能把 ReturnData JSON 标成 `text/html`，须检查正文而非只看头；端口一律取用户保存的地址。`writeOutcome` 说明写入是否到达阅读：`not_attempted`/`not_sent` 表示没写，`rejected` 表示阅读拒绝，`accepted` 表示已接收（回读失败时报 `READBACK_FAILED`），`unknown` 表示写请求已发出但没拿到响应——此时**不要重发**，先在阅读书源列表确认该源是否已存在再决定。`diagnostic`/`webPageProbeDiagnostic` 只给失败阶段与异常类别，用来区分连接重置、超时还是拒绝连接，不要据此建议固定或更换端口。`fetch_page` 对已保存 origin 返回的引导（`requestIssued=false`）是本 App 生成的，不是阅读服务器的响应，不能当成调用结果。

### 失败分诊

| 返回 | 含义 | 行动 |
|---|---|---|
| `status=not_configured` | 用户还没在 MCP 页「设置」→「阅读 Web 服务」填地址 | 提示填写（同机可填 `http://127.0.0.1:<阅读端口>`，端口以阅读设置为准）；登记未覆盖，继续汇报其余证据 |
| `status=auth_required`（`authRequired=true`/`authReady=false`） | 地址**有响应**，但鉴权没过：令牌缺失/无效、`401`/`403` 或 Bearer 挑战。不是离线，也不是「不是阅读」 | `allowedNextActions` 给 `configure_reader_web_token`（网页可开或只是登录墙时同时给 `reader_web_page`）：让用户补令牌（改地址会清旧令牌），或直接在网页里登录后用 `reader_web_page`；**不要**先调结构化同步/检验 |
| `capabilities.* = unauthorized` | 接口存在，但当前凭据过不了鉴权 | 不能当 `unsupported`（版本不支持），也不能当可用；先解决令牌，再重测 |
| `status=unreachable` / 超时 | 根路径与接口都没有响应 | 核对阅读是否开启 Web 服务、端口是否一致、异机是否同一 Wi-Fi/可达 VPN；蜂窝下跨设备私网地址通常不可达。不要猜默认端口，端口从 `originEcho` 读用户保存的真实值 |
| `status=web_page_available`（`webPageReachable=true`、`apiProfile=mismatch`） | **网页在线，官方接口不匹配**（分支/版本不同）。根路径 2xx 拿到 HTML 但没识别到官方接口，正确结果就是这条 | 直接用 `reader_web_page` 继续；不要断言「不是阅读」，不要要求升级，也不要因此改书源规则 |
| `status=web_page_unavailable` | 服务有响应（HTTP 状态已到），但根路径不是可用的页面入口 | 核对端口是否指向阅读的 Web 服务入口；不要改规则 |
| `status=api_only_available` | 官方接口有正面证据，但根路径没有可用页面 | 只说明接口可达：网页与书源都未证实；可用结构化快捷，或让用户核对阅读显示的实际地址。不说「不是阅读」，也不说书源通过 |
| `status=web_page_unreachable` | 根路径在网络层没拿到响应，但服务在别的路径有响应 | 分清是网页入口不可达还是地址填错；不要笼统报「网页不可用」 |
| `status=web_page_probe_incomplete` | 根路径已回状态码但正文没读完，页面是否可用未确认 | 重试一次或用 `reader_web_page` 实际打开看，不要当失败结论 |
| `webPageReachable=true` 但页面里搜不到书/正文 | 网页能开，只是这一次书源表现不对 | 继续在网页里导航取证（换关键词、翻目录、点章节），按页面实际内容判断；**根路径 200 本身不是书源通过的证据** |
| `TOKEN_REQUIRED` / `TOKEN_INVALID`（结构化快捷） | 阅读开了「访问令牌保护」 | 在 MCP 页「设置」填写与阅读设置**完全相同**的令牌；改地址后会清除旧令牌，需重新填写 |
| `NOT_OPENED` / `CLOSED` / `TIMEOUT` / `LOAD_FAILED` / `CONFIG_CHANGED` / `OFF_ORIGIN`（`reader_web_page`） | 会话不在、加载失败、保存地址已变更或页面跳出了已保存 origin | 重新 `action=open`；`OFF_ORIGIN` 说明页面越域，本工具不会跟随，核对地址后重开 |
| `TARGET_NOT_FOUND` / `INVALID_SELECTOR`（`reader_web_page`） | selector 没匹配到或语法无效 | 重新 `snapshot` 取当前 `selector` 再点/填 |
| `UNSUPPORTED_INPUT`（`reader_web_page`） | 目标是文件类输入框 | 无法用 `fill` 上传文件，改人工操作或换页面路径 |
| `conflict`（同步） | 阅读里已有同 URL 但内容不同，默认**不写** | 向用户说明不同的顶层字段名并取得明确授权后才可 `overwrite=true`；未授权就停在冲突 |
| `written_unconfirmed` | 已写且回读存在，但字段不一致 | 调试仍自动运行现存阅读版本，不算本地字段生效；按 `mismatchedFields` 排查。仅本地省略、远端补 `enabledCookieJar=true` 记 `normalizedFields`，显式 false 与其它差异不豁免 |
| `SOURCE_NOT_IN_READER` | 阅读里没有该书源 | 先在 `reader_web_page` 的书源管理页导入/粘贴，或在接口匹配时 `reader_web_sync_source`；`reader_web_verify` 不会自动上传 |
| `legacy_debug_only` / `machineVerdict=unverifiable` | 阅读版本没有结构化检验接口，已自动降级跑旧版 WebSocket 调试 | 旧版路径仍可用：据日志定位并修规则，但只有日志、**没有通过/失败结论**，旧版 WS 日志不判 `PASSED` |
| `stale_state` / `session_replaced` / `session_unconfirmed` / `timeout` | 没拿到归属本次检验的新状态 | 都不是通过；`timeout` 可稍后重跑，避免多客户端同时检验同一书源 |
| `check` 能力 `unsupported` | 阅读已连通，但该版本没有结构化检验路由 | 走网页工具或 `reader_web_debug`/`reader_web_search`（都不依赖新版 REST）；只有需要机器可判的 `PASSED` 时才考虑升级阅读，或改在阅读的 Web 书源页面手动检验 |
| `legacy_debug_only` / `machineVerdict=unverifiable`（`reader_web_debug`，或 `reader_web_verify` 降级） | 阅读端真实执行了旧版 WebSocket 调试 | 证明阅读端执行过；据日志定位修规则，但**没有通过/失败结论**，旧版 WS 日志不判 `PASSED`，不写成本实例 `check_source` 证据 |
| `SOURCE_NOT_IN_READER`（`reader_web_debug`） | 阅读里没有该书源 | 未运行调试，也不自动导入；先同步或在网页里导入并取得用户确认 |
| `ENDPOINT_MISSING` / `HANDSHAKE_TIMEOUT` / `UNREACHABLE` / `PROTOCOL_ERROR`（WS） | 该分支/版本不支持对应的 `/bookSourceDebug` 或 `/searchBook`，或端口 +1 上没有服务 | **如实回报不支持或不可达**，不要换端口、不要扫描；改走 `reader_web_page` 或结构化接口。端口只来自用户保存配置 +1 |
| `TOKEN_REQUIRED` / `TOKEN_INVALID`（WS 关闭码 1008/4401/4403 或 HTTP 401/403） | WS 子协议令牌被拒 | 在 MCP 页填写与阅读一致的令牌；改地址会清旧令牌 |
| `reader_web_search` `status=completed/partial` 但结果里没有目标源 | `byOrigin` 按完整键计数；`#probeUA` 与无后缀正式源是不同书源 | **不是该源失败或正式源命中**；登记「本次全局搜索未覆盖该源」，改用 `reader_web_debug` 或阅读网页单源搜索 |
| `reader_web_search` `status=no_data` / `empty` | 结束但没有数组帧 / 有帧但没有书 | 只能说明本轮阅读搜索无结果，不能推出任何单源结论 |
| `NO_EFFECT` / `ACTION_UNVERIFIED`（`reader_web_page`） | 事件已派发，但没观察到导航/DOM 变化，或变化无法确认 | **不是点击或搜索成功**；重新 `snapshot` 看真实页面，或改用 `reader_web_debug`/`reader_web_search` 取证 |

### 不要做

- 不要把网页里看到的一次可用性、`reader_web_debug` 的日志或 `reader_web_search` 的全局搜索结果写成「书源通过」，也不要把阅读端 `PASSED` 写成本实例 `check_source` 的四阶段证据；两边证据互不替代，`export_checked_sources` 只采信本实例 `check_source`。沙箱 `check_source` 只可初筛，不是最终判断。
- 不要用网页根路径 200、登录页能打开、接口探测有响应来证明书源可用；只有页面实际显示搜索结果/目录/正文、或阅读端真实执行结果才算。
- 不要把 `reader_web_search` 当成单源验证：它是全局搜索，目标源不在阅读搜索范围时没有结果不代表失败；也不要拿日志帧冒充搜索到的书（非 JSON 数组帧会被 `INCOMPATIBLE_FRAME` 拒绝）。
- 不要猜端口、不要为了「换端口再试」改地址或扫描端口：HTTP 只访问用户保存的 origin，WS 只连该 origin 端口 +1 的固定路径；也不要向其它私网地址发请求。
- 不要在 `apiProfile=mismatch`、返回 HTML 或 401 时断言「不是阅读」或要求用户升级；那是接口不匹配，网页、`reader_web_debug` 与 `reader_web_search` 仍可用。
- 不要在阅读 Web 不可达/未配置时改用 `import_reader` 的唤起回执冒充导入成功或检验通过——该工具只负责唤起阅读的导入勾选页（`launched=true`/`confirmation=pending_user`/`confirmed=false`），最终是否导入由用户在阅读里确认，确认后仍需 `reader_web_status`/`reader_web_page`/`reader_web_debug` 核实。
- 不要跳过 `reader_web_status` 直接开页、跑 WS 或同步/检验：先确认配置存在与真实端口，能避免把环境故障误判为书源问题，也能避免猜端口。
- `reader_web_page` 的 `click`/`fill` 与 `reader_web_debug`/`reader_web_search` 都有副作用（登录、翻页、提交搜索、阅读调试状态、搜索历史）；保存/覆盖/删除书源等写操作必须先得到用户确认。
- 不要并发用多个客户端检验同一书源：会互相取代（`session_replaced`）。
- 工具完整参数（精简连接只回首句描述）按名读 `studio://tools/{工具名}`，不要凭印象传未声明字段；新装 APK 后客户端必须重连才会刷新 `tools/list`。
- 不要把具体实例地址、令牌或个人书源内容写进公开仓库。
