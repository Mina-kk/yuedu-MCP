# JS / 请求语义差异研究（yuedu-MCP-v2 重构沙箱）

基线：`/root/projects/yuedu-MCP-v2` @ `e129d35e7cba08f8dcacc2cf05dc3e626b5965dd`（分支 `refactor/rust-vue-reader-semantics`）。
上游固定源码：`/root/.hermes/profiles/yd-mcp/research/yuedu-refactor/<owner__repo>/src`，commit 见各节。

> 证据分级（全文遵守）：
> - **[源码]** 直接读到的上游/本仓库源码行。
> - **[未验证]** 只是从源码推断，未跑测试、未跑真机。
> - **[environment]** 需要 Android / 联网 / WebView，离线沙箱不可判定，必须标 `unsupported`，不得假通过。
>
> 本次没有运行任何 Gradle 测试、真机调试或联网书源验证。唯一实测是 scratch 里一个 Rhino 探针，编译失败（见 §9），结论已按「未验证」处理。

---

## 1. 结论摘要（最小但真实的重构优先级）

| 优先级 | 问题 | 现状 | 该做什么 |
|---|---|---|---|
| P0 | URL 模板/`@js`/`{{}}`/`<page>`/URL 选项的解析链 | 沙箱只有 `LegadoUrlOptions.parse`（只认 `,{` 之后的 JSON，且只读 `header`/`headers`、`method`、`body`、`charset`、`bodyJs`、`webView`、`webJs`、`webViewDelayTime`）；`requestValue` 只做 `{{key}}`/`{{page}}` 字符串替换 + 通用 `{{js}}` | 按官方 `AnalyzeUrl.initUrl` 顺序实现：`analyzeJs`（`@js`/`<js>`，`@result` 拼接）→ `replaceKeyPageJs`（先 `{{}}` 后 `<a,b,c>`）→ `analyzeUrl`（切 `,{` 前的 URL、`getAbsoluteURL`、选项字段全量） |
| P0 | JS 作用域/绑定 | `RhinoEvaluator` 每次求值新建 `ScriptBindings`，`chainTo(新 TopLevel)`；`page`/`key` 只在部分阶段以 bindings 传入，`infoMap`/`extraParams` 完全缺失 | 引入「会话对象」统一承载 `page/key/baseUrl/result/source/book/chapter/infoMap/extraParams`，按阶段填值（见 §7） |
| P1 | jsLib 作用域 | 每次 `evaluateRaw` 重新 `eval(jsLib)` 到新父作用域；官方是每书源共享作用域（`getShareScope`） | jsLib 编译/执行结果按书源缓存为父作用域，规则求值只 chainTo，不重复执行 |
| P1 | `loginCheckJs` 时机与语义 | 沙箱在 `fetchValue` 里「响应后、bodyJs 前」执行，返回值按字符串/对象接管 body | 与官方一致（`WebBook` 各阶段 `analyzeUrl.evalJS(checkJs, it) as StrResponse`），但需补 `result` 为 `StrResponse` 的完整方法面 |
| P1 | 目录分页与去重 | 沙箱 `dedupeChaptersByUrl` + 100 页上限 + 不合成空 URL；官方 `LinkedHashSet<BookChapter>` 去重、空 URL 合成 `title+index`/`baseUrl`、`reverse` 逻辑 | 保留沙箱有界上限（安全），但去重/空 URL 语义要按官方，或明确标注「与阅读端总数可能不同」 |
| P2 | 正文格式 | 沙箱 `ContentPlainText.formatKeepImg` + 逐行 trim + 全文 replaceRegex | 官方 `HtmlFormatter.formatKeepImg` + `unescapeHtml4` + `useHtmlRegex` 占位符保护；`title` 规则、`subContent`、`nextContentUrl` 并发分支未覆盖 |

---

## 2. 官方 JS 绑定面（`AnalyzeUrl.evalJS` / `AnalyzeRule.evalJS` / `BaseSource.evalJS`）

