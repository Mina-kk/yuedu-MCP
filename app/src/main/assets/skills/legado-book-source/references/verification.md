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

**覆盖范围必须逐链路分清**：`loginCheckJs` 是**写进成品书源、由官方阅读 App 在请求后执行**的钩子；Studio 沙箱的 `debug_source` / `check_source` / `eval_js` **不执行 `loginCheckJs`**（`EmbeddedLegadoRuntime` 无此调用点），不要指望在沙箱里靠它过盾。反过来，验证中心/每域 WebView 模式只存在于工坊调试期，官方阅读 App 没有这套机制——成品在真机出盾时只能靠 `loginCheckJs`/`startBrowserAwait`/URL `webView` 选项自理。因此「调试期过盾」与「成品过盾」是两条链路，都要验证，不能互相代替。

调试通过验证中心过完盾、规则写好后，如果目标站在真实使用中也会频繁出盾，应把 `loginCheckJs` 过盾逻辑写进书源，保证导入阅读 App 后仍能工作。验证码类站点的识别特征与处理见知识库：`search_knowledge("验证码")` → `read_knowledge("knowledge/图文验证码.md")`。
