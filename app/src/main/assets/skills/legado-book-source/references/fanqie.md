# 单站深案：番茄小说（fanqienovel.com）

> 2026-09 实测（官方 APK 逆向 + 工坊 Rhino 真实请求 + 真机排障）。**禁止凭枚举名猜参数。**
> 通用正文管线（result 类型、翻页机制、replaceRegex 时机）见 [`content-rules.md`](content-rules.md)，本文只收番茄特有机理。

适用：番茄小说书源的**五件套——搜索、发现、详情、目录、正文**。
评论相关能力（书评/章评/段评/回复、评论面板、阅读界面自定义按钮）已从书源中整体移除，不要往回加。

## 零、四条铁律

1. **目录与正文的 URL 契约必须一致**。`ruleToc.chapterUrl` 输出的 URL 就是 `ruleContent.content` 里的 `baseUrl`。
   - 事故：目录指向第三方中转，正文却用 `/reader\/(\d+)/` 解析 → 每章报 `Error: 章节编号缺失`，整源变废品。
   - 规范写法：`chapterUrl` = `https://fanqienovel.com/reader/<itemId>?bookId=<bid>`；`content` 解析需兼容
     `item_id=` / `reader/<id>` / `itemId=` / `chapterId=` / 纯 15+ 位数字，报错信息必须带上 baseUrl 便于定位。
2. **禁止任何第三方中转**。搜索、发现、详情、目录、正文全部走官方域名；自建中转一旦下线或改协议，整源即废。
3. **匿名只读**。不登录官方账号；设备注册与密钥链完全由书源内部维护（`source.put('fq_key')` / `fq_kv`），失败就重新注册。
4. **正文只保留官方插图**。正文解密后是 XHTML，`<img>` 是官方插图（`*.byteimg.com` 防盗链签名图），
   `<h1>/<p>` 等是排版标签。**只能剥排版标签，不能一刀切 `<[^>]+>`**，否则整本书的正文插画全丢。

## 一、接口总表（全部官方）

| 能力 | 方法与地址 | 签名 | 关键点 |
|---|---|---|---|
| 搜索 | GET `https://novel.snssdk.com/api/novel/channel/homepage/search/search/v1/?aid=1967&q=<kw>&offset=<n>` | 不需要 | 结果在 `data.ret_data`；**书名是 `title`**（不是 `book_name`） |
| 发现（书库） | GET `https://fanqienovel.com/api/author/library/book_list/v0/?page_count=20&page_index=<n>&gender=<0\|1>&category_id=<cid>&creation_status=-1&word_count=-1&book_type=-1&sort=0` | 不需要 | 结果在 `data.book_list`；**书名/作者/摘要/最新章节全部 PUA 字体混淆**，必须查表反解 |
| 详情 | GET `https://fanqienovel.com/api/book/info?bookId=<bid>` | 不需要 | 作为 `bookUrl`；`tocUrl` 由 bookId 拼目录接口 |
| 目录 | GET `https://fanqienovel.com/api/reader/directory/detail?bookId=<bid>` | 不需要 | `data.chapterListWithVolume[][]`，每章 `itemId` / `title` |
| 正文 | GET `https://reading.snssdk.com/reading/reader/full/v?<baseQuery>&item_id=<itemId>&req_type=1` | **必须** | 需匿名设备 + registerkey；正文 AES 加密 |

## 二、发现页（`exploreUrl` + `ruleExplore`）

### 2.1 `exploreUrl` 语法

- 每行一条，格式 `显示名::URL`；分页变量 `{{(page-1)}}`、页码变量 `{{page}}`。
- **发现页搜索框**：某一行 URL 里含 `{{key}}` 时，阅读客户端会在发现页顶部原生渲染搜索输入框，
  用户输入后 `key` 被替换并请求。首行固定为 `🔍搜索::<搜索接口>`：

```
🔍搜索::https://novel.snssdk.com/api/novel/channel/homepage/search/search/v1/?aid=1967&q={{key}}&offset={{(page-1)*10}}
```

- `q=` 在运行时由阅读内核做 URL 编码，**规则里不要再套 `encodeURIComponent`**。
- 分类行用官方书库接口，男频 `gender=1`、女频 `gender=0`，`page_index={{(page-1)}}`：

