# 获取不到内容？排查指南

> 当规则写了但获取不到内容时，按以下顺序从简到繁排查。
>
> **调试期遇到验证码 / CF / WAF 盾**（工具返回 `verification_required` 或 `webview_mode_enabled` 的 JSON）：不要按本文改规则，先走 [`verification.md`](verification.md) 的验证中心 playbook。本文第 4 节的 `loginCheckJs` 是写进**成品书源**、让官方阅读 App 自己过盾的方案。

## 1. 更换 User-Agent（第一步）

遇到反爬时，**先换 UA**，这是最简单的反爬手段。在书源 `header` 字段中填写 `User-Agent`。

最稳妥的方式是用户在手机上获取自己的浏览器 UA，例如使用 https://tool.ip138.com/useragent/ 网站查看 UA。注意部分网站针对手机 UA 和 PC UA 会分别做适配，网页结构可能变化。

如果还不够，再试 `webView:true` 或 `loginCheckJs`。

## 2. 使用 WebView 渲染页面

当页面上有内容但 OkHttp 请求返回空 HTML（SPA 站点、JS 动态渲染、需执行脚本后才出现内容），最简单的修复：在 URL 后加 `,{"webView":true}` 用 WebView 加载。

```
https://example.com/page,{"webView":true}
```

组合使用示例（同时开启 WebView + 编码 + 延迟）：

```
https://example.com/page,{"webView":true, "webJs":"...", "webViewDelayTime":2000}
```

**注意：** `webView` 设为 `true` 时才会执行 `webJs`。若缺省或为 `false`，即使传了 `webJs` 也不会启用 WebView。

完整 URL 选项（method/charset/header/body/js/bodyJs/dnsIp 等）见 `references/js-api.md`。<!-- url-选项参考 -->

## 3. webJs 等待页面就绪

正文规则中的 `webJs` 字段可以用于处理**懒加载页面**。与普通请求不同，webjs 会在 WebView 中反复执行，直到返回非 `null` 值才结束。

**前置要求：** 使用 `contentRule.webJs` 前，URL 必须带 `,{"webView":true}` 选项，否则 WebView 路径不通，`webJs` 被静默忽略。如果章节 URL 不便于加选项，可将 webjs 写在内联规则中通过 `@@webjs:` 触发。

### 原理

```
webjs 返回 null → 等待后重新执行 → 再次检查 → 直到返回非 null
```

- 返回 `null` → 继续轮询等待
- 返回字符串 → 作为页面 HTML 内容，结束轮询

### 典型用法（等待懒加载内容）

```javascript
(() => {
  // ========== 用户配置区 ==========
  const selector = '.read-content';
  const scrollToBottom = true;

  // 检测目标：'html' 检查 outerHTML，'text' 检查 innerText
  const checkTarget = 'html';

  // 对检测目标做正则匹配（均作用于 checkTarget 的结果）
  const expectPattern = null;                       // 必须匹配才视为就绪
  const rejectPattern = /加载中|loading|请稍候/i;    // 匹配则未就绪，设 null 关闭

  // 自定义就绪检测，存在时独占判断，正则不生效
  const readyCheck = null;

  // 是否压缩空白符（仅 checkTarget = 'text' 时生效）
  const normalizeWhitespace = true;
  // ==============================

  if (scrollToBottom && !window.__legado_scrolled) {
    window.scrollTo(0, document.body.scrollHeight);
    window.__legado_scrolled = true;
    return null;
  }

  const el = document.querySelector(selector);
  if (!el) return null;

  // readyCheck 独占：存在时忽略正则
  if (typeof readyCheck === 'function') {
    return readyCheck(el) ? document.documentElement.outerHTML : null;
  }

  let content = checkTarget === 'html'
    ? el.outerHTML
    : (el.innerText || el.textContent || '');
  if (checkTarget === 'text' && normalizeWhitespace) {
    content = content.replace(/\s+/g, ' ').trim();
  }

  if (rejectPattern && rejectPattern.test(content)) return null;
  if (expectPattern && !expectPattern.test(content)) return null;

  return document.documentElement.outerHTML;
})();
```

### 配置项说明