### 2.1 `AnalyzeUrl.evalJS`
LegadoTeam/legado @ `330e606c4d57e08adc056ff8ab7a4132c784da9a`
`app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt:395-424`

```kotlin
bindings["java"] = this
bindings["baseUrl"] = baseUrl
bindings["cookie"] = CookieStore
bindings["cache"] = CacheManager
bindings["page"] = page
bindings["key"] = key
bindings["speakText"] / ["speakSpeed"]
bindings["book"] = ruleData as? Book
bindings["source"] = source
bindings["result"] = result
extraParams?.forEach { bindings[name] = ... }   // page 特判转 Int
bindings["infoMap"] = infoMap
```
作用域：`source.getShareScope(coroutineContext)` 优先，否则 `SharedJsScope.getCryptoScope(source ?: this, ...)`，再否则 `RhinoScriptEngine.getRuntimeScope(bindings)`；有 shared 时 `bindings.chainTo(sharedScope, sharedGlobalStateKey)`。

差异参考：
- HapeLee/legado-with-MD3 @ `2e2c63c3a7d2786719919fd0b11a197af9cf29bd` `AnalyzeUrl.kt:379-407`：**没有 `key`、没有 `extraParams`**，多了 `currentToneID/currentSpeakerName/currentEmotionName/currentEmotionTag`；用 `bindings.prototype = sharedScope`（不是 chainTo），**没有 `getSharedGlobalStateKey`**。
- Suml-1/Legado_Max @ `b3864087588ba624cb376e9f04772be2b6385656` `AnalyzeUrl.kt:375-399`：同 MD3，无 `key`/`extraParams`。
- Rimchars/legado @ `e111bf666199505d783e6910d8609f0e5cb043e0` `AnalyzeUrl.kt:376-405`：无 `key`；多 `currentSpeechRouteJson`；`getShareScope(coroutineContext, dnsScope)`。
- joestar817/legado_NG @ `7b6087410f287511080f2a93b82795231f4b72fd` `AnalyzeUrl.kt:374-405`：`cookie = BookSourceCookieStore.forSource(source)`、`cache = source.scriptCacheObject()`（**每书源独立 cache 命名空间**）、多 `voice/voiceId/voiceName/speakVolume/speakPitch`，`chainTo(sharedScope)` 无 globalStateKey。

→ 结论：**`key` 与 `extraParams` 在主流分支里并不一致**。沙箱若按 LegadoTeam 实现，需要在文档里写清「以 LegadoTeam 330e606 为准」，否则跨分支书源会两边都不对。

### 2.2 `AnalyzeRule.evalJS`
LegadoTeam `AnalyzeRule.kt:896-936`：`java/cookie/cache/source/book/result/baseUrl/chapter/chapters/title/src/nextChapterUrl/rssArticle/fromBookInfo`，`localBindings` 里的 `paraIndex/paraData/page` 条件注入。作用域：`getShareScope` → 否则 `topScopeRef`（第 17 次求值后缓存）→ 否则 `newStandardTopLevel()`。

差异：MD3 `AnalyzeRule.kt:834-863` 无 `chapters`；NG `:834-863` `cookie/cache` 换成本书源作用域对象。**`chapters`（批量正文 `contentBatch`）只有 LegadoTeam 有**。

### 2.3 `BaseSource.evalJS`（header/jsLib/login 用）
LegadoTeam `BaseSource.kt:414-434`：`java/source/sourceApi/baseUrl(=getKey())/cookie/cache`，`baseUrl` 在这里是**书源 URL**，不是页面 URL。`getHeaderMap` (`:197-222`)：`header` 若是纯 JS（`JS_PATTERN.matches` 全匹配）则 eval 后按 JSON 解析，否则直接当 JSON；异常吞掉只记 `AppLog`；最后补 `User-Agent`；`hasLoginHeader` 时合并 `getLoginHeaderMap()`。