```
男频·都市::https://fanqienovel.com/api/author/library/book_list/v0/?page_count=20&page_index={{(page-1)}}&gender=1&category_id=1&creation_status=-1&word_count=-1&book_type=-1&sort=0
```

### 2.2 书库接口的 PUA 字体混淆（核心）

- 书库接口把常用汉字替换到 Unicode 私有使用区（实测码位 `0xe3e8`~`0xe55b`），
  直接取值会得到 `\ue40e\ue53f精\ue437病院...` 这类乱码。
- 反解办法：内置完整映射表 `FQ_LIB_MAP`（361 项，`码位 -> 汉字`），逐字符 `charCodeAt` 查表，查不到就原样保留：

```js
function fqLibDecode(text) {
  text = String(text || '');
  var out = '';
  for (var i = 0; i < text.length; i++) {
    var code = text.charCodeAt(i);
    if (FQ_LIB_MAP[code]) out += FQ_LIB_MAP[code];
    else out += text.charAt(i);
  }
  return out;
}
```

- 搜索接口用的是另一张表 `FQ_SEARCH_MAP`（搜索返回里也可能带少量混淆），同样逐字符反解。
- 两张表在 `ruleContent.content` 里定义；装配时**抽取并内联进 `ruleExplore.bookList`**，
  使发现页规则自包含，不依赖正文规则先执行。

### 2.3 `ruleExplore.bookList` 双模分发

发现页同时存在两种响应结构，必须一个规则兼容：

| 来源 | 数据路径 | 书名取值 | 编码处理 |
|---|---|---|---|
| 书库分类（`/author/library/book_list/v0/`） | `data.book_list` | `book_name` | `fqLibDecode` |
| 发现页搜索（`/search/v1/`） | `data.ret_data` | `original_book_name` → `book_name` → `title` | `fqSearchDecode` |

实现要点：`parseObj(result)` 兼容字符串/对象；搜索分支沿用 `genre` 过滤（4/8/11/905 非小说）；
每条结果 `JSON.stringify` 成一行，字段名与 `ruleExplore` 的 `$.xxx` 路径一一对应：
`book_name` / `author` / `category` / `abstract` / `thumb_url` / `book_id` / `word_number` / `last_chapter_title`。
`bookUrl` 统一为 `$.book_id<js>'https://fanqienovel.com/api/book/info?bookId=' + result</js>`，与搜索页保持同一详情入口。

### 2.4 已知现象

- 书库分类的 `last_chapter_title` 在部分分类（如女频现代言情）为空，属官方返回缺字段，不是规则问题。
- 书库摘要里可能含裸反斜杠等字符，`eval_js` 在序列化时可能产出非法 JSON，
  验证发现页请优先用 `debug_source` 的 `分类::URL` 入口（App 内真实运行时，不走 MCP JSON 序列化）。

## 三、正文直连链路（签名 + 解密 + 插图）

1. `baseQuery`（固定设备参数）：`iid=&device_id=&ac=wifi&channel=43536163a&aid=1967&app_name=novelapp&version_code=70132&version_name=7.0.1.32&device_platform=android&os=android&ssmix=a&os_version=10&device_type=P30&device_brand=realme&update_version_code=70132&manifest_version_code=70132`
2. 签名头：`x-argus` / `x-ladon` / `x-khronos` / `x-ss-req-ticket`（SM3 + 请求体摘要；`user-agent: com.dragon.read`，POST 加 `Content-Type: application/json; charset=utf-8`）。
3. **匿名设备 + 密钥**：GET `https://reading.snssdk.com/reading/crypt/registerkey?<baseQuery>`，body 为注册 JSON；返回 `data.key`（加密）与 `keyver`。解出的 key 存 `source.put("fq_key")`、`keyver` 存 `fq_kv`，避免重复注册。
4. 正文解密：`content` 是 base64；前 16 字节为 IV，其余 AES 解密 → `compress_status===1` 再 gunzip → 得到官方 XHTML。
5. `key_version` 与缓存 `fq_kv` 不一致时，清空 `fq_key` 重新注册。
6. 错误语义：`code!==0` 抛错（`code=110` = 该章需权限/加锁，非本模块可解）；`data.content` 为 `Invalid` 或空 → 返回空正文。

### 3.1 官方正文插图保护与还原（占位-保护-还原）