| 配置项 | 作用 |
|--------|------|
| `selector` | 内容元素的 CSS 选择器 |
| `scrollToBottom` | 首次执行时滚动到底部，触发懒加载 |
| `checkTarget` | `'html'` 检查 outerHTML，`'text'` 检查 innerText |
| `expectPattern` | 必须匹配才视为就绪（null 关闭） |
| `rejectPattern` | 匹配则未就绪，继续等待 |
| `readyCheck` | 自定义就绪函数，存在时独占判断 |
| `normalizeWhitespace` | 压缩空白符（仅 text 模式生效） |

### 执行流程

1. **首次执行**：滚动到底部（触发懒加载），返回 `null`
2. **后续执行**：检查目标元素内容是否就绪
   - 未就绪（匹配 `rejectPattern`）→ 返回 `null`
   - 已就绪 → 返回 `document.documentElement.outerHTML`

### 适用场景

- 需要 JS 渲染的 SPA 页面
- 懒加载内容（滚动后才加载）
- 有"加载中"提示的页面
- 需要等待动态内容就绪的场景

### 调试注意

webjs 会在 WebView 中反复执行，注意避免无限循环。确保 `rejectPattern` 能正确识别未就绪状态，`expectPattern` 或 `readyCheck` 能正确识别就绪状态。

### WebView 内置 JS 方法

- `window.close()` — 关闭浏览器界面
- `screen.orientation.lock()` — 全屏后控制屏幕方向（lock 参数：`"landscape"`、`"landscape-primary"`、`"landscape-secondary"`）
- `window.run("js代码")` — 异步执行阅读函数并返回字符串结果

```javascript
window.run("java.toast('执行成功');'成功'")
.then(r=>alert(r))
.catch(e=>alert("执行出错:"+e));
```

## 4. loginCheckJs 过验证盾（写进成品书源）

> 本节是**成品源**在官方阅读 App 内过盾的方案。MCP 调试期遇到盾请走 [`verification.md`](verification.md)（验证中心 + 每域模式），两者互补不冲突：调试期过完盾验证规则后，若目标站真实使用中会频繁出盾，仍应把本节的 `loginCheckJs` 写进书源。

`loginCheckJs` 位于书源**基础**选项卡。Legado 在每次请求网站后都会执行此 JS 代码，`result` 为响应对象（包含 `url`、`code`、`body` 等属性）。JS 需返回修改后的响应对象。

### 原理

检测响应中是否包含验证特征，如有则启动浏览器等待用户完成验证，验证通过后继续。

### 典型用法（过 Cloudflare 盾）

```javascript
var resultUrl = result.url();
var resultCode = result.code();
var resultBoDy = result.body();
if (/_cf_|ge_ua|verify.php/gi.test(resultBoDy) && resultCode >= 403) {
  if (key) {
    url = baseUrl + java.ruleUrl;
  }
  cookie.removeCookie(baseUrl);
  result = java.startBrowserAwait(resultUrl, "验证", false);
  if (key) {
    url =
      org.jsoup.Jsoup.parse(result.body())
        .select('meta[property="og:url"]')
        .attr("content") || url;
  }
}
result;
```

### 关键步骤

1. 检测响应体中的验证特征（如 `_cf_`、`ge_ua`、`verify.php`）+ 状态码 ≥ 403
2. 清除旧 cookie：`cookie.removeCookie(baseUrl)`
3. 调用 `java.startBrowserAwait(url, title, isPost)` 启动浏览器等待用户操作
4. 验证通过后从新响应中提取正确的 URL
5. **末尾必须返回 `result`**（修改后的响应对象）

### 调试注意

调用 `java.startBrowserAwait` 后，手机上会弹出浏览器窗口显示 Cloudflare 验证页面。用户需要手动点击完成验证（勾选"我不是机器人"等），代码会等待用户操作完成后才继续执行。调试时要提醒用户留意手机上弹出的验证窗口。

## 5. 重定向拦截

对于搜索重定向的源，使用 `java.get()` / `java.post()` 获取重定向后的 URL。

