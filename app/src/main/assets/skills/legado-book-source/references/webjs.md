# webJs 参考

> `webJs` 是在 **WebView** 中执行的 JavaScript，与通过 `@js:` `<js>` `{{}}` 调用的 Rhino JS 是两套独立环境。

## 与 Rhino JS 的区别

| | webJs（WebView） | Rhino JS（书源规则） |
|---|---|---|
| **执行环境** | Android WebView（完整浏览器引擎） | Mozilla Rhino（纯 JS 引擎） |
| **访问 DOM** | ✅ `document.querySelector` `$('#id')` | ❌ 无 `window`/`document`/`$` |
| **调用网站函数** | ✅ `getDecode(); doDecrypt();` | ❌ 无函数引用 |
| **使用页面变量** | ✅ `window.x` `globalConfig` | ❌ |
| **await/Promise** | 阅读端 webJs：async 立即得到 `null`，靠返回 `null` 轮询；**不保证**等待 Promise resolve | ❌ 不支持 async/await（ES6 模式可用 let/const/箭头函数）；等待用同步写法或 webJs 返回 `null` 轮询 |
| **调用 Java bridge** | ✅ `ajaxAwait()` `webViewAwait()`（仅 `@webjs:`） | ✅ `java.ajax()`；`java.webView()` 部分分支是 object 不是函数 |
| **jQuery/React/Vue** | ✅ 直接使用页面已加载的 | ❌ |

## 启用 webJs 的三种方式

跨阅读分支**优先方式一**。部分阅读分支上 `java.webView` 是 object 不是函数，三参调用会失败；URL 选项 `webView` + `webJs` 走阅读自己的页面加载，不依赖该方法是否可调用。

### 方式一：URL 选项（推荐）

在书源 URL 后附加 JSON 选项：

```
https://example.com/chapter/123,{"webView":true,"webJs":"document.querySelector('.content').innerHTML"}
```

搜索、详情、目录、正文的入口 URL 都可以这样写。需要 jsLib 生成脚本时，让函数**返回** webJs 字符串，再拼进该 JSON 的 `webJs` 字段。

### 方式二：ContentRule.webJs 字段

**前提**：章节 URL 必须设 `webView:true`，否则 webJs 不会在 WebView 中执行。

```json
"ruleContent": {
    "content": "div.content",
    "webJs": "document.querySelector('div.content').innerHTML"
}
```

### 方式三：`@webjs:` 模式

**无需** URL 设 `webView:true`，`@webjs:` 会自行创建后台 WebView 执行。

```
getDecode()@webjs:document.querySelector('.content').innerHTML
```

`getDecode()` 在 Rhino 环境执行，`@webjs:` 之后在 WebView 环境执行。

> 超时与调用路径有关：**`@webjs:` 规则段固定 10 秒**；URL 选项 `{"webView":true,"webJs":...}` 与 `contentRule.webJs` 路径走 `BackstageWebView`，默认 60 秒。MCP 调试器（WebViewPageLoader）是另一套独立实现（固定 300ms 轮询、总超时 60s、无退避），重试参数表现与手机端不同，勿互相外推。它不用「同步 XHR 泵」——webJs 返回 `null` 视为未就绪、到点重跑脚本，由总超时兜底，异步就绪写法与方式五「懒加载轮询」一致。工坊 `webview_eval` 支持等待 Promise 的设计实现，须在目标 WebView 环境实测；阅读书源 webJs 不应依赖 Promise。探测脚本仍可返回 `null`（未就绪）或最终字符串。

### URL 选项补充字段

| 字段 | 说明 |
|------|------|
| `webView` | 启用 WebView 加载（方式一和二必需） |
| `webJs` | WebView 加载完成后执行的 JS |
| `bodyJs` | 得到响应结果后执行的 JS，对结果二次处理 |
| `webViewDelayTime` | WebView 等待页面加载完毕的延迟时间（毫秒） |

## webJs 写法

### 1. JS 表达式（返回值即为结果）

```javascript
document.documentElement.outerHTML
```

结果就是页面完整 HTML。

### 2. 调用网站函数

```javascript
getDecode();$('#content').html()
```

`getDecode()` 是网站定义的解密函数，最终表达式的值作为结果返回。

### 3. 完整 JS 逻辑

```javascript
(function(){
    let content = document.querySelector('#article');
    let title = document.querySelector('h1').innerText;
    return title + '\n' + content.innerHTML;
})()
```

### 4. 异步操作（scroll / 返回 null 轮询）

```javascript
(function(){
    if (!window.__legado_scrolled) {
        window.scrollTo(0, document.body.scrollHeight);
        window.__legado_scrolled = true;
        return null;
    }
    var el = document.querySelector('#content');
    if (!el) return null;
    return el.innerHTML;
})()
```