→ 沙箱 `sourceHeaders()`（`EmbeddedLegadoRuntime.kt:702-710`）**只做 JSON 解析，完全不执行 header 里的 JS**，也不补 UA、不合并登录头。这是「书源被迫改写」的第一大来源。

### 2.4 `java` 对象（`JsExtensions`）
LegadoTeam `help/JsExtensions.kt`：`ajax(:176)/ajax(url,timeout:180)/ajaxAll(:200)/ajaxTestAll(:219)/connect(:240,256,260)/webView(:279,291)/webViewGetSource(:307)/webViewGetOverrideUrl(:342)/startBrowserAwait(:412)/getVerificationCode(:442)/cacheFile(:470)/getCookie(:499,504)/downloadFile(:518)/get(:575)/head(:602)/post(:629)/strToBytes/bytesToStr/base64*/hex*/timeFormat/timeFormatUTC/encodeURI/htmlFormat/t2s/s2t/getWebViewUA/readFile/readTxtFile/unzip*/queryTTF/replaceFont/toURL/toast/log/randomUUID/androidId/openUrl/showBrowser/singleFlight/lock/tick/getReadBookConfig/getThemeConfig`。
沙箱 `RhinoEvaluator.kt:379-987` 覆盖了其中一部分，缺 `ajaxTestAll`、`webViewGetOverrideUrl`、`startBrowser`、`cacheFile`、`downloadFile`、`importScript`、`getZipStringContent` 等。

---

## 3. jsLib 装载与作用域

官方：`BaseSource.jsLib: String?`（`BaseSource.kt:94`），`refreshJSLib(){ remove(jsLib) }`（`:394-401`）；实际装载在 `help/source/getShareScope`（**本次未取到该文件**，`tree.json` 里存在但不在 `selected.json`，故「jsLib 如何 eval 进 shared scope」为 **[未验证]**）。可确认的是：`AnalyzeRule.evalJS` / `AnalyzeUrl.evalJS` 都优先 `source.getShareScope(...)`，即 **jsLib 与规则 JS 共用同一书源级作用域**，跨规则、跨阶段可见。

沙箱：`RhinoEvaluator.kt:100-126` 每次求值都 `RhinoScriptEngine.eval(libSource, libScope)` 一遍 jsLib，再 `scope.chainTo(parent)`。
后果：
1. jsLib 里的 `var`/函数每次重新定义 → 依赖「jsLib 里初始化一次」的写法（计数器、`let` 单例、闭包缓存）语义不同。
2. jsLib 里的 `const {java,source}=this` 惯用法依赖沙箱自有的 `FEATURE_LEGADO_DYNAMIC_DEFAULT_THIS`，与官方 `chainTo` 的 this 绑定不同（**[未验证]**，探针未跑成）。
3. jsLib 为 http(s) URL 时沙箱直接跳过（`EmbeddedLegadoRuntime.kt:77-84`）并记诊断行；官方会下载加载。→ 标 `unsupported/environment`。

---

## 4. URL 语义（`@js` / 模板 / body / 编码 / 选项）

### 4.1 官方链路（LegadoTeam `AnalyzeUrl.kt`）
1. `initUrl` (`:175-183`)：`analyzeJs()` → `replaceKeyPageJs()` → `analyzeUrl()`。
2. `analyzeJs` (`:188-211`)：`AppPattern.JS_PATTERN` 逐个匹配 `@js:` / `<js></js>`，**先输出 JS 之前的字面量段（把其中的 `@result` 替换为上一次结果）**，再 `evalJS(js, result)`，末尾剩余段同样 `@result` 替换。即书源可写 `https://a.com/{{key}}@js:baseUrl.split('/')[2]` 这类拼接。
3. `replaceKeyPageJs` (`:216-243`)：先 `RuleAnalyzer.innerRule("{{","}}")`（**花括号平衡扫描**，`chompRuleBalanced`，能处理嵌套与引号）替换 `{{js}}`，数字整型特判 `%.0f`；再用 `pagePattern = "<(.*?)>"` 替换 `<a,b,c>` 分页段（`page < size ? pages[page-1] : pages.last()`）。
   - 注释明确：「先替换内嵌规则再替换页数规则，避免内嵌规则中存在大于小于号时，规则被切错」。