```javascript
// 方法一：使用 java.connect
(()=>{
  if(page==1){
    let url='https://www.yooread.net/e/search/index.php,'+JSON.stringify({
    "method":"POST",
    "body":"show=title&tempid=1&keyboard="+key
    });
    return source.put('surl',String(java.connect(url).raw().request().url()));
  } else {
    return source.get('surl')+'&page='+(page-1)
  }
})()

// 方法二：用 java.post 拿响应，从 headers()（字符串）正则抓 Location
(()=>{
  let base='https://www.yooread.net/e/search/';
  if(page==1){
    let url=base+'index.php';
    let body='show=title&tempid=1&keyboard='+key;
    let resp=java.post(url,body,{});
    let m=(''+resp.headers()).match(/Location:\s*(\S+)/i);
    return base+source.put('surl',m?m[1]:'');
  } else {
    return base+source.get('surl')+'&page='+(page-1);
  }
})()
```

Java 网络请求方法（详见 `references/js-api.md`）：
- `java.get(urlStr: String, headers: Map<String, String>)`
- `java.post(urlStr: String, body: String, headers: Map<String, String>)`
- `java.connect(urlStr, header = null, callTimeout: Int? = null): StrResponse`

## 6. 字体解析（正文乱码）

正文替换规则中使用，根据 f1 字体的字形数据到 f2 中查找字形对应的编码。

```js
<js>
(function(){
  var b64=String(src).match(/ttf;base64,([^\)]+)/);
  if(b64){
    var f1 = java.queryTTF(b64[1]);
    var f2 = java.queryTTF("https://alanskycn.gitee.io/teachme/assets/font/Source Han Sans CN Regular.ttf");
    return java.replaceFont(result, f1, f2, true); // 过滤掉 f1 中不存在的字形
  }
  return result;
})()
</js>
```

## 7. 图片解密

正文或封面图片需要二次解密时使用。`result` 为待解密图片的 `ByteArray`，`src` 为图片链接。封面解密同理，`result` 为 `inputStream`。

```js
// AES 解密
java.createSymmetricCrypto("AES/CBC/PKCS5Padding", key, iv).decrypt(result)

// XOR 解密
function decodeImage(data, key) {
  var input = new Packages.java.io.ByteArrayInputStream(data)
  var out = new Packages.java.io.ByteArrayOutputStream()
  var byte
  while ((byte = input.read()) != -1) {
    out.write(byte ^ key)
  }
  return out.toByteArray()
}
decodeImage(result, key)
```

## 8. 超出文档范围的反爬

上述方法覆盖了 Legado 书源最常见的反爬场景。如果遇到文档未覆盖的反爬手段（请求签名、指纹检测、字体反爬、JS 混淆等），请自行查阅 Legado 源码仓库（需询问用户阅读版本）和小说网页本身来寻找解决方案。Legado 的 `loginCheckJs`、URL 选项、JS 规则等接口足够灵活，很多反爬可以通过 JS 逆向在规则层解决。

> 提醒：反爬手段千变万化，解决方案也需要针对具体情况定制，无法一概而论。过反爬能力受模型本身能力限制，建议使用更好的模型（国外模型对齐较好，可能不会帮助过反爬，建议使用 deepseek v4 pro）。

## 9. searchUrl 的 @js: 报「返回的值无效」

`debug_source` 调 `searchUrl` 的 `@js:` 时，脚本最后一行必须返回 http(s) URL，或 `"url," + JSON.stringify({method,body,headers})` 形式的字符串；写裸文本或非 URL 会被 EvaluatorException「返回的值无效」直接打回。

```javascript
(()=>{
  // 正确写法一：返回 URL
  return "https://example.com/search?key=" + encodeURIComponent(key);
  // 正确写法二：URL + 行内选项（POST/Body 等）
  // return "https://example.com/search," + JSON.stringify({method:"POST", body:"key="+key});
})()
```

## 10. searchUrl 的 @js: 不能带 return，注意 key 的作用域

- `@js:` 最后一行必须是**纯表达式返回**，不能带 `return`（末尾表达式的值即返回值，带 `return` 会报错）
- `key` 变量只在 `searchUrl` 作用域注入；`ruleSearch.bookList` 的 JS 里没有 `key`，需要时先通过 `source.put` 或 URL 参数带过去

## 11. rrssk 签名脚本按天滚动，不能吃跨天缓存