官方 XHTML 里的配图是标准标签，形如：

```html
<img src="http://p6-novel-sign.byteimg.com/novel-pic/<hash>~tplv-snk2bdmkp8-31.image?lk3s=8d963091&amp;x-expires=...&amp;x-signature=..." img-width="278" img-height="61" .../>
```

处理顺序**不能颠倒**，否则要么丢图，要么留乱码标签：

1. **先抽图**（在剥标签之前）：匹配 `<img ...>` 与 SVG `<image xlink:href="...">`，取 `src`，
   把 `&amp;` / `&#38;` 还原成 `&`，`http://` 与 `//` 统一升级为 `https://`，存入数组 `fqImgs`；
2. **原地打占位符**：把标签替换成 `\u0001FQIMG<n>\u0001`（控制字符包围，正常正文不会出现）；
3. **再清洗文本**：剥 `<h1>` 与其余标签、反转义 HTML 实体、跑 `fqDecode`；
4. **回填**：`fqDecode` 之后把占位符换回 `\n<img src="...">\n`，并收敛多余空行。

要点：

- 千万不要用 `text.replace(/<[^>]+>/g, "")` 一次性清标签——那会把插图一起清掉（真实事故）。
- 回填用换行包裹，让阅读器把图当独立块排版，不触发额外网络探测。
- 图片 URL 带防盗链签名，**不要缓存或改写参数**，也不要再套自建代理。

### 3.2 哪些书有插图

- **只有"正文 XHTML 自带 `<img>`"的章节才有图**：原创书里的图鉴/图集章，以及出版物（`is_ebook=1`）里的图文章。
- 官方另有一个**阅读器取图接口**（仅作对照，正文规则不要调用）：
  `GET https://reading.snssdk.com/reading/reader/multi_full_image/v?<baseQuery>&item_id_list=<章节itemId>&book_id=<bookId>`
  → `data.image_url_data.<itemId> = [图片URL...]`，与正文里的 `<img>` 内容一致；纯文字章返回 `[]`。
- 反例（不要误判成书源 bug）：部分章节正文解密后 `<img>` 为 0，`multi_full_image` 也返回 `[]`。
  官方 App 该章顶部的"东方IC"水印配图属**平台侧智能配图/AI 插图**，**不走正文接口**，第三方书源取不到。

### 3.3 内嵌排版 CSS 必须整块剥离

部分章节的官方 XHTML 会在正文开头带一坨 `<style>`：

```html
<style>
.volumePicture img { bd-object-fit: screen; bd-scale-type: top-crop; bd-image-filter:mask; clickable:0; selectable:0; }
/**页面背景*/.pageBg { background-image:url(http://p3-novel.byteimg.com/novel-static/...~tplv-noop.image); bd-page-background:1; }
/**标题数字*/ .chapterTitleNum { font-family: jiangxizhuokai !important; }
@med...
</style>
```

- `<[^>]+>` 只删标签、**不删内容**，CSS 会整段漏进正文，阅读器里显示成 `.volumePicture img { ... }` 纯文本（用户实测截图事故）。
- 正确顺序：**先整块删 `<style>…</style>` / `<script>…</script>` / `<!--…-->` / `<link|meta>`，再抽 `<img>`，最后剥标签**。
  删除正则要容忍未闭合：`/<style[^>]*>[\s\S]*?(?:<\/style\s*>|$)/gi`。
- 兜底（style 标签缺失/未闭合）：先删 `@media … { … }` 块，再循环 3 次删 `(^|\n)\s*[.#@][\w\-.#:\s,>+~\[\]="']*\{[^{}]*\}`，避免嵌套块残留。
- 注意 `.pageBg` 的 `background-image:url(...)` 是**页面背景样式**，不是正文插图，必须随 CSS 一起丢弃；
  而 `.volumePicture` 容器里的真实 `<img>` 是正文卷首图，应保留。
- 验证口径：同一章跑旧/新规则做 `difflib` 逐字 diff，差异块应只有「删除 CSS」这一块，正文文本零改动。

## 四、规则实现要点

### 搜索（`searchUrl` + `ruleSearch`）