4. `analyzeUrl` (`:248-317`)：`paramPattern = "\\s*,\\s*(?=\\{)"` 切出 `urlNoOption`；`NetworkUtils.getAbsoluteURL(baseUrl, urlNoOption)`；`GSONStrict` → 失败再 `GSON` 宽松解析 `UrlOption`（并 log 提示）。
5. `UrlOption` 字段 (`:829-1031`)：`method/charset/headers/body/origin/retry/type/webView/webJs/timeout/followRedirects/dnsIp(alt resolveIp)/js/bodyJs/serverID/webViewDelayTime`。
   - `headers` 支持 **JSON 字符串或对象**；`body` 支持对象/数组（序列化回 JSON 字符串）；`webView` 接受 `true/"true"/非空`；`retry` 从字符串转 Int。
   - `option.js`：**在 URL 已绝对化之后**执行 `evalJS(jsStr, url)`，结果覆盖 `url`（`:292-296`）。
   - `bodyJs`：**响应拿到后**执行 `evalJS(bodyJs, it.body)`，结果替换 body（`:548-551`）。
6. 编码（`:329-389`）：GET 走 `analyzeQuery`，POST 走 `analyzeFields`；`charset` 为空 → UTF-8 且 `checkEncoded=true`（已编码的 `%xx` 原样保留）；`charset=="escape"` → `EncoderUtils.escape`；GET 且 charset 非空时若 `NetworkUtils.encodedQuery(params)` 为真则整体不编码，否则用 `RFC3986.UNRESERVED.orNew(PercentCodec.of("!$%&()*+,/:;=?@[\\]^`{|}"))` 编码（**保留 `=&` 等分隔符**）。
7. `getStrResponseAwait` (`:454-470`)：`type != null` → 直接 hex 返回（`StrResponse(url, HexUtil.encodeHexStr(getByteArrayAwait()))`）；`concurrentRateLimiter.withLimit` 包裹；`setCookie()` 在请求前（`urlOption 临时 cookie > 数据库 cookie`，`mergeCookies`）；`enabledCookieJar` 时写 `CookieManager.cookieJarHeader`。
8. POST 分支 (`:519-543`)：`encodedForm` 非空或 body 空 → `postForm`；有 `Content-Type` → `post(body.toRequestBody(contentType))`；否则 `postJson(body)`。
9. `webView` 分支 (`:482-517`)：POST 先发一次请求，再 `BackstageWebView(url=res.url, html=res.body, javaScript = webJs ?: jsStr, ...)`；GET 直接 `BackstageWebView(url, ...)`。`followRedirects` 与 `res.raw.code` 有 `shouldReturnRedirectBeforeWebView` 早退（`AnalyzeUrlNetworkOptions.kt`）。

### 4.2 沙箱现状
- `LegadoUrlOptions.kt:19-38`：`lastIndexOf(",{")` 切分（官方是正则 `\s*,\s*(?=\{)`，**允许逗号与 `{` 之间有空格**，沙箱不允许）；只解析 `header`/`headers`、`method`、`body`、`charset`、`bodyJs`、`webView`、`webJs`、`webViewDelayTime`。**缺 `js`、`retry`、`type`、`timeout`、`followRedirects`、`dnsIp`、`origin`、`serverID`、`proxy`**。
- `requestValue`（`EmbeddedLegadoRuntime.kt:510-526`）：`@js:` 前缀整体当 JS 求值（**不支持 `@js` 出现在中间、不支持 `@result` 拼接、不支持 `<js></js>`**）；`{{key}}` 用 `URLEncoder.encode`（**官方是 `encodeQuery`/`encodeParams` 的 RFC3986 语义，且 `key` 已由 `replaceKeyPageJs` 处理**）；`{{page}}` 取 `bindings["page"] ?: "1"`（**不处理 `<1,2,3>` 分页段**）。
- `fetchValue`（`:408-465`）：`resolve(base, parsed.url)` 用 OkHttp `HttpUrl.resolve`；**没有 `option.js` 覆盖 URL**；`bodyJs` 在 `loginCheckJs` 之后执行（官方 `bodyJs` 在 `getStrResponseAwait` 内、`loginCheckJs` 在 `WebBook` 外，**顺序相反**）。
- `sourceHeaders`（`:702-710`）：只 JSON 解析，不执行 header JS。
- 没有 `charset` 编码语义（`HttpFetcher.FetchRequest` 只带 `charset` 字段，编码逻辑未在本文件出现，**[未验证]** 是否实现）。

