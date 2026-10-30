# JS API 参考

> **能力边界**：本文同时包含工坊实际支持清单和原版阅读 API 参考，不能将参考条目全部视为工坊实现。`java` 由工坊的 `StudioJsApi` 桥接；同名方法的参数、返回值、状态和 Android 平台能力仍可能与目标阅读版本不同。制作时以「Studio 沙箱可用 API 清单」为准，导出后需在目标版本验证。

> 阅读内置 htmlunit-core-js（Rhino 分支，ES6 模式）作为 JavaScript 引擎以便于调用 Java 类和方法；`java` 变量指向已被阅读修改，想要调用 `java.*` 下的包，请使用 `Packages.java.*`

> [Rhino 运行时](https://github.com/mozilla/rhino/blob/master/rhino/src/main/java/org/mozilla/javascript/ScriptRuntime.java)懒加载导入的 Java 类和方法

| 构造函数 | 函数 | 对象 | 调用类 | 简要说明 |
|------|-----|------|----|------|
| JavaImporter | importClass importPackage | | [ImporterTopLevel](https://github.com/mozilla/rhino/blob/master/rhino/src/main/java/org/mozilla/javascript/ImporterTopLevel.java) | 导入 Java 类到 JavaScript |
| | getClass | Packages java javax ... | [NativeJavaTopPackage](https://github.com/mozilla/rhino/blob/master/rhino/src/main/java/org/mozilla/javascript/NativeJavaTopPackage.java) | 默认导入 JavaScript 中的 Java 类 |
| JavaAdapter | | | [JavaAdapter](https://github.com/mozilla/rhino/blob/master/rhino/src/main/java//org/mozilla/javascript/JavaAdapter.java) | 继承 Java 类 |

> 注意 `java` 变量指向已经被阅读修改，如果想要调用 `java.*` 下的包，请使用 `Packages.java.*`

> 在书源规则中使用 `@js` `<js>` `{{}}` 可使用 JavaScript 调用阅读部分内置的类和方法

> 注意为了安全，阅读会屏蔽部分 java 类调用，见 [RhinoClassShutter](https://github.com/gedoor/legado/blob/master/modules/rhino/src/main/java/com/script/rhino/RhinoClassShutter.kt)

> 不同的书源规则中支持的调用的 Java 类和方法可能有所不同

> 注意使用 `const` 声明的变量不支持块级作用域，在循环里使用会出现值不变的问题，请改用 `var` 声明

## 内置变量

| 变量名 | 调用类 |
|------|-----|
| java | 当前类 |
| baseUrl | 当前 url, String |
| result | 上一步的结果 |
| book | [书籍类](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/data/entities/Book.kt)（沙箱实现 `BookJsApi`，属性/方法与官方同名） |
| rssArticle | [Article 类](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/data/entities/RssArticle.kt)（沙箱未注入） |
| chapter | [章节类](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/data/entities/BookChapter.kt)（沙箱实现 `ChapterJsApi`，属性/方法与官方同名） |
| source | [基础书源类](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/data/entities/BaseSource.kt) |
| cookie | [cookie 操作类](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/help/http/CookieStore.kt) |
| cache | [缓存操作类](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/help/CacheManager.kt) |
| title | 章节当前标题 String |
| src | 请求返回的源码 |
| nextChapterUrl | 下一章节 url |
| fromBookInfo | 是否为详情页刷新（官方绑定名，无 is 前缀） |

> **规则 JS 里没有 `url` 变量。** 取当前页地址用 `baseUrl`；`@js:url`、`<js>url</js>` 在阅读端会报 `ReferenceError: “url” 未定义`（沙箱同样报错）。`url` 只作为局部变量（如 `var url = ...`）或对象字段（如 `chapter.url`）出现。
>
> **`getString` vs `getStringList`：链式 `@js` 的 `result` 类型不同。** `content` 等字段走 `getString()`，`result` 是 **String**（多元素已 join）；`nextContentUrl` / `nextTocUrl` 走 `getStringList(..., isUrl=true)`，`result` 是 **Java List**。list 型规则第一句先 `var s = '' + result`。
>
> **`isUrl=true` 时空串。** 阅读会把空串解析成当前页 URL，分页可能停在本页或打穿后续章节。工坊 `--` 正文通道没有 `mNextChapterUrl` 哨兵，空串会 **skip** 并在日志打 `nextContentUrl <empty> (skipped)`，避免跟成当前页。这是通道保护，与阅读差异见 [`content-rules.md`](content-rules.md)。

## java 对象方法

函数带有默认值的函数会自动重载，可以不填。

### Studio 沙箱可用 API 清单（与原版阅读的差异）

> 本节清单描述的是 Studio 沙箱（MCP 工具 `eval_js` / `debug_source` / `save_source`）已实现的 JS 范围，不代表目标阅读端的完整能力。清单之外的 `java.*`、`Packages`、`org.jsoup` 等在真实阅读端通常可用；沙箱报缺失或签名不支持时属于沙箱限制。保留原写法，以阅读端日志为准，禁止为让沙箱通过而改写规则。
>
> **每次 `eval_js` 都是全新作用域**：上一次调用里 `var` 声明的变量、对原型的改动都不会带到下一次；要跨调用存取走 `cache.put`/`cache.get`。报错信息若命中「方法/属性不存在」会自带【API 提示】指回本清单，按提示核对方法名与参数个数即可。
>
> **jsLib（书源级 JS 库）**：`debug_source` / `check_source` 会把 BookSource JSON 的 `jsLib` 字段装载进 Rhino 作用域，之后 searchUrl `{{}}`/`@js:`、bookList、目录、正文等所有规则 JS 都能直接调用其中声明的函数与顶层 `var`。隔离语义：jsLib 在独立的父层作用域求值——规则表达式里同名 `var`/赋值只遮蔽当次求值，下一次求值 jsLib 函数仍然干净；jsLib 顶层 `var` 每次求值重新初始化，不跨调用累积。
>
> **jsLib 顶层没有规则绑定。** 函数外不要使用 `java` / `source` / `cookie` / `cache` / `baseUrl` / `result`。函数被规则 JS 调用时，绑定依目标阅读分支：部分分支的 `this` 指向当次规则作用域，函数开头 `const {java, source, cookie, cache} = this` 可用；部分分支 `this` 不是规则作用域，应在规则侧把 `java`/`source` 等作为参数传入（`myFn(java, source, key)`）。沙箱里 `this` 解构能跑，不代表目标阅读端同样绑定；以阅读端日志为准，不要为迁就沙箱改绑定方式。
>
> **沙箱不加载外部 jsLib**：`jsLib` 值是 `http(s)://...` 时不会发起网络请求，只在 debug 报告的 `lines` 里留一条诊断；这是沙箱限制，不要仅为沙箱而把外链库内联，保留原写法并用阅读端实测。`eval_js` 不传 source，不会装载任何 jsLib。沙箱能执行 jsLib 不等于阅读端通过。

沙箱内 `java` 对象实际提供的方法（以 `RhinoEvaluator.kt` 的 `StudioJsApi` 为准）：

**网络：**
```js
java.ajax(url)                // 返回响应体字符串。阅读端第一参可解析 AnalyzeUrl 行内选项
                              // `https://example.com/api,{JSON}`（method/body/header/webView/webJs 与 searchUrl 同形）。
                              // 沙箱是否同解析以阅读端为准，不要为沙箱改写法。
java.connect(url)
java.connect(url, header)
java.connect(url, header, methodOrTimeout)   // 第3参 "GET"/"POST"/"HEAD" 视为请求方法，否则当 timeout
java.connect(url, header, method, body)
java.connect(url, header, method, body, charset)
java.get(url, headers?)       // 第一参是普通 URL，不要拼 `,JSON`；headers 为 Map 或 JSON 字符串
java.post(url, body, headers?) // 同上：URL / body / headers 分参，不要把选项塞进 URL
java.fetchFont(url)             // Studio 沙箱专有：仅放行字体响应（font/* Content-Type 或 .woff/.woff2/.ttf/.otf
                                // URL，且前 4 字节魔数为 wOF2/wOFF/TTF/OTTO），返回原始 ByteArray，其余一律报错
java.decodeWoff2(data)          // Studio 沙箱专有：解 WOFF2（内部 Brotli）/WOFF/TTF/OTTO 的 cmap；
                                // 入参 ByteArray 或 base64 字符串；返回 {"U+4E2D":1,...} 码点→glyphId。
                                // glyphId 是字形序号，不等于汉字。cmap 在 glyf 被变换时仍可能可读；
                                // gid→汉字不是只能轮廓比对，见下方「字体查询边界」。
java.queryTTF(data)             // 官方同名 API：解析字体为 QueryTTF（码点↔字形轮廓指纹映射）。
                                // 入参 ByteArray / base64 字符串 / http(s) URL（URL 走 fetchFont 同套抓取校验）。
                                // 支持 TTF/OTF/WOFF/WOFF2：woff/woff2 自动解包成 sfnt；
                                // 限制：woff2 的 glyf/loca 若带 transformed glyf（transformVersion≠3）
                                // 轮廓解析会报错（沙箱未实现逆变换）。这只说明本路径拿不到轮廓，
                                // **不等于**阅读端不可解，也不要求为此改书源或先转 TTF。
                                // 结果按字体字节 sha256 缓存（LRU 8 份），同字体重复调用不重解析。
                                // 返回对象方法：getGlyfByUnicode(码点) 取轮廓指纹、getGlyfIdByUnicode(码点)
                                // 取 glyphId、getUnicodeByGlyf(指纹) 反查码点、isBlankUnicode(码点)、
                                // 以及 unicodeToGlyph/glyphToUnicode/unicodeToGlyphId 三个映射表。
java.replaceFont(text, errFont, correctFont[, filter])
                                // 官方同名 API：通用字体反爬替换（字形轮廓比对，不依赖 GB2312 顺序表，
                                // 任意 cmap 打乱的字体都能用）。errFont=页面声明的错误字体（文本按它编码），
                                // correctFont=轮廓相同的正确字体；逐码点取 errFont 轮廓到 correctFont 反查汉字。
                                // filter=true 时删掉 errFont 里无轮廓的字符；空白字符恒保留；任一字体 null 返回原文。
                                // 典型用法（参照官方 ruleHelp）：先按字符把网页字体存成错误字体，再拿站点
                                // 原字体作正确字体：
                                //   var err = java.queryTTF(java.fetchFont("https://x/enc.woff2"));
                                //   var ok  = java.queryTTF("https://x/orig.ttf");
                                //   java.replaceFont(正文, err, ok, true)
                                // 轮廓比对只是路径之一。确认只是 GB2312 顺序 gid 映射时，可用
                                // decodeWoff2 拿码点→gid 再按 gid 表映射。
java.ajaxAll(urls)
java.webView(url)             // 单参形式：loadUrl 抓渲染后页面（仅当 java.webView 可调用）
java.webView(html, url, js)   // 三参形式：html 非空时以 url 为 baseURL 直接渲染交接文档（loadDataWithBaseURL），
                              // html 为 null/空白仍 loadUrl；url 必须是公网 http/https（拒绝 file/javascript/回环私网），
                              // js 返回 null 会按固定间隔轮询重试直到超时。页内异步解码把脚本放进 jsLib 函数返回，
                              // 不要把超长字面量写进规则；路径差异见 webjs.md。
                              // 部分阅读分支上 java.webView 是 object 不是函数（调用报「不是函数，它是 object」）。
                              // 跨分支优先 URL 选项 {"webView":true,"webJs":"..."}，不要默认三参调用。
                              // 沙箱可调用不代表目标阅读端可调用。
java.startBrowserAwait(url, title?, refetch?, html?) // 创建 webview 验证会话→用户在手机验证中心 WebView 过盾→沙箱 WebView 通道抓该 URL（html 非空作交接文档）返回整页源码 StrResponse；取消/超时返回空 body
java.getVerificationCode(imageUrl) // 单次受限 HTTP 拉真实图片（PNG/JPEG/GIF/WebP，最多 2MB）→手机验证中心看图输入（最长 120 秒）；取消/超时返回空串，拉图失败抛错
```

**字体查询边界（沙箱 `queryTTF` 失败 ≠ 阅读端不可解）：**

1. 先核对**未变换**的 `cmap` / `post` / `name` 表。`decodeWoff2` / `fetch_font` 读 cmap 时，glyf 被变换通常仍能给出码点→glyphId；这不等于汉字，但说明字体不是「完全不可解析」。
2. `queryTTF` 报 transformed glyf（`transformVersion≠3`）只说明**轮廓路径**在该环境拿不到 glyf 逆变换。禁止据此改书源、宣称正文不可解、或要求先转 TTF。
3. 轮廓比对（`queryTTF` + `replaceFont`）不是唯一途径。页内异步解码（站点自己的 JS/WASM）可通过 jsLib 函数**返回** webJs 字符串。跨分支优先写进 URL 选项 `{"webView":true,"webJs":...}`；仅当目标阅读端 `java.webView` 可调用时才用三参 `java.webView(html, url, js)`。不要把超长解码字面量写进规则。路径超时差异见 [`webjs.md`](webjs.md)：`@webjs:` 固定 10 秒；`--章节URL` 未带 `webView:true` 时 `contentRule.webJs` 不执行。
4. 沙箱不作规则标准。字体结论以阅读端 `--章节URL` / 网页正文实测为准；沙箱失败、阅读端已解，保留原写法并登记 `sandbox_divergent`。

图片验证码请求拒绝私网入口、DNS/重定向私网以及 HTML/CF 挑战响应，不写入图片日志。403/Cloudflare 时先用本手机验证中心在**原站页面**人工过盾再重试；图片本身不经 WebView，Cookie/通行态跨 HTTP/WebView 通道复用不保证成功。不要反复请求一次性图片 URL；详见 [`verification.md`](verification.md)。

**`java.ajax` 与 `java.get` / `java.post` 参数不是同一套：**

| 方法 | 第一参 | 其余参数 | 典型用途 |
|---|---|---|---|
| `java.ajax(urlStr)` | 可解析 AnalyzeUrl 行内选项：`https://example.com/api,{JSON}`（`method`/`body`/`header`/`webView`/`webJs` 等与 searchUrl 同形） | 可选 timeout | 规则里复用 searchUrl 那种 `url,{JSON}` 字符串，返回响应体 |
| `java.get(url, headers?, timeout?)` | **普通 URL**，不要拼 `,JSON` | headers 为 Map 或 JSON 字符串 | 只要 GET 与自定义头 |
| `java.post(url, body, headers?, timeout?)` | **普通 URL** | body、headers 分参传入 | POST 表单或 JSON 正文 |
| `java.connect(url, ...)` | 沙箱会解析 URL 选项；阅读端以目标版本为准 | 后续 header/method/body/charset | 需要状态码、响应头、重定向链 |

把 `"url," + JSON.stringify({method:"POST", body:...})` 传给 `java.get` / `java.post` 会把整段当 URL，请求失败。需要行内选项时用 `java.ajax` 或 `java.connect`。headers 传 `null`/`undefined` 在部分阅读分支会 NPE，空头传 `{}`。沙箱签名与阅读端不必相同，保留阅读端写法。

**工坊 POST 编码边界：** 普通 `searchUrl` 模板先对 `{{key}}` 作 UTF-8 表单编码，再解析 `,{...}` 请求选项，因此 body 内的 `{{key}}` 也会编码（空格为 `+`，字面加号为 `%2B`）。`@js:` 使用原始 `key` 变量；其返回选项的 body 与 `java.post` 的 body 按脚本构造值发送，不会自动再编码。需要百分号编码时显式调用 `java.encodeURI(key)`（空格为 `%20`）；JSON 或其他非表单正文应自行构造并设置对应 Content-Type。不要将这几条路径视为相同的自动编码入口，也不要把模板中已编码的值再次编码。

**响应对象方法（`java.connect`/`java.get`/`java.post`/`java.ajaxAll` 元素均返回此对象）：**
```js
code()          // 注意是 code()，不是 status()
statusCode()    // code() 的别名；推荐用官方 code()
url()           // 重定向后的最终 URL（finalUrl），非请求 URL
body()
headers()       // 响应头 Map（JS 里是 object），取单个头用下面的 header(name) 更稳
header(name)    // 单头查询，大小写不敏感，如 res.header('set-cookie')
bytes()         // 原始字节 ByteArray，GBK 等编码失真时用它重构
callTime()
raw()
request()
priorResponse() // 重定向链前置响应
```

**编码：**
```js
java.encodeURI(value, charset? = "UTF-8")
java.base64Encode(value)
java.base64Decode(value, charset? = "UTF-8")
java.base64DecodeToByteArray(value)
java.md5Encode(str) / java.md5Encode16(str)
java.sha1Encode(value)
java.sha256Encode(value)
java.hexEncodeToString(utf8) / java.hexDecodeToString(hex)
java.strToBytes(v) / java.strToBytes(v, charset)
java.bytesToStr(b) / java.bytesToStr(b, charset)   // 沙箱注册，但重建前语料仅 2/26861 在用、官方 App 未验证；字节转字符串优先 new Packages.java.lang.String(b, "UTF-8")
java.createSymmetricCrypto(transformation, key, iv?) // key/iv 为 UTF-8 String 或 ByteArray；见下文工坊对称加解密边界
// 返回对象：decrypt(bytes) / decryptStr(bytes或标准Base64字符串) / encrypt(bytes)
// encryptBase64(UTF-8字符串或bytes) / encryptHex(UTF-8字符串或bytes)
// encryptStr(str) 返回 ByteArray；encryptBase64Str(str) 是 encryptBase64(str) 别名；Pkcs7Padding 回退 PKCS5Padding
java.timeFormat(value) / java.timeFormat(value, format)
java.htmlFormat(str)
java.randomUUID()
java.getWebViewUA()
```

**Cookie：**
```js
java.getCookie(url) / java.getCookie(url, key)
java.setCookie(url, cookie)
```

**睡眠：**
```js
java.lang.Thread.sleep(ms)   // 仅此一个 java.lang 用法可用
```
> 注意：部分阅读分支没有 `java.lang`（真机报 `jl=undefined` / TypeError），以目标阅读端为准。沙箱的 `java.lang` 支持范围不能推断阅读端能力；不要仅为迁就沙箱改写，保留原写法并以 `reader_web_debug` 日志判断。

**全局同名对象：**
```js
// cookie
cookie.getCookie(url)
cookie.getKey(url, key)
cookie.setCookie(url, cookie)
cookie.replaceCookie(url, cookie)
cookie.removeCookie(url)
cookie.setWebCookie(url, cookie)

// cache
cache.put(key, value)
cache.get(key)
cache.delete(key)
cache.putMemory(key, value)
cache.getFromMemory(key)
cache.deleteMemory(key)

// source
source.getVariable / source.setVariable / source.putVariable
source.put(key, value)
source.get(key)

// book（官方 Book 属性在沙箱可读写；一次 debug 链路内同一实例，调试阶段会逐字段回填）
book.bookUrl / book.tocUrl / book.origin / book.originName
book.name / book.author / book.kind / book.coverUrl / book.intro
book.type / book.latestChapterTitle / book.totalChapterNum
book.durChapterTitle / book.durChapterIndex / book.canUpdate / book.variable
book.putVariable(key, value)   // value=null 删除该键；变量池一次 debug 内跨规则共享
book.getVariable(key?)         // 无参返回变量池 JSON 串
book.putCustomVariable(json) / book.getVariableMap() / book.setVariableMap(map)
book.upCustomIntro(v) / book.setReverseToc(b) / book.getUseReplaceRule() / book.setUseReplaceRule(b)
// 语义差异：eval_js 里 book 是空对象兜底（属性为空串/0，不报错）；
// debug_source 里 book.origin/originName/type 取书源头字段，
// 详情阶段 book.bookUrl=详情页地址且 name/author/kind/intro/coverUrl/tocUrl/latestChapterTitle 逐字段写回，
// 目录阶段 book.tocUrl=目录页地址，正文阶段 chapter.url=章节地址。
// 各阶段 book.putVariable 的写入跨规则求值共享（与 source 变量同理）。

// chapter（官方 BookChapter 属性；目录阶段逐条实例化 index/url/title/bookUrl/baseUrl，正文阶段 chapter.url=目标章节）
chapter.url / chapter.title / chapter.baseUrl / chapter.bookUrl / chapter.index
chapter.resourceUrl / chapter.tag / chapter.start / chapter.end / chapter.variable
chapter.putVariable(key, value) / chapter.getVariable(key?)
chapter.putLyric(v) / chapter.putImgUrl(v) / chapter.update()
```

**Studio 沙箱限制（不代表目标阅读端不可用）：**

- 沙箱中 `java.lang.*` 除 `Thread.sleep` 外不可用（如 `java.util.List`、`java.lang.System`）；字符串构造可用 `new Packages.java.lang.String(...)`。真实阅读端的可用性依目标分支，以阅读端日志为准。
- 沙箱未实现 `java.currentTimeMillis` / `java.decodeURI` / `java.utf8ToGbk` / `java.getElement` / `java.getElements`；不要据此推断目标阅读端也不可用，保留原写法并在阅读端实测。
- `java.bytesToStr` 是 Studio 沙箱专有，官方 App 未验证；需要跨环境时先以目标阅读端实测确认，不要仅为沙箱改写规则。
- 沙箱的 `org.jsoup.Jsoup.parse` 等 Java 包路径直调可能不可用（例如报「parse 不是函数，它是 object」）；沙箱注入了全局 `Jsoup` 代理对象。真实阅读端通常可用 `org.jsoup.Jsoup`；沙箱报错时属于沙箱限制，保留原写法并以阅读端日志为准。
- Rhino 里 java 方法返回的 `java.lang.String` 直接 `.replace(/正则/, ...)` 会报「选择不明确」，必须先 `String()` 包一层；域名等纯文本替换直接 `.replace("a","b")`
- `'+'` 拼接产生的是 ConsString，直接传给 java 方法当 `java.lang.String` 参数可能 `ClassCastException`，包 `String()` 兜底

> Jsoup 解析得到的 `Document`/`Element`/`Elements` 对象可直接调用其方法（`.select()`/`.text()`/`.attr()` 等）；旧版 App 中报「找不到函数」时请升级到 1.2.2 及以上。

> 更多沙箱实测坑点（searchUrl 返回值校验、save_source 转义等）见 `troubleshoot.md` 第 9 节起。

### RssJsExtensions 独有函数

> 在订阅源 `shouldOverrideUrlLoading` 规则中使用
> 被下方 `SourceLoginJsExtensions` 类包含，也能使用这些函数
> 订阅添加跳转 url 拦截, js, 返回 true 拦截, js 变量 url, 可以通过 js 打开 url
> url 跳转拦截规则不能执行耗时操作

**调用阅读搜索：**
```js
/**
 * @param key 搜索关键词
 * @param searchScope 搜索作用域，为空时调用所有书源搜索
 */
// searchScope 作用域，形式为 `源名称::源地址`、或者 `,` 符号隔开的源分组名称
// 在书源调用时可写为 java.searchBook(key, source)，仅本书源进行搜索
java.searchBook(key: String, searchScope: String? = null)
```

**添加书架：**
```js
java.addBook(bookUrl: String)
```

**打开源界面：**
```js
/**
 * @param name "sort"打开订阅源分类界面、"rss"打开订阅源正文界面、"explore"打开书源发现界面、"search"打开书籍搜索界面、"login"打开源登录界面
 * @param url "sort"时为分类链接、"rss"时为正文链接、"explore"时为发现链接，"search"/"login"时该参数无意义
 *        特别说明，"sort"时 url 可以传序列化后的键值对用来打开多个分类界面
 * @param title 对应界面的标题，"search"时为搜索关键词，"login"时该参数无意义
 * @param origin 打开指定源界面的源地址
 */
java.open(name: String, url: String? = null, title: String? = null, origin: String? = null)
```

**展示图片：**
```js
java.showPhoto(src: String)
```

### SourceLoginJsExtensions 独有函数

> 只在 `登录界面按钮` 被触发、`界面按钮的回调` 事件、`发现按钮` 函数、`图片链接 click 键`、`购买规则` 中有效

```js
// 用内置浏览器打开本地 html
java.showBrowser(url: String, html: String, preloadJs: String? = null, config: String? = null)

// 复制文本到剪贴板
java.copyText(text: String)

// 实时更新登录界面用户信息，upLoginData(null) 会全部重置为默认值
java.upLoginData(data: Map<String, String?>?)

// 刷新登录界面
java.reLoginView(deltaUp: Boolean = false)

// 刷新书籍详情页
java.refreshBookInfo()

// 刷新书籍目录页
java.refreshBookToc()

// 刷新书籍正文内容
java.refreshContent()

// 清除 TTS 源的缓存，仅限 TTS 源的登录界面
java.clearTtsCache()

// 刷新发现，仅限发现按钮
java.refreshExplore()
```

[showBrowser 函数介绍](https://github.com/Luoyacheng/legado/wiki/java.showBrowser%E5%87%BD%E6%95%B0%E4%BB%8B%E7%BB%8D)

### AnalyzeUrl 部分函数

> js 中通过 java.调用，只在 `登录检查 JS` 规则中有效

```js
initUrl() // 重新解析 url，可以用于登录检测 js 登录后重新解析 url 重新访问
getHeaderMap().putAll(source.getHeaderMap(true)) // 重新设置登录头
getStrResponse(jsStr: String? = null, sourceRegex: String? = null) // 返回访问结果，文本类型
getResponse(): Response // 返回访问结果，网络朗读引擎采用此方法
```

### AnalyzeRule 部分函数

**获取文本/文本列表：**
```js
// mContent 待解析源代码，默认为当前页面
// isUrl 链接标识，默认为 false
java.getString(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false)
java.getStringList(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false)
```

**设置解析内容：**
```js
java.setContent(content: Any?, baseUrl: String? = null)
```

**获取 Element/Element 列表：**
> 官方 App 的 `AnalyzeRule` 有 `getElement/getElements`，但 **Studio 沙箱未实现，沙箱调试期调用会报错**。若只需在沙箱取证，可用 CSS 选择器 + `java.getString('div.x@text', html)` / `java.getStringList`；不要仅为沙箱而改写规则，原写法以目标阅读端实测为准。
```js
java.getElement(ruleStr: String)
java.getElements(ruleStr: String)
```

**重新搜索书籍/重新获取目录 url：**
> 只能在刷新目录之前使用，有些书源书籍地址和目录 url 会变
```js
java.reGetBook()
java.refreshTocUrl()
```

**变量存取：**
```js
java.get(key)
java.put(key, value)
```

### JS 扩展类 部分函数

**链接解析：**
```js
java.toURL(url: String, baseUrl: String? = null): JsURL
```

**获取 SystemWebView User-Agent：**
```js
java.getWebViewUA(): String
```

**网络请求：**
```js
// 普通请求。urlStr 可带 AnalyzeUrl 行内选项：`https://example.com/api,{JSON}`
java.ajax(urlStr, callTimeout: Int? = null): String

// 并发访问网络，skipRateLimit 为 true 时不受源并发率限制
java.ajaxAll(urlList: Array<String>, skipRateLimit: Boolean = false): Array<StrResponse>

// ajaxTestAll 会忽略网络访问错误，错误类型由 callTime() 获取
// 错误码值：-1 超过设定时间, -2 超时, -3 域名错误, -4 连接被拒绝, -5 连接被重置, -6 SSL证书错误, -7 其它错误
// 无错误时 callTime() 为响应时间
java.ajaxTestAll(urlList: Array<String>, timeout: Int, skipRateLimit: Boolean = false): Array<StrResponse>

// 连接请求，返回 StrResponse 对象具有方法：body() code() message() headers() raw() toString() callTime()
java.connect(urlStr, header = null, callTimeout: Int? = null): StrResponse

// POST / GET / HEAD：url 是普通地址，不要拼 `,JSON`；body/headers/timeout 走后续参数
java.post(url: String, body: String, headerMap: Map<String, String>, timeout: Int? = null): Connection.Response
java.get(url: String, headerMap: Map<String, String>, timeout: Int? = null): Connection.Response
java.head(url: String, headerMap: Map<String, String>, timeout: Int? = null): Connection.Response

// 使用 webView 访问网络（html 非空时以 url 为 baseURL 内嵌渲染交接文档，等价官方 loadDataWithBaseURL 路径）
// 部分阅读分支上 java.webView 是 object 不是函数；跨分支优先 URL {"webView":true,"webJs":"..."}
java.webView(html: String?, url: String?, js: String?, cacheFirst: Boolean = false): String?

// 使用 webView 获取跳转 url
java.webViewGetOverrideUrl(html: String?, url: String?, js: String?, overrideUrlRegex: String, cacheFirst: Boolean = false, delayTime: Long = 0): String?

// 使用内置浏览器打开链接，可用于获取验证码、手动验证网站防爬
java.startBrowser(url: String, title: String, html: String? = null)

// 使用内置浏览器打开链接并等待网页结果
java.startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean = false, html: String? = null): StrResponse
```

**调试：**
```js
java.log(msg)
java.logType(var)
```

**获取用户输入的验证码：**
```js
java.getVerificationCode(imageUrl)
```

**弹窗提示：**
```js
java.longToast(msg: Any?)
java.toast(msg: Any?)
```

**获取用户阅读配置：**
```js
java.getReadBookConfig(): String
java.getReadBookConfigMap(): Map<String, Any>
```

**获取用户主题配置：**
```js
java.getThemeConfig(): String
java.getThemeConfigMap(): Map<String, Any?>
```

**获取用户主题模式：**
```js
// @return 0 跟随系统，1 亮色主题，2 暗色主题，3 墨水屏
fun getThemeMode(): String
```

**从网络/本地读取 JavaScript 文件：**
```js
java.importScript(url)
// 相对路径支持 android/data/{package}/cache
java.importScript(relativePath)
java.importScript(absolutePath)
```

**缓存网络文件：**
```js
// 获取
java.cacheFile(url)
java.cacheFile(url, saveTime)
// 执行内容
eval(String(java.cacheFile(url)))
// 使缓存失效
cache.delete(java.md5Encode16(url))
```

**获取网络压缩文件里面指定路径的数据（按压缩格式选对应方法，无泛名 getStringContent）：**
```js
java.getZipStringContent(url: String, path: String): String
java.getZipStringContent(url: String, path: String, charsetName: String): String
java.getZipByteArrayContent(url: String, path: String): ByteArray?
java.getRarStringContent(url: String, path: String): String        // Rar 同理
java.get7zStringContent(url: String, path: String): String         // 7z 同理
```

**URI 编码：**
```js
java.encodeURI(str: String, enc: String = "UTF-8")
```

**Base64：**
> flags 参数可省略，默认 Base64.NO_WRAP，查看 [flags 参数说明](https://blog.csdn.net/zcmain/article/details/97051870)
```js
java.base64Decode(str: String)
java.base64Decode(str: String, charset: String)
java.base64DecodeToByteArray(str: String, flags: Int)
java.base64Encode(str: String, flags: Int)
```

**ByteArray：**
```js
// Str 转 Bytes
java.strToBytes(str: String)
java.strToBytes(str: String, charset: String)
// Bytes 转 Str（沙箱注册但重建前语料仅 2/26861 在用；跨环境书源一律用
// new Packages.java.lang.String(bytes, "UTF-8")）
java.bytesToStr(bytes: ByteArray)
java.bytesToStr(bytes: ByteArray, charset: String)
```

**工坊对称加解密：**
```js
// transformation 示例：AES/CBC/PKCS5Padding、AES/ECB/Pkcs7Padding（Pkcs7 回退 PKCS5Padding）
// String key/iv 按 UTF-8 取字节；ByteArray 原样使用，不自动识别 Hex 或 Base64
java.createSymmetricCrypto(transformation: String, key: Any, iv: Any? = null)
cipher.decrypt(bytes)         // 原始密文字节 → ByteArray
cipher.decryptStr(bytes)      // 原始密文字节 → UTF-8 String
cipher.decryptStr(base64)     // 严格标准 Base64 密文 → UTF-8 String
cipher.decryptBase64Str(base64) // decryptStr(base64) 别名
cipher.encrypt(bytes)         // 原始明文字节 → ByteArray
cipher.encryptStr(str)        // UTF-8 明文 → ByteArray
cipher.encryptBase64(strOrBytes) // UTF-8 明文或原始字节 → 标准 Base64
cipher.encryptHex(strOrBytes) // UTF-8 明文或原始字节 → 小写 Hex
cipher.encryptBase64Str(str)  // encryptBase64(str) 别名
```

`decryptStr(String)` 不自动识别 Hex、URL-safe Base64 或其他编码。Hex 密文应先经 `java.hexDecodeToByteArray` 转成字节，再调用 `decrypt` 或 `decryptStr`。未提供 `InputStream`、字符集参数及任意额外参数的通用兼容重载。算法和模式由运行平台的密码提供程序决定，不保证与所有原版阅读版本完全一致。

> **重要：`decryptStr` 等 Java 方法返回的是 `java.lang.String`，不是 JS 原生字符串。**
> 对其直接调 JS 的 `.replace(/正则/, ...)`、`.match`、`.split` 等方法会报
> `Java 方法 java.lang.String.xxx 的选择不明确`（Rhino 无法在 object/string 重载间抉择）。
> **必须先 `String(...)` 包一层转成 JS 字符串**。完整可抄示例：
>
> ```js
> // AES 解密正文（CBC + PKCS5，key/iv 为 UTF-8 串；Base64 密文）
> var cipher = java.createSymmetricCrypto("AES/CBC/PKCS5Padding", "1234567890abcdef", "abcdef1234567890");
> var plain = String(cipher.decryptStr(base64CipherText));   // ← String() 包装是必需的
> plain = plain.replace(/<[^>]+>/g, "");                      // 现在能安全调 JS 正则/字符串方法
> plain;
> ```

**Hex：**
```js
// HexString 解码为字节数组
java.hexDecodeToByteArray(hex: String)
// hexString 解码为 utf8String
java.hexDecodeToString(hex: String)
// utf8 编码为 hexString
java.hexEncodeToString(utf8: String)
```

**标识 id：**
```js
java.randomUUID()
java.androidId()
```

**繁简转换：**
```js
java.t2s(text: String): String  // 转简体
java.s2t(text: String): String  // 转繁体
```

**时间格式化：**
```js
java.timeFormatUTC(time: Long, format: String, sh: Int): String?
java.timeFormat(time: Long): String
```

**HTML 格式化：**
```js
java.htmlFormat(str: String): String
```

**文件操作：**
> 所有对于文件的读写删操作都是相对路径，只能操作阅读缓存 `android/data/{package}/cache/` 内的文件
```js
// 文件下载，url 用于生成文件名，返回文件路径
downloadFile(url: String): String
// 文件解压，zipPath 为压缩文件路径，返回解压路径
unArchiveFile(zipPath: String): String
unzipFile(zipPath: String): String
unrarFile(zipPath: String): String
un7zFile(zipPath: String): String
// 文件夹内所有文件读取
getTxtInFolder(unzipPath: String): String
// 读取文本文件
readTxtFile(path: String): String
// 删除文件
deleteFile(path: String)
```

### JS 加解密类（原版阅读参考）

> 以下保留原版阅读的 API 参考，不是工坊的完整实现清单。工坊使用 JCE 桥接，未引入 Hutool；实际支持的输入类型、输出和方法以上文「工坊对称加解密」为准。原版阅读的具体行为还取决于目标版本。
>
> 原版参考使用 [hutool-crypto](https://www.hutool.cn/docs/#/crypto/%E6%A6%82%E8%BF%B0) 提供 crypto 算法接口。
> 本参考记录的 hutool-crypto 版本为 5.8.22。
> 注意：如果输入的参数不是 Utf8String，可先调用 `java.hexDecodeToByteArray` / `java.base64DecodeToByteArray` 转成 ByteArray

**对称加密：**
```js
// 创建 Cipher (key, iv 支持 ByteArray|Utf8String)
java.createSymmetricCrypto(transformation, key, iv)

// 解密加密 (data 支持 ByteArray|Base64String|HexString|InputStream)
cipher.decrypt(data)        // 解密为 ByteArray
cipher.decryptStr(data)     // 解密为 String
cipher.encrypt(data)        // 加密为 ByteArray
cipher.encryptBase64(data)  // 加密为 Base64 字符
cipher.encryptHex(data)     // 加密为 HEX 字符
```

**非对称加密：**
```js
// 创建 Cipher (key 支持 ByteArray|Utf8String)
java.createAsymmetricCrypto(transformation)
  .setPublicKey(key)
  .setPrivateKey(key)

// 解密加密 (data 支持 ByteArray|Base64String|HexString|InputStream)
cipher.decrypt(data, usePublicKey: Boolean? = true)
cipher.decryptStr(data, usePublicKey: Boolean? = true)
cipher.encrypt(data, usePublicKey: Boolean? = true)
cipher.encryptBase64(data, usePublicKey: Boolean? = true)
cipher.encryptHex(data, usePublicKey: Boolean? = true)
```

**签名：**
```js
// 创建 Sign (key 支持 ByteArray|Utf8String)
java.createSign(algorithm)
  .setPublicKey(key)
  .setPrivateKey(key)

// 签名 (data 支持 ByteArray|InputStream|String)
sign.sign(data)      // 签名输出 ByteArray
sign.signHex(data)   // 签名输出 HexString
```

**摘要：**
```js
java.digestHex(data: String, algorithm: String): String?
java.digestBase64Str(data: String, algorithm: String): String?
```

**MD5：**
```js
java.md5Encode(str: String)
java.md5Encode16(str: String)
```

**HMac：**
```js
java.HMacHex(data: String, algorithm: String, key: String): String
java.HMacBase64(data: String, algorithm: String, key: String): String
```

**跳转外部链接/应用：**
```js
// 跳转外部链接，传入 http 链接或者 scheme 跳转到浏览器或其他应用
// 指定 mimeType，可以跳转指定类型应用，例如（video/*）
java.openUrl(url: String, mimeType: String = null)
```

**视频播放器函数：**
```js
/**
 * @param url 视频播放链接
 * @param title 视频的标题
 * @param isFloat 是否悬浮窗打开
 */
java.openVideoPlayer(url: String, title: String, isFloat: Boolean = false)
```

## book 对象

### 属性

> 使用方法：在 js 中或 `{{}}` 中使用 book.属性的方式即可获取。如在正文内容后加上 `##{{book.name+"正文卷"+title}}` 可以净化 书名+正文卷+章节名称（如 我是大明星正文卷第二章我爸是豪门总裁）这一类的字符。

```js
bookUrl              // 详情页 Url（本地书源存储完整文件路径）
tocUrl               // 目录页 Url（toc=table of Contents）
origin               // 书源 URL（默认 BookType.local）
originName           // 书源名称 or 本地书籍文件名
name                 // 书籍名称（书源获取）
author               // 作者名称（书源获取）
kind                 // 分类信息（书源获取）
customTag            // 分类信息（用户修改）
coverUrl             // 封面 Url（书源获取）
customCoverUrl       // 封面 Url（用户修改）
intro                // 简介内容（书源获取）
customIntro          // 简介内容（用户修改）
charset              // 自定义字符集名称（仅适用于本地书籍）
type                 // 0:text 1:audio
group                // 自定义分组索引号
latestChapterTitle   // 最新章节标题
latestChapterTime    // 最新章节标题更新时间
lastCheckTime        // 最近一次更新书籍信息的时间
lastCheckCount       // 最近一次发现新章节的数量
totalChapterNum      // 书籍目录总数
durChapterTitle      // 当前章节名称
durChapterIndex      // 当前章节索引
durChapterPos        // 当前阅读的进度（首行字符的索引位置）
durChapterTime       // 最近一次阅读书籍的时间（打开正文的时间）
canUpdate            // 刷新书架时更新书籍信息
order                // 手动排序
originOrder          // 书源排序
variable             // 自定义书籍变量信息（用于书源规则检索书籍信息）
```

### 函数

**自定义书籍变量存取：**
```js
book.putVariable(key: String, variable: String?)
book.getVariable(key: String): String?
```

## chapter 对象

### 属性

> 使用方法：在 js 中或 `{{}}` 中使用 chapter.属性的方式即可获取。如在正文内容后加上 `##{{chapter.title+chapter.index}}` 可以净化 章节标题+序号（如 第二章 天仙下凡2）这一类的字符。

```js
url          // 章节地址
title        // 章节标题
baseUrl      // 用来拼接相对 url
bookUrl      // 书籍地址
index        // 章节序号
resourceUrl  // 音频真实 URL
tag          //
start        // 章节起始位置
end          // 章节终止位置
variable     // 变量
```

### 函数

**自定义章节变量存取：**
```js
chapter.putVariable(key: String, variable: String?)
chapter.getVariable(key: String): String?
// 在函数回调或登录界面等地方调用，chapter 自身不会进行保存，需要调用 chapter.update()
```

**章节信息存储：**
```js
chapter.putLyric(value: String?)   // 存储音频章节歌词
chapter.putImgUrl(value: String?)  // 存储章节图标链接，比如标题上的段评图标链接
```

## source 对象

**获取书源 url：**
```js
source.getKey()
```

**书源变量存取：**
```js
source.putVariable(variable: String?)
source.getVariable()
```

**自定义书源变量存取：**
```js
source.put(key: String, variable: String?)
source.get(key: String): String?
```

**登录头操作：**
```js
source.getLoginHeader()                   // 获取登录头
source.getLoginHeaderMap().get(key: String) // 获取登录头某一键值
source.putLoginHeader(header: String)     // 保存登录头
source.removeLoginHeader()                // 清除登录头
```

**用户登录信息操作：**
> 使用 `登录UI` 规则，并成功登录，阅读自动加密保存登录UI规则中除 type 为 button 的信息
```js
source.getLoginInfo()                     // login 函数获取登录信息
source.getLoginInfoMap().get(key: String) // login 函数获取登录信息键值
source.removeLoginInfo()                  // 清除登录信息
source.putLoginInfo()                     // login 函数存放登录信息（在登录界面时请调用 java.upLoginData）
```

**书源缓存刷新：**
```js
source.refreshExplore()  // 刷新发现
source.refreshJSLib()    // 刷新 jslib
```

## cookie 对象

```js
cookie.getCookie(url: String)           // 获取全部 cookie
cookie.getKey(url: String, key: String)  // 获取 cookie 某一键值
cookie.setCookie(url: String, cookie: String)     // 设置 cookie
cookie.replaceCookie(url: String, cookie: String)  // 替换 cookie
cookie.removeCookie(url: String)         // 删除 cookie
cookie.setWebCookie(url: String, cookie: String)  // 设置内置浏览器 cookie
```

## cache 对象

> saveTime 单位：秒，可省略
> 保存至数据库和缓存文件（50M），保存的内容较大时请使用 `getFile` / `putFile`

```js
cache.put(key: String, value: String, saveTime: Int = 0)  // 保存，saveTime 为 0 时无过期时间
cache.get(key: String, onlyDisk: Boolean = false): String? // 读取数据库，onlyDisk 为 true 时只从磁盘读取
cache.delete(key: String)                   // 删除
cache.putFile(key: String, value: String, saveTime: Int)  // 缓存文件内容
cache.getFile(key: String): String?          // 读取文件内容
cache.putMemory(key: String, value: Any)     // 保存到内存
cache.getFromMemory(key: String): Any?       // 读取内存
cache.deleteMemory(key: String)              // 删除内存
```

## URL 选项参考

URL 末尾以 `,{JSON}` 追加行内选项，格式如 `https://example.com/page,{"webView":true}`。这是跨阅读分支最稳妥的 WebView 路径：部分分支上 `java.webView` 不可调用，但 URL 选项 `webView` + `webJs` 仍走阅读自己的页面加载。`java.ajax` 的第一参也可带同一套选项；`java.get` / `java.post` 不行。

| 选项 | 类型 | 说明 |
|------|------|------|
| `webView` | bool | `true` 时使用 WebView 加载页面 |
| `webJs` | string | WebView 加载完成后执行的 JS |
| `webViewDelayTime` | number | 页面加载后等待延迟（毫秒） |
| `method` | string | 请求方法：`POST` / `GET` / `HEAD` |
| `charset` | string | 编码，如 `utf-8`、`gbk` |
| `retry` | number | 重试次数 |
| `header` | object | 自定义请求头，如 `{"User-Agent":"..."}` |
| `headers` | object / JSON 字符串 | 同上（**不支持数组格式**，数组会静默解析失败→不加头） |
| `body` | string | POST 请求体 |
| `js` | string | 访问前执行 JS，结果替换 URL |
| `bodyJs` | string | 获取响应后执行 JS，结果替换 body |
| `dnsIp` | string | 自定义 DNS 解析 IP |
| `type` | string | 资源类型标识 |
| `serverID` | number | 服务器 ID |

**js 参数示例**（访问前处理 URL）：

```
https://www.baidu.com,{"js":"java.headerMap.put('xxx', 'yyy')"}   // headerMap 仅官方 App 可用，Studio 沙箱不可用
https://www.baidu.com,{"js":"java.url+'yyyy'"}                    // 表达式返回式 URL 处理，无赋值写法
```

**bodyJs 参数示例**（响应后处理）：

```
https://www.baidu.com,{"bodyJs":"if(result)'这里的文本作为访问返回的响应体body'else result"}
```

**dnsIp 参数示例**（强制指定 IP）：

```
https://dns.google,{"dnsIp":"8.8.8.8"}
```