rrssk（第三方搜索）的签名脚本地址按天滚动：`/?action=signJs&v=17-YYYYMMDD`，每天零点换版本。跨午夜若用了含旧日期的页面快照，签名脚本会 404 → 拿不到签名 → 搜索 403。

每次搜索前必须重新抓取搜索页，不能吃跨天缓存；调试遇到 403 先核对签名脚本 URL 里的日期是否为当天。

## 12. rrssk Cookie 三重兜底

rrssk 的 `PHPSESSID` / `__snc` Cookie 在不同环境下 Set-Cookie 暴露方式不一（有的只在响应头里、不会自动进 Cookie 容器），单靠一种方式可能取不到。需要三重兜底：

1. 响应头正则：从 `java.connect(url).headers()` 里正则抓 `Set-Cookie` 字段
2. `java.getCookie(url)`
3. `cookie.getCookie(url)` / `cookie.getKey(url, "PHPSESSID")`

任一途径拿到即 `java.setCookie(url, value)` 固定下来再发搜索请求。

## 13. 整包 save_source 传巨型 @js 规则：JSON 转义与 fields 分传

整包 `save_source` 传巨型 `@js` 规则时，JSON 转义（`\"` `\\` `\n`）极易漏字符，导致 gson 解析报「Unterminated object」。改用 `save_source` 的 `fields` 参数分字段传入，绕开整包大 JSON 的转义地狱：

```
fields={"ruleSearch":{...}}
```

校验失败信息现在会附带错误列附近的源码片段，按提示即可定位漏转义的位置。

## 14. 菠萝猫（boluomao1.com）正文 base64 + XOR 混淆

菠萝猫正文/简介为 base64 + 逐字节 XOR `(i%127)+1` 混淆。**按页面实际机制选解码方式**，不要一律套 XOR——

普通 base64/AES 混淆（语料主流，沙箱均已跑通）：

```js
// base64 直出字符串
java.base64Decode(b64str)
// AES（key/iv 兼容 String 或 ByteArray；Pkcs7Padding 自动回退 PKCS5）
java.createSymmetricCrypto("AES/ECB/Pkcs7Padding", java.base64DecodeToByteArray(key)).decryptStr(b64str)
java.createSymmetricCrypto("AES/CBC/PKCS5Padding", keyStr, ivStr).decryptStr(b64str)
```

XOR / 逐字节混淆（菠萝猫式，字节操作后用 Rhino 官方语法转字符串，官方 App 实测可用）：

```js
var b64 = String(src).match(/data-obf(?:-html)?="([a-zA-Z0-9+/=]+)"/); // 按实际页面取 base64 串
if (b64) {
    var rawBytes = java.base64DecodeToByteArray(b64[1]);
    for (var i = 0; i < rawBytes.length; i++) {
        rawBytes[i] = rawBytes[i] ^ ((i % 127) + 1);
    }
    return "" + new Packages.java.lang.String(rawBytes, "UTF-8");
}
return java.getString(".content p@text"); // CSS 兜底
```

要点：

- 字节转字符串用 `new Packages.java.lang.String(bytes, "UTF-8")`；**不要**用 `java.bytesToStr`（真实书源仅 2/26861 在用，官方 App 未验证）、`new java.lang.String(...)`（不存在）、手写位运算拼 UTF-8（已翻车）
- 域名替换等纯文本修正直接用 `.replace("www.boluomao.com", "www.boluomao1.com")`，不要对 java 返回值上正则 replace

目录/正文分页走官方字段，**不要**写循环翻页：

```json
"ruleToc": {
  "chapterList": "#chapters .direList li a",
  "chapterName": "text",
  "chapterUrl": "href",
  "nextTocUrl": ".page2-chapter .page-range-list .cur + a@href"
},
"ruleContent": {
  "content": "@js:…标准两件套解码…",
  "nextContentUrl": ".readPage a:contains(下一页)@href"
}
```

旧写法在 `chapterList` 里写巨型 `<js>` 用 `cp=1..60` 循环 `java.ajax` 自建章节数组，是官方机制（`nextTocUrl`）能覆盖的事，属五次翻车的典型反模式，禁止再教再写；唯一例外是目录由加密 API 返回（见 `generation-contract.md` 第 2 条）。