---

## 5. header JS / loginCheckJs / webView / webJs

- **header JS**：官方 `BaseSource.getHeaderMap` 支持纯 JS（`JS_PATTERN.matches`）返回 JSON 字符串；沙箱无。→ 书源被迫把 header JS 改写成静态 JSON，或改写成 `@js` 请求（非阅读规则）。
- **loginCheckJs**：官方在 `WebBook` 每个阶段 `analyzeUrl.evalJS(checkJs, it) as StrResponse`（`WebBook.kt:77-82, 151-156, 229-235, 329-335, 456-462`），即 **`result` 是 `StrResponse`，返回值必须能强转 `StrResponse`**。沙箱 `applyLoginCheckJs`（`EmbeddedLegadoRuntime.kt:474-502`）接受 null/String/StudioJsResponse/Map/NativeObject，**不做 `as StrResponse` 强转**，且顺序在 `bodyJs` 之前（与官方 `bodyJs` 在 `getStrResponseAwait` 内、`loginCheckJs` 在外一致，但沙箱 `bodyJs` 也在 `fetchValue` 内，实际变成 loginCheck → bodyJs，官方是 bodyJs → loginCheck）。**[源码]** 顺序差异成立。
- **webView/webJs**：官方 `AnalyzeUrl` 的 `useWebView` 分支与 `AnalyzeRule.getWebJsResult`（`AnalyzeRule.kt:182-199`，`cacheFirst=true, timeout=10000, result=GSON.toJson(result), isRule=true`）。沙箱 `WebViewPageLoader` 走 `loader.load(absolute, parsed.webJs, parsed.webViewDelayTime)`，**`webViewDelayTime` 默认 800ms，官方默认 0**（`AnalyzeUrl.kt:128` `webViewDelayTime: Long = 0`）。→ 默认值差异会让部分页面等待时间不同（不影响正确性但影响复现）。
- `Mode.WebJs`（规则里 `@webJs:`）沙箱 `LegadoStringRule` 只有 `Default/Js` 两种 mode，**没有 WebJs/XPath/Json/Regex 的 mode 区分**（`LegadoStringRule.kt:14`）。

---

## 6. 分页与内容格式

### 6.1 目录（`BookChapterList.kt`）
- `nextTocUrl` 用 `getStringList(..., isUrl = true)`，**过滤掉等于 `redirectUrl` 的项**（`:214-220`）。
- 0 条结束；1 条串行跟；2+ 条 `mapAsync(threadCount)` **并发**展开，二级页 `getNextUrl=false`（`:68-119`）。
- 空 `chapterUrl`：卷名 → `title + index`，非卷名 → `baseUrl`（`:262-276`）。
- 去重 `LinkedHashSet(chapterList)`（`:129`），`reverse` 两次（`:124-133`），最后 `index` 重排。
- `formatJs`（`:139-157`）：`gInt/index/chapter/title` 绑定，逐章改写 `title`。
- 沙箱：`dedupeChaptersByUrl` 只按非空 URL 去重、不合成空 URL、100 页上限、`nextTocUrl` 返回 2+ 时一层展开（与官方一致），但**没有 `formatJs`、没有 `isVip/isPay`、没有 `reverse` 处理**。→ 沙箱明确写了「不得声称目录总数与阅读完全一致」（`:280-286`），这是诚实但也是差异。