> **原理**：`evaluateJavascript` **不会**等待 Promise resolve。async IIFE 执行时往往立即得到 `null`，阅读端靠「返回 `null` → 间隔后重跑」轮询直到非 `null` 或超时。不要把「返回 Promise」写成阅读书源 webJs 已支持的契约。工坊 `webview_eval` 支持等待 Promise 的设计实现，须在目标 WebView 环境实测；阅读书源 webJs 不应依赖 Promise。未就绪请显式 `return null`，就绪请返回字符串。

### 5. 懒加载轮询：null 等到超时，空结果要返回空容器

`webJs` 返回 `null` 时系统会重试直到超时；返回字符串时结束。可用此特性等待动态内容就绪。

**0 条结果不是超时。** 列表容器已经渲染、或页面已是明确空态（空列表、「暂无数据」一类提示）时，应返回空容器 HTML，让规则解析到 0 条。容器还没出现、仍在 loading 时才继续 `return null`。把已空的页面一直 `return null` 会拖到超时，看起来像加载失败。

```javascript
(() => {
  const selector = '.read-content';
  const rejectPattern = /加载中|loading|请稍候/i;
  const scrollToBottom = true;

  if (scrollToBottom && !window.__legado_scrolled) {
    window.scrollTo(0, document.body.scrollHeight);
    window.__legado_scrolled = true;
    return null;
  }

  const el = document.querySelector(selector);
  if (!el) {
    const emptyHint = document.body && /暂无|没有找到|无结果|没有相关/.test(document.body.innerText);
    if (emptyHint) return document.documentElement.outerHTML;
    return null;
  }

  if (rejectPattern && rejectPattern.test(el.outerHTML)) return null;

  return document.documentElement.outerHTML;
})();
```

| 页面状态 | 返回 | 结果 |
|---|---|---|
| 目标节点不存在，且仍在 loading | `null` | 继续轮询 |
| 目标节点已在 DOM（哪怕 0 个子项） | 空容器或整页 HTML | 结束轮询，规则得到 0 条或空正文 |
| 明确空态文案已出现 | 含该空态的 HTML | 结束轮询，按 0 结果处理 |
| 一直 `null` 到超时 | （无字符串） | 该次 WebView 失败，不是「搜索 0 条」 |

## Bridge API（异步，返回 Promise）

由 `WebJsExtensions` 注入，**仅在规则段 `@webjs:` 路径可用**（URL 选项 webJs / `ruleContent.webJs` 路径 `isRule=false`，整个 Bridge 表不注入，写了会 ReferenceError）：

> **以上均为官方 App 行为。Studio 沙箱（MCP 调试器 `WebViewPageLoader`）不注入任何 Bridge**——`run`/`ajaxAwait`/`webViewAwait` 等一概不存在，webJs 里调用一律 ReferenceError → `__STUDIO_ERROR__` 或 undefined 轮询到超时。

| 函数 | 说明 |
|------|------|
| `run(jsCode)` | 回调到 Rhino 执行 JS |
| `ajaxAwait(url, timeout)` | 异步网络请求 |
| `connectAwait(url, header, timeout)` | 建立连接 |
| `getAwait(url, header, timeout)` | GET 请求 |
| `headAwait(url, header, timeout)` | HEAD 请求 |
| `postAwait(url, body, header, timeout)` | POST 请求 |
| `webViewAwait(html, url, js, cacheFirst)` | 递归启动新的 WebView |
| `webViewGetSourceAwait(html, url, js, regex, cacheFirst, delay)` | 获取资源 URL |
| `decryptStrAwait(transformation, key, iv, data)` | 解密 |
| `encryptBase64Await(transformation, key, iv, data)` | 加密（Base64 输出） |
| `encryptHexAwait(transformation, key, iv, data)` | 加密（Hex 输出） |
| `createSignHexAwait(algorithm, publicKey, privateKey, data)` | 签名（Hex 输出） |
| `downloadFileAwait(url)` | 下载文件 |
| `readTxtFileAwait(path)` | 读取文本文件 |
| `importScriptAwait(url)` | 导入外部 JS 脚本 |
| `getStringAwait(rule, content)` | 用解析规则提取文本 |

Bridge API 的 Promise 通过独立回调通道 resolve，配合重试机制实现异步等待。

## 同步方法（非 bridge，不返回 Promise）

webJs 环境中可直接调用（来自注入的 `WebJsExtensions` 对象，代码里习惯写作 `java`；同样**仅 `@webjs:` 路径可用**，且同 Bridge 一样**是官方 App 行为——Studio 沙箱的 WebView 不注入同步 `java` 对象**，调试期调用必报错）：

| 方法 | 签名 |
|------|------|
| `getString(rule, content, isUrl)` | 用解析规则提取文本 |
| `getStringList(rule, content, isUrl)` | 用解析规则提取文本列表 |
| `ajax(url, callTimeout)` | 同步网络请求 |
| `connect(url, header, callTimeout)` | 同步连接 |
| `get(url, header, timeout)` | 同步 GET 请求 |
| `post(url, body, header, timeout)` | 同步 POST 请求 |
| `log(msg)` | 输出日志 |
| `toast(msg)` / `longToast(msg)` | 弹出 Toast |