- `searchUrl`：`@js` 拼 `offset=(page-1)*10`，`q=` 用 `encodeURIComponent(key)`；**无需签名**。
- `ruleSearch.bookList`：`@js` 解析 `data.ret_data`，`parseObj` 兼容字符串/对象两种返回。
  过滤 `genre` ∈ {4, 8, 11, 905}（非小说/短剧等），书名优先 `original_book_name`，去掉 HTML 标签与实体。
- 字段路径：`name=$.book_name`、`author=$.author`、`kind=$.category`、`wordCount=$.word_number`、
  `lastChapter=$.last_chapter_title`、`intro=$.abstract`、`coverUrl=$.thumb_url`、
  `bookUrl=$.book_id<js>'https://fanqienovel.com/api/book/info?bookId=' + result</js>`、`checkKeyWord=我的`。

### 详情（`ruleBookInfo`）

- `init`：`baseUrl` 已是 `/api/book/info` 就直接用 `result`；否则按 `/page/<id>`、`/reader/<id>`、`bookId=` 提取 id 后
  `java.ajax` 详情接口，异常回退原 `result`。
- 字段统一走 `fqPick(result, key)`：**先按 JSON 解析**（`data.<key>`），失败再按网页 HTML 正则抓 `"<key>":"…"` 或 `"<key>":数字`，
  并反转义 `\u002F`、`\/`、`\"`：
  `name=bookName`、`author=author`、`lastChapter=lastChapterTitle`、`intro=abstract`、`coverUrl=thumbUrl`、
  `wordCount=wordNumber`（数字型同样兼容）。
- `kind`：JSON 分支解析 `data.categoryV2`（取 `Name`）→ `completeCategory`（`/`→`,`）→ `creationStatus`（1=连载）；
  HTML 分支抓 `"category"` 与 `"status"`；`canReName=true`。
- `tocUrl`：`@js` 由 bookId 拼 `https://fanqienovel.com/api/reader/directory/detail?bookId=<id>`。
- HTML 兜底：此前从网页链接 `/page/<id>` 进书时详情字段全空，只按 JSON 解析 `result` 会复现。

### 目录（`ruleToc`）

- `chapterList`：`@js` 解析 `data.chapterListWithVolume`（二维数组按卷分组，需拍平）——**仅适用于官方一次性返回的 JSON 目录接口**；HTML 目录页仍须优先 CSS：`chapterList`+`chapterName`/`chapterUrl`，分页用 `nextTocUrl`，禁止循环翻页自建数组。**解析结果为空就兜底**：
  用 `baseUrl` 或响应里的 `bookId` 再 `java.ajax` 一次目录接口重解析。
  注意不能只看键名是否存在——`/page/<id>` 的 HTML 里也含 `chapterListWithVolume` 字样
  （此前该入口目录返回 0 章）。
- `chapterName=$.title`；`chapterUrl` = `'https://fanqienovel.com/reader/' + itemId + (bid ? '?bookId=' + bid : '')`。

### 正文（`ruleContent`）

- 入口先解析章节号，兼容 `item_id=` / `reader/<id>` / `itemId=` / `chapterId=` / 纯 15+ 位数字；
  解析失败抛 `章节编号缺失:<baseUrl>`。
- 取正文：`fqGetDevice()` → `fqGetKey(dev)` → `fqGetTextChapter(dev, key, itemId)`；
  `!ch.content` 抛 `官方未返回可读正文`；正常则 `answer = ch.content`。
- **不要**在正文规则里做任何额外网络请求（旧版曾为段评气泡每章多发 1~2 次请求，导致加载变慢）。

### 元数据（公开标准）

```json
{
  "bookSourceName": "番茄小说",
  "bookSourceGroup": "番茄",
  "bookSourceComment": "官方接口直连",
  "bookSourceType": 0,
  "bookSourceUrl": "https://fanqienovel.com",
  "enabled": true,
  "enabledExplore": true,
  "enabledCookieJar": false,
  "customOrder": 0,
  "customButton": false,
  "eventListener": false,
  "weight": 0,
  "respondTime": 180000,
  "lastUpdateTime": 0,
  "concurrentRate": "3/1000"
}
```

名称/分组/注释只写站点与能力，**不写研发备注、版本号、模块删减历程**，不要带个人或内部标记。
官方字段按上表出全（`bookSourceType/Url/enabledExplore/enabledCookieJar/customOrder/customButton/eventListener/weight/respondTime/lastUpdateTime`），对照 `template.yaml` 复核。