### 6.2 正文（`BookContent.kt`）
- `analyzeContent`：`getString(contentRule.content, unescape = false)` → `HtmlFormatter.formatKeepImg(content, rUrl)` → `unescapeHtml4`（`:242-258`）。
- `nextContentUrl`：`getStringList(isUrl=true)`，1 条串行、2+ 条并发（`:76-130`）；`mNextChapterUrl` 由上一章/第 0 章兜底（`:56-61`）。
- 全文 `replaceRegex`：**先合并多页**，再 `split(LFRegex).joinToString("\n"){ it.trim() }`，再 `analyzeRule.getString(replaceRegex, contentStr)`（`:169-178`）。
- `title` 规则在正文之后执行，支持 `imgRegex` 提取图片（`:179-201`）。
- `subContent`：音频/视频走 `putLyric/putDanmaku`（`:131-168`）。
- 沙箱：`ContentPlainText.formatKeepImg` + 逐行 trim + 全文 replaceRegex（`EmbeddedLegadoRuntime.kt:331-356`），**没有 `title` 规则、没有 `subContent`、没有 `useHtmlRegex` 占位符保护**。

---

## 7. 建议的运行时依赖注入 / 会话对象接口（最小）

不要堆 adapter，先立一个「阶段上下文」对象，把所有官方 bindings 一次算清：

```kotlin
/** 一次书源求值的完整上下文；字段与官方 AnalyzeUrl/AnalyzeRule bindings 一一对应。 */
data class LegadoEvalSession(
    val stage: Stage,                 // Search/Explore/Info/Toc/Content
    val sourceKey: String,            // bookSourceUrl
    val baseUrl: String,              // 阶段基准 URL（官方 baseUrl，非书源 URL）
    val result: Any?,                 // 官方 result
    val page: Int? = null,            // 官方 page（URL 阶段）
    val key: String? = null,          // 官方 key（URL 阶段）
    val infoMap: Map<String, String>? = null,   // 发现分类
    val extraParams: Map<String, String>? = null,
    val book: BookJsApi,
    val chapter: ChapterJsApi? = null,
    val nextChapterUrl: String? = null,
    val chapters: List<ChapterJsApi>? = null,   // contentBatch
    val fromBookInfo: Boolean = false,
)

interface LegadoRuntimeDeps {
    val fetcher: HttpFetcher
    val cookies: RuntimeCookieStore
    val cache: CacheJsApi              // 按书源命名空间
    val webView: WebViewPageLoader?    // null = 不支持，必须报 unsupported
    fun shareScope(sourceKey: String): Scriptable   // 每书源 jsLib 作用域，缓存
    fun headerMap(sourceKey: String, hasLoginHeader: Boolean): Map<String, String>  // 执行 header JS
}
```

`RhinoEvaluator.evaluateRaw` 改为接收 `LegadoEvalSession` + `LegadoRuntimeDeps`，内部按官方顺序组装 bindings；`jsLib` 只在 `shareScope` 首次创建时执行一次。

---

## 8. 离线测试案例候选（≥12）

字段：`id / 阶段 / 输入 / 预期 / 源码依据 / 平台能力 / 状态`。
「状态」= `candidate`（可离线复现）/ `unsupported`（需 Android/联网/WebView）/ `unknown`（预期未确认）。

