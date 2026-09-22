# 实战模式：禁漫书源技巧精粹

> **漫画书源完整制作指南**见 `references/comic.md`。本文档侧重于可跨类型复用的高级模式。


以下模式提取自禁漫画源，覆盖了 Legado 书源中高级用法。每个模式展示了「问题 → 解法 → 代码范例」结构。

---

## 1. 书源变量持久化（跨页面状态管理）

**问题**：书源需要在搜索、发现、详情、目录等多个页面间共享状态（如当前分流线路、排序方式、分类选择），但 Legado 没有全局变量。

**解法**：用 `source.getVariable()` / `source.setVariable()` 存取 JSON，封装 `Get()` / `put()` 辅助函数。

```javascript
// jsLib 中定义辅助函数
function put(data) {
    const { java, source } = this;
    return source.setVariable(JSON.stringify(data, null, '\t'));
}
function Get(e) {
    const { java, source } = this;
    var get = JSON.parse(source.getVariable());
    return get[e];
}

// 使用：读取当前分流
let currentShunt = Get('shunt');  // 0-3

// 切换分流
$$$.shunt = newIndex;
put($$$);
```

**版本升级**：在 `loginUrl` 中检查变量版本号，不匹配时重置默认值：

```javascript
try {
    $$$ = JSON.parse(source.getVariable());
    if (!$$$ || $$$.ver !== 2.2) { error; }
} catch (e) {
    $$$ = original;  // 默认值对象
    put($$$);
}
```

---

## 2. 多线路分流（镜像站切换）

**问题**：网站有多个镜像服务器，用户需切换线路访问。URL 改变但解析规则不变。

**解法**：将所有线路 URL 存数组，用索引切换；章节 URL 拼接分流参数。

```javascript
// 线路数组
const SH = ['1', '2', '3', '4'];

// 获取当前线路
let URL = Get('urls')[Get('ci')];

// 搜索URL
searchUrl: "{{Get('urls')[Get('ci')]}}/search/photos?search_query={{key}}&page={{page}}"

// 章节URL拼接分流参数
chapterUrl: "href\n@js:\nresult += '/?shunt=' + SH[Get('shunt')]"
```

**动态获取线路**：从目标网站首页抓取可用镜像地址：

```javascript
function url() {
    const baseUrl = java.connect(Get('rel')).url();
    // java.ajax 返回 java.lang.String（Java 对象）：
    // 字符串操作前必须先 String() 包裹，正则 replace 只能用在 JS 字符串上
    const html = String(java.ajax(baseUrl));
    let allUrls = [];
    ['china', 'first_line', 'second_line'].forEach(key => {
        const reg = new RegExp(`${key}\\"><span>(.*?)<\\/span`);
        const match = html.match(reg);
        if (match && match[1]) {
            allUrls.push(match[1].replace(/\s+/g, ""));
        }
    });
    $$$.urls = [...new Set(allUrls)];  // 去重
    put($$$);
}
```

> 要点：`java.*` 返回的都是 Java 对象。`.match`/`.replace` 前先 `String()` 包裹；域名等纯文本修正也可直接 `.replace("a.com", "b.com")`。对 java 裸值直接 `.replace(/正则/, ...)` 会报「选择不明确」（Rhino）。

---

## 3. 多类型书源（漫画 + 小说二合一）

**问题**：网站同时提供漫画和小说，两种类型的页面结构和字段不同，但想用一个书源覆盖。

**解法**：在 `ruleBookInfo.init` 中检测 URL 类型并存标记，后续各字段用 JS 分支判断。

```javascript
// ruleBookInfo.init
<js>
url = baseUrl
num = baseUrl.includes("novel") ? 1 : 2;
java.put("btype", num);
if (num == 2) {
    jmBookId = baseUrl.match(/album\/(\d+)/)[1];
    java.put("jmBookId", jmBookId);
}
result
</js>

// ruleBookInfo.author - 按类型选择不同选择器
@js:
if (baseUrl.match(/novel/)) {
    text = java.getString('.p-t-5 li a@text');
} else {
    text = java.getString('a.web-author-tag:nth-of-type(1)@text');
}
text;