## 五、装配与验证

- 成品与中间产物放 `work/{书源名称}/` 独立目录（构建脚本、映射表、调试快照一并落入）。
- 分步验证：`validate → 发现页分类/搜索 → 搜索 → 详情 → 目录 → 正文（普通章 + 插图章）`，每步确认 `validation.issues` 为空。
- 最终版实测口径：`issues=[]`；发现页男频都市 p1/p2 各 20 本且分页不重复；发现页搜索「玄幻」10 条；
  搜索「我的绝美女友」8 条；详情字段齐全；目录章节名/URL 正确；普通章 0 图纯文本、插图章 2 张 HTTPS 官方插图。
- MCP 侧两个已知坑：`save_source` 的 `source` 参数在某些版本必须传 stringified JSON 字符串（传对象会报 `JsonPrimitive` 类型错）；
  大结果被包装成 `{resultId, preview, nextOffset, hasMore}`，用 `read_result` 分段取回（上限与上下文纪律见 `SKILL.md`）。

## 六、排障清单

| 症状 | 根因 | 处置 |
|---|---|---|
| Error: 章节编号缺失 | 目录 URL 与正文解析契约不一致 | 按第零节统一契约 |
| 官方未返回可读正文 | 章节加锁/权限（code=110）或设备失效 | 换章验证；清 `fq_key`/`fq_kv` 重注册 |
| 官方正文拒绝 code=xxx | 签名头缺失或设备参数不一致 | 核对 baseQuery 与签名头 |
| 正文插画消失 | 用 `<[^>]+>` 一刀切清标签 | 改「抽图 + 占位 + 回填」三段式 |
| 正文开头出现 `.xxx { ... }` / `bd-` / `@media` 等 CSS 文本 | `<style>` 只被删了标签、内容未剥离 | 按 3.3：先整块删 style/script，再抽图，最后剥标签 |
| 正文出现 `\u0001FQIMG0` 字样 | 回填未执行（或 `fqDecode` 锚点被改） | 检查占位符回填位置在 `fqDecode` 之后 |
| 插图 URL 打不开 | 协议头仍是 `http://` 或 `&amp;` 未还原 | 统一升级 HTTPS 并反转义实体 |
| 发现页没有搜索框 | `exploreUrl` 里没有含 `{{key}}` 的行 | 首行固定为 `🔍搜索::...q={{key}}...` |
| 发现页书名乱码 | 未做 PUA 反解 | 用 `fqLibDecode`（书库）/ `fqSearchDecode`（搜索） |
| 发现页某分类空 | 该 `category_id` 失效或 gender 传错 | 用真实 id 重试；男频 gender=1、女频 gender=0 |
| 搜索为空 | 书名字段取错（应为 `title`）或结果不在 `ret_data` | 修正字段路径 |
| 目录为空 | 未拍平 `chapterListWithVolume[][]` 或未兜底二次请求 | 按第四节目录要点处理 |
| 从 `/page/<id>` 进书详情全空 | 只按 JSON 解析 `result` | 走 `fqPick` 的 HTML 兜底 |
| 从 `/page/<id>` 进书目录 0 章 | 只判断键名存在就跳过兜底请求 | 改为按解析结果是否为空判断 |
| 详情 kind 为空 | `categoryV2` 解析失败且无 `completeCategory` | 回退 `category` 字段 |
| JS 报「字符串文字没有限制」 | searchUrl 拼接引号不配对 | 检查生成的 URL 表达式 |
| 正文加载慢 | 正文规则里塞了额外网络请求 | 只保留 `fqGetTextChapter` 一次请求 |

## 七、已知限制

- 部分正文 `code=110` 加锁，不承诺全本可读。
- 官方 App 章节顶部偶尔出现的"配图"（如东方IC 水印图）属平台侧智能配图/AI 插图，不在正文与取图接口内，
  第三方源取不到；只有正文 XHTML 自带 `<img>` 的章节才有图（见 3.2）。
- 搜索接口返回条数以官方为准（实测每页 6~10 条），不保证与网页版一致。
- 书库分类接口部分分类不返回 `last_chapter_title`，发现页该列可能为空。
- 设备注册与密钥有有效期，`key_version` 变更后首次请求会重新注册（略慢），之后走缓存。