| # | 阶段 | 输入 | 预期 | 源码依据 | 平台能力 | 状态 |
|---|---|---|---|---|---|---|
| 1 | URL | `searchUrl: "https://a.com/s?q={{key}}&p={{page}}"`，key=`剑 来`，page=2 | `q=%E5%89%91%20%E6%9D%A5`（RFC3986，空格 `%20` 非 `+`） | `AnalyzeUrl.kt:329-345` + `queryEncoder` `:819-820` | 纯字符串 | candidate |
| 2 | URL | `searchUrl: "https://a.com/{{page*10}}/list"`，page=3 | `https://a.com/30/list`（`Double` 整型特判 `%.0f`） | `AnalyzeUrl.kt:221-229` | Rhino | candidate |
| 3 | URL | `searchUrl: "https://a.com/s/<1,2,3>.html"`，page=5 | `https://a.com/s/3.html`（超出取 last） | `AnalyzeUrl.kt:232-242` | 纯字符串 | candidate |
| 4 | URL | `searchUrl: "https://a.com/{{key}}@js:baseUrl.split('/')[2]"`，key=`x` | 先 `{{key}}`→`x`，再 `@js` 得到 `a.com`，`@result` 拼接 → `https://a.com/xa.com`（**待确认**） | `AnalyzeUrl.kt:188-211` | Rhino | unknown（`@result` 拼接顺序需实测） |
| 5 | URL | `"https://a.com/api , {\"method\":\"POST\",\"body\":{\"q\":\"x\"}}"`（逗号后有空格） | 识别为 URL + 选项，body 序列化为 `{"q":"x"}` | `AnalyzeUrl.kt:248-259, 953-966` | 纯解析 | candidate（沙箱 `lastIndexOf(",{")` 会失败） |
| 6 | URL | `"https://a.com/a,{\"js\":\"baseUrl + '/b'\"}"`，baseUrl=`https://a.com/a` | 最终 url = `https://a.com/a/b`（`option.js` 覆盖） | `AnalyzeUrl.kt:292-296` | Rhino | candidate（沙箱缺 `js`） |
| 7 | URL | `"https://a.com/x,{\"charset\":\"escape\"}"` + query `q=中文` | `q=%u4E2D%u6587`（`EncoderUtils.escape`） | `AnalyzeUrl.kt:333-339, 384-385` | 纯字符串 | candidate |
| 8 | JS 作用域 | `@js: page + 1`，page=3 | `4`（数字，不是 `"31"`） | `AnalyzeUrl.kt:401` | Rhino | candidate |
| 9 | JS 作用域 | `@js: key`，key=`剑来` | `剑来` | `AnalyzeUrl.kt:402` | Rhino | candidate（沙箱仅在 search/explore 传 key） |
| 10 | JS 作用域 | `@js: typeof infoMap === 'undefined' ? 'none' : JSON.stringify(infoMap)`（发现阶段） | `{"class":"玄幻"}` | `AnalyzeUrl.kt:411`、`WebBook.kt:149` | Rhino | candidate（沙箱缺 infoMap） |
| 11 | JS 作用域 | 两次求值：第一次 `var g=1;`，第二次 `typeof g` | 官方 shared scope 下为 `number`；无 shared 时为 `undefined` | `AnalyzeRule.kt:916-931` | Rhino | unknown（取决于 shared scope 是否建立） |
| 12 | jsLib | `jsLib:"function f(){return 1}"`，两次 `@js: f()` | 两次都 `1`，且 jsLib 只执行一次（副作用计数不累加） | `BaseSource.kt:94,394-401` + `getShareScope`（**文件未取到**） | Rhino | unknown |
| 13 | header | `header: "@js: JSON.stringify({Referer: baseUrl})"` | 请求带 `Referer` = 书源 URL | `BaseSource.kt:197-222` | Rhino | candidate（沙箱不执行 header JS） |
| 14 | header | `header: "{\"X\":\"1\"}"` | 自动补 `User-Agent` | `BaseSource.kt:214-216` | 纯解析 | candidate |
| 15 | loginCheckJs | `loginCheckJs: "result.body().includes('登录') ? java.connect('https://a.com/login').body() : result"` | 返回 `StrResponse` 被强转；沙箱按 Map/String 接管 | `WebBook.kt:77-82`、`EmbeddedLegadoRuntime.kt:474-502` | Rhino | candidate（语义差异） |
| 16 | 顺序 | `bodyJs:"result + '!'"` + `loginCheckJs:"result"` | 官方先 bodyJs 后 loginCheckJs | `AnalyzeUrl.kt:548-551` vs `WebBook.kt:77-82` | Rhino | unknown（沙箱顺序相反，需实测确认官方顺序） |
| 17 | 目录 | 空 `chapterUrl` + `isVolume:true` | url = `title + index` | `BookChapterList.kt:262-276` | 纯逻辑 | candidate（沙箱不合成） |
| 18 | 目录 | 两页返回同一章节 URL | `LinkedHashSet` 去重后 1 条 | `BookChapterList.kt:129` | 纯逻辑 | candidate（沙箱近似一致） |
| 19 | 目录 | `formatJs:"'第'+(index)+'章 '+title"` | 标题被改写 | `BookChapterList.kt:139-157` | Rhino | candidate（沙箱缺） |
| 20 | 正文 | `content:"<p>a</p><p>b</p>"` | `HtmlFormatter.formatKeepImg` 后 `a\nb`，再 `unescapeHtml4` | `BookContent.kt:242-258` | jsoup | candidate |
| 21 | 正文 | `content` 规则输出含 `&amp;` | `unescape=false` 时保留 `&amp;`，`formatKeepImg` 后 `unescapeHtml4` → `&` | `BookContent.kt:242,253-255` | jsoup | candidate |
| 22 | 正文 | `replaceRegex` + 两页合并 | 先合并再逐行 trim 再 replace | `BookContent.kt:169-178` | 纯逻辑 | candidate（沙箱一致） |
| 23 | 正文 | `title:"@js: result.match(/《(.+)》/)[1]"` | `chapter.title` 被覆盖 | `BookContent.kt:179-201` | Rhino | candidate（沙箱缺） |
| 24 | webView | `{"webView":true,"webJs":"...","webViewDelayTime":0}` | 官方默认 0ms；沙箱默认 800ms | `AnalyzeUrl.kt:128` vs `EmbeddedLegadoRuntime.kt:16` | Android WebView | unsupported |
| 25 | 联网 | `java.ajax` 二次请求带 cookie | 依赖 `enabledCookieJar` + `mergeCookies` | `AnalyzeUrl.kt:752-774` | 联网 | unsupported |