## 使用场景

| 场景 | 示例 |
|------|------|
| **网站内容加密/混淆** | `webJs: "getDecode();$('#content').html()"` |
| **需等待 JS 渲染** | URL 配 `webView:true, webViewDelayTime:3000` |
| **动态加载的内容** | webJs 中 scroll + await 等待加载 |
| **SPA 需调 API 补全** | webJs 中调用页面暴露的 `fetch`/`axios` |
| **绕过无限滚动** | scroll 循环 + 最终提取 |

## 页内异步解码（优先 URL `webView` + `webJs`）

站点在浏览器里异步解码（自定义字体、页内函数、WASM）时，**不要**把超长解码脚本写成规则字面量。把可复用脚本放进书源 `jsLib` 函数，让函数**返回**那段 webJs 字符串。

跨分支优先拼进 URL 选项，不要默认调用 `java.webView`（部分阅读分支上它是 object 不是函数）：

```js
// jsLib：只返回字符串，顶层不要碰 java/source
function pageDecodeJs() {
  return "(function(){ var el=document.querySelector('.content'); if(!el) return null; return el.innerHTML; })()";
}
// 规则 JS（Rhino）：把返回的脚本写进 URL 选项
baseUrl + "," + JSON.stringify({webView:true, webJs: pageDecodeJs()})
```

仅当阅读端日志证明 `typeof java.webView === 'function'` 时，才用三参 `java.webView(html, url, pageDecodeJs())`。`js` 返回 `null` 会按该调用的轮询重试，写法与上文「懒加载轮询」一致：已渲染的空容器返回空 HTML，未加载才 `null`。沙箱不加载外链 `jsLib`（`http(s)://...`），只在 debug `lines` 留诊断；不要仅为沙箱把库内联，保留原写法并用阅读端实测。jsLib 函数内取 `java`/`source` 须 `this` 解构或由规则传参，见 [`js-api.md`](js-api.md)。

### 路径差异（不要互相外推）

| 路径 | 何时进 WebView | 超时 |
|---|---|---|
| `@webjs:` 规则段 | 自行建后台 WebView，**不要求** URL 带 `webView:true` | 固定 **10 秒** |
| URL 选项 / `contentRule.webJs` | 章节或页面 URL 必须 `webView:true`，否则 webJs **不执行** | 默认 **60 秒** |
| `java.webView(html, url, js)` 三参 | 仅当该方法可调用；部分分支是 object | 按该调用的轮询/超时 |
| `reader_web_debug` 的 `--章节URL` | 只按该章节 URL 的选项走；未带 `webView:true` 时方式二的 `contentRule.webJs` 不会跑 | 阅读端该次调试超时 |
| MCP 调试器 `WebViewPageLoader` | 独立实现，固定 300ms 轮询、总超时 60s、不注入 Bridge | 与手机端不同，勿外推 |

沙箱 `@webjs:` 10 秒超时、MCP WebView 轮询失败、或 `--章节URL` 因未带 `webView:true` 而没跑 webJs，都不能写成「阅读端不可解」。沙箱 `java.webView` 可调用也不等于阅读端可调用。以阅读端 `--` / 网页正文实测为准。沙箱不作规则标准。

## 注意事项

- 超时：`@webjs:` 规则段 **10 秒**；URL 选项与 `ruleContent.webJs` 路径默认 **60 秒**（MCP 调试器为独立实现，参数不同）
- 返回 `null` 会轮询直到超时。已渲染的空列表 / 明确空态必须返回空容器 HTML，不能一直 `null`
- 返回空或 `null` 时重试最多 30 次（间隔：200ms → 400ms → 600ms → 800ms → 1000ms，之后恒定 1000ms）。重试时 JS 重新执行，不适用于有副作用的操作
- 工坊 `webview_eval` 支持等待 Promise 的设计实现，须在目标 WebView 环境实测；阅读书源 webJs 不应依赖 Promise
- `java.webView(html, url, js)` 三参形式仅当目标阅读端该方法可调用：`html` 非空时以 `url` 为 baseURL 经 `loadDataWithBaseURL` 直接渲染交接文档；`html` 为 null/空白仍走 `loadUrl` 抓线上页面。沙箱侧 `url` 必须是公网 http/https（file/javascript/data/content 等 scheme 与回环/私网地址一律拒绝，重定向后的 finalUrl 同口径复验），`html` 超过 4,000,000 字符被拒。跨分支优先 URL `webView`+`webJs`。页内解码脚本由 jsLib 函数返回，不要塞超长字面量
- WebView 图片加载默认关闭（`blockNetworkImage = true`），不要依赖图片触发 lazy-load
- 网站可能检查 `event.isTrusted`，程序化触发的点击/滚动会被忽略
- `console.log` 仅在 `@webjs:` 规则段输出到调试面板；URL 选项与 `ruleContent.webJs` 路径无此输出（官方 App 行为）
- webJs 不影响的字段如 ruleContent.webJs 的 `sourceRegex` 等仍然在 Rhino 环境执行