// ruleToc - 按类型设置 book.type
<js>
let type = +(java.get('btype'))
switch (type) {
    case 1: book.type = 8; break;   // 小说
    case 2: book.type = 64;          // 漫画
}
result
</js>

// ruleContent - 按类型区分正文提取
@js:
url = baseUrl
if (baseUrl.includes("novel")) {
    if (!result) {
        result = "\n 90天后会自动解锁"
    }
    result
} else {
    result.split("\n").map(x => '<img src="' + x + '">').join("\n")
}
```

**关键点**：
- 小说 type=8（文本），漫画 type=64（图片）
- 用 `java.put()` / `java.get()` 在规则间传递类型标记
- 所有需要区分类型的字段都用 JS 分支

---

## 4. 登录系统（自定义 UI + JS 登录逻辑）

**问题**：网站需要登录才能看完整内容，但登录流程较简单（用户名+密码 POST），无需 WebView。

**解法**：`loginUi` 定义表单控件，`loginUrl` 编写登录逻辑函数。

```json
// loginUi
[
    { "name": "获取链接", "type": "button", "action": "url()" },
    { "name": "分流", "type": "select", "action": "sh(...)", "chars": ["1","2","3","4"], "default": "1" },
    { "name": "账号", "type": "text" },
    { "name": "密码", "type": "password" },
    { "name": "登录", "type": "button", "action": "Login()" }
]
```

```javascript
// loginUrl 中的登录函数
function Login() {
    const result = JSON.parse(source.getLoginInfo());
    var username = result['账号'];
    var password = result['密码'];
    var options = {
        body: `username=${username}&password=${password}&submit_login=1`,
        method: 'POST'
    };
    var request = JSON.parse(
        java.log(java.ajax(Get('urls')[Get('ci')] + `/login,${JSON.stringify(options)}`))
    );
    if (request.status == '1') {
        login('✅ 登录成功');
    } else {
        login('❌ 登录失败');
    }
}
```

**关键点**：
- 按钮的 `action` 对应 `loginUrl` 中的函数名
- `source.getLoginInfo()` 返回用户填入的表单数据 JSON
- 分流/排序等 select 控件改变时立即调用函数持久化状态

---

## 5. 动态发现页生成（纯 JS 构建菜单）

**问题**：发现页需要多级分类、动态内容（如本周连载、历史记录）、用户状态展示（欢迎语），静态列表不够灵活。

**解法**：`exploreUrl` 用 `@js:` 开头，返回 JSON 数组。用 `push()` 辅助函数逐项添加。

```javascript
// exploreUrl
@js:
eval(String(source.loginUrl));  // 共享 loginUrl 中的变量和函数
let URL = Get('urls')[Get('ci')];
let list = [];

const push = (title, url, size) => list.push({
    "title": title,
    "url": url,
    "style": {
        "layout_flexGrow": 1,
        "layout_flexBasisPercent": size
    }
});

// 用户信息
const authInfo = JSON.parse(source.getLoginInfo());
const username = authInfo?.['账号'] || "游客";
push('[▶ ' + username + ' ◀]', '', 1);

// 本周连载（动态计算星期）
const today = new Date();
const day = today.getDay() === 0 ? 7 : today.getDay();
push('今日连载 - ' + weekDays[today.getDay()].D, Get('url') + '/serialization/' + day, 1);

// 遍历分类数组批量生成
_list.map(([t, u]) => {
    push(t, `${URL}/${u}?o=${sort}&page={{page}}`, 0.25);
});