---

## 9. 本次实测记录（诚实）

- 尝试用 `htmlunit-core-js-5.3.0-legado.4.jar`（项目 `third_party/maven` 自带）在 scratch 写 Rhino 探针，验证「顶层 `var` 是否落到 bindings 作用域」「函数内 `this`」「`let` 跨编译单元」等。
- **探针编译失败**：该 fork 的 `Scriptable`/`VarScope`/`TopLevel` 泛型签名与标准 Rhino 不同（`Scriptable extends PropHolder<Scriptable>`，`newObject(VarScope)` 返回 `Scriptable`），我按标准 Rhino API 写的 `setPrototype(TopLevel)` / `evaluateString(VarScope)` 均不匹配，未能在预算内修正。
- 因此 §2/§3 中任何「`this` 指向」「`var` 泄漏」结论均为 **[未验证]**，不要当作实测。

## 10. 未取到的关键文件（影响结论强度）

`tree.json` 中存在但 `selected.json` 未包含，故未读：
- `app/src/main/java/io/legado/app/constant/AppPattern.kt`（`JS_PATTERN`/`WebJS_PATTERN`/`dataUriRegex` 的正则原文）
- `help/source/getShareScope` 所在文件（jsLib 如何进 shared scope）
- `help/CacheManager.kt`、`help/http/CookieStore.kt`、`help/http/BackstageWebView.kt`
- `data/entities/BookChapter.kt`（`equals/hashCode`，决定目录去重语义）

这些文件的缺失使 §3 jsLib 作用域、§6.1 去重语义只能标 **[未验证]**。