JSON.stringify(list);
```

**关键点**：
- `eval(String(source.loginUrl))` 复用 jsLib 中的共享函数和变量
- `push()` 返回 item 对象，集中构建后 `JSON.stringify(list)` 输出
- `style.layout_flexBasisPercent` 控制每行显示几个按钮（1=整行，0.5=半行，0.25=1/4行）

---

## 6. 图片解密（Java Bitmap 块重组）

**问题**：网站将图片分割成 N 块后打乱顺序排列，需要前端重组才能正常显示。

**解法**：用 `JavaImporter` 导入 Android Graphics 包，用 `BitmapFactory` 解码后用 `Canvas` 重组。

```javascript
// imageDecode
var Magua = new JavaImporter();
Magua.importPackage(Packages.java.io, Packages.android.graphics);
with (Magua) {
    let mac = src.match(/photos\/(\d+)?\/(\d+)?/);
    let bookId = mac[1];
    let imgId = mac[2];

    // 根据 bookId 范围确定块数
    if (Number(bookId) > 421925) {
        let md5 = java.md5Encode((bookId + imgId));
        let ascii = md5.substr(-1).charCodeAt(0);
        var num = (ascii % 8 + 1) * 2;
    } else if (Number(bookId) >= 268850) {
        let md5 = java.md5Encode((bookId + imgId));
        let ascii = md5.substr(-1).charCodeAt(0);
        var num = (ascii % 10 + 1) * 2;
    } else {
        var num = 10;
    }

    // 解码 → 切片 → 反向重组
    var img = BitmapFactory.decodeByteArray(result, 0, result.length);
    var height = img.getHeight();
    var width = img.getWidth();
    var y = Math.floor(height / num);
    var remainder = height % num;
    var newImg = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    let canvas = new Canvas(newImg);
    for (let i = 1; i <= num; i++) {
        let h = i === num ? remainder : 0;
        canvas.drawBitmap(
            Bitmap.createBitmap(img, 0, y * (i - 1), width, y + h),
            0, height - y * i - h, null
        );
    }
    var newbit = new ByteArrayOutputStream();
    newImg.compress(Bitmap.CompressFormat.PNG, 100, newbit);
    newbit.toByteArray();
}
```

**关键点**：
- `JavaImporter().importPackage()` 导入 Android 原生类
- `result` 是图片的 ByteArray，`src` 是图片 URL
- 必须返回 ByteArray（不是 Bitmap），用 `ByteArrayOutputStream` 转换
- 使用 `with(Magua) { ... }` 简化类名引用

---

## 7. 缓存限流（一次性操作控制）

**问题**：某些初始化操作不应被频繁执行（如自动填充登录信息），需要限制执行次数或频率。

**解法**：用 `cache.put()` 配合过期时间做计数器。

```javascript
function GetInfo() {
    const { java, cache, source } = this;
    if (!source.getLoginInfo()) {
        var num = parseInt(cache.get('jmlk') || 0);
        if (num > 100) {
            source.putLoginInfo(loginInfo);
            return;
        }
        cache.put('jmlk', num + 1, 60);  // 60秒过期
    }
}
```

**关键点**：
- `cache.put(key, value, saveTime)` 第三个参数是缓存秒数
- 适合防抖、限频、渐进式初始化等场景

---

## 8. 自定义按钮回调（生成完整 HTML 页面）

**问题**：想在阅读界面添加自定义按钮（如"看评论"），点击后在浏览器中展示定制页面。

**解法**：启用 `eventListener` + `customButton`，在 `callBackJs` 中处理 `clickCustomButton` 事件，用 `java.startBrowser()` 展示。

```javascript
// callBackJs
switch (event) {
    case "clickCustomButton":
        const bookId = book.getVariable("jmBookId");
        if (!bookId) { java.longToast("暂无关联书籍"); break; }

        // 第一页
        const body = "video_id=" + bookId + "&page=1&series=1&with_ad_wcm=1";
        const request = java.post(base + '/ajax/album_pagination', body, {}).body();
        let code = JSON.parse(request).code || "";

        // 如果有更多页，继续获取
        const hasMore = code.indexOf('查看更多') !== -1;
        if (hasMore) {
            const body2 = "video_id=" + bookId + "&page=2&series=1&with_ad_wcm=1";
            const next = java.post(base + '/ajax/album_pagination', body2, {}).body();
            code += JSON.parse(next).code.replace('查看更多', '');
        }

        // 修正图片链接为绝对路径
        let fixedCode = code.replace(/<img src=\"/g, `<img src=\"${base}`);

        // 生成完整 HTML 页面（含 CSS 样式）
        const html = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <title>书评</title>
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>/* ... 大量自定义 CSS ... */</style>
</head>
<body>${fixedCode}</body>
</html>`;

        java.startBrowser("", "", html);
        break;
}
```

**关键点**：
- 需要先设置 `eventListener: true` 和 `customButton: true`
- 书名旁边会出现自定义按钮，点击触发 `clickCustomButton` 事件
- `book.getVariable()` / `book.putVariable()` 在规则间传递书籍级数据
- `java.startBrowser(url, title, html)` 的 html 参数可直接渲染
- 根据系统深色模式适配 CSS（`@media (prefers-color-scheme: dark)`）

---

## 9. 登录验证盾（Cloudflare / 人机验证）

**问题**：网站有 Cloudflare 或其他验证盾，常规请求被拦截返回验证页面。

**解法**：在 `loginCheckJs` 中检测验证特征，启动浏览器等待用户手动验证。

```javascript
// loginCheckJs
var resultUrl = result.url();
var resultCode = result.code();
var resultBoDy = result.body();

if (/_cf_|ge_ua|verify.php/ig.test(resultBoDy)) {
    if (key) { url = baseUrl + java.ruleUrl; }
    cookie.removeCookie(baseUrl);     // 清除旧 Cookie
    result = java.startBrowserAwait(  // 弹出浏览器让用户验证
        resultUrl,
        "验证",
        false
    );
}
result;  // 必须返回
```

**关键点**：
- `loginCheckJs` 在每个请求后都会执行
- 必须清除旧 cookie 再启动浏览器
- `java.startBrowserAwait` 会阻塞直到用户关闭浏览器
- **末尾必须返回 `result`**

---

## 10. 章节 URL 后处理（拼接参数）

**问题**：章节 URL 需要拼接额外参数（如分流线路），但 URL 从 HTML 解析得到的是基础路径。

**解法**：在 `chapterUrl` 规则中用 `@js:` 追加参数。

```javascript
// ruleToc.chapterUrl
href
@js:
result += "/?shunt=" + SH[Get('shunt')]
```

```javascript
// 也可用 preUpdateJs 在更新前重刷目录 URL
preUpdateJs: java.refreshTocUrl()
```

---

## 11. 请求头中的动态 Referer

**问题**：Referer 需要指向当前 baseUrl，但 baseUrl 是动态的。

**解法**：header 中使用 `baseUrl` 字符串（Legado 自动替换）。

```json
{
    "User-Agent": "Mozilla/5.0 ...",
    "Referer": "baseUrl"
}
```

---

## 12. 正文图片样式控制

**问题**：漫画正文的全宽图片需要用 `FULL` 样式而非默认居中。

**解法**：

```
ruleContent.imageStyle: FULL
```

---

## 速查表

| 模式 | 适用场景 | 核心 API |
|------|----------|----------|
| 变量持久化 | 多页面共享状态 | `source.getVariable()/setVariable()` |
| 多线路分流 | 镜像站切换 | `Get('urls')[index]`, `SH[]` 数组 |
| 多类型切换 | 漫画+小说合一 | `java.put("btype", num)`, JS 分支 |
| 登录系统 | 用户名密码登录 | `loginUi` + `loginUrl` 函数 |
| 动态发现页 | 复杂菜单生成 | `exploreUrl` 中用 JS 构建 JSON |
| 图片解密 | 图片块重组 | `JavaImporter`, `BitmapFactory`, `Canvas` |
| 缓存限流 | 防抖/限频 | `cache.put(key, val, ttl)` |
| 自定义按钮 | 看评论/额外功能 | `eventListener` + `callBackJs` |
| 过验证盾 | CF/人机验证 | `loginCheckJs` + `startBrowserAwait` |
| URL后处理 | 参数拼接 | `<js>result += '?param=val'</js>` |

---

## 附录：官方写法 vs 翻车写法（基准 = 官方规则机制 + 26,861 份真实书源语料共识）

**先看语料再动手**：写任何字段前，用 `match_sources`/`get_corpus_source` 查同族真实书源怎么写的。菠萝猫正反样板只是实战参考，不是标准。语料实测共识（2026-09-22 统计）：

| 维度 | 语料共识 |
|------|----------|
| chapterList 纯 CSS | **23,496/26,861（87.5%）** |
| JS 型 chapterList 配 chapterName+chapterUrl | 1,784/1,817（98.2%） |
| nextTocUrl / nextContentUrl 出现率 | 31.5% / 43.1%；最常见惯用法 `text.下一页@href` |
| 手动循环翻页建目录 | 少数（JS 型内严格信号约 22%），且官方 `nextTocUrl` 可覆盖——**禁止** |
| 官方字段 | weight 26857、lastUpdateTime 26859、customOrder 26856、enabledExplore 26856、respondTime 25591、enabledCookieJar 25590、bookSourceComment 25064 份源在写 |
| 坏 API（utf8ToGbk/currentTimeMillis/decodeURI/reversed） | **0 份真实源在用**，坐实不存在，别写 |

真实样例（语料直取，CSS 目录 + nextTocUrl 的六种典型）：

```json
{"chapterList": "id.nr_body", "chapterName": "tag.a.1@text", "chapterUrl": "-", "nextTocUrl": "text.下一章.0@href"}
{"chapterList": "-class.article-list@li", "chapterName": "tag.a@text##>", "chapterUrl": "tag.a@href", "nextTocUrl": "text.下一页@href"}
{"chapterList": "-class.chapterlist@li", "chapterName": "a.0@text", "chapterUrl": "a.0@href", "nextTocUrl": "class.next number@href"}
{"chapterList": "class.chapters.1@tag.li", "chapterName": "tag.a@text", "chapterUrl": "tag.a@href", "nextTocUrl": "text.下一页@href"}
{"chapterList": "id.chapterlist@li@a", "chapterName": "text", "chapterUrl": "href", "nextTocUrl": "text.下一页.0@href"}
```

同一站点、同一功能，两种写法的关键差异。**左列是已翻车五次的非官方写法，右列是官方阅读规则下的成品形态**，新写书源一律以右列为准。

| 维度 | ❌ 翻车写法（禁止） | ✅ 官方写法（照这个来） |
|------|------|------|
| 目录列表 | `chapterList` 巨型 `<js>`：`for(var cp=1;cp<=60;cp++){ java.ajax(...) }` 循环扒 `<a>` 自建 HTML 数组 | `"chapterList": "#chapters .direList li a"` + `"chapterName": "text"` + `"chapterUrl": "href"` |
| 目录分页 | 循环计数 `cp=1..N` 手动翻页，「当前页为空即停」 | `"nextTocUrl": ".page2-chapter .page-range-list .cur + a@href"`（官方字段，自动翻页） |
| 正文解码 | 巨型 `<js>` 手写 base64 码表 + XOR + 手写 UTF-8 位运算重组 | `@js:` 标准两件套：`java.base64DecodeToByteArray(s)` + `new Packages.java.lang.String(bytes, "UTF-8")`，正文兜底 `java.getString(".content p@text")` |
| 域名修正 | `String(java.base64Decode(...)).replace(/正则/, ...)`（Rhino 报「选择不明确」） | `.replace("www.boluomao.com", "www.boluomao1.com")` 纯字符串替换 |
| 章节内分页 | 在 content 的 JS 里反复发请求拼多页 | `"nextContentUrl": ".readPage a:contains(下一页)@href"` |
| 官方字段 | 缺 `customButton/customOrder/eventListener/lastUpdateTime/respondTime/weight/enabledExplore/enabledCookieJar/bookSourceComment` | 按 `template.yaml` 出全（菠萝猫成品即此形态） |

> 唯一允许的 JS 型目录：目录本体由加密 API 返回时（如书友社 `loadChapterPage`，AES 加密 POST 换 JSON）。此时 `@js` 只负责取数据、拼 `<a href>` 片段数组，仍必须配 `chapterName`/`chapterUrl` 字段解析，且文档须标注「仅限 API 型目录」。
