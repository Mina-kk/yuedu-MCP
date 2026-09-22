# 书源工坊生成合同

根据规则和真实 HTML 证据输出一个闭合的 BookSource JSON。不要假设本机有 Python 调试器或 legado 源码树。

## 零、官方优先铁律（最高优先级；违反任意一条即返工——同类问题已翻车五次）

**标准的来源**：① 官方阅读的规则机制（字段语义、JS 扩展 API）；② 内置语料库 26,861 份真实书源的共识写法（统计见 `corpus.md`，实例可 `get_corpus_source` 直查）。个别站点样例（含书友社、菠萝猫）只是**实战参考**，不是标准本身；与语料共识冲突时，先怀疑样例、再查语料。

1. **能 CSS 就不 JS**。目录/详情/搜索/正文优先 CSS 选择器。语料证据：26,861 份真实书源中 **23,496 份（87.5%）chapterList 为纯 CSS**，形态如 `#chapters .direList li a` + `chapterName: text` + `chapterUrl: href`（菠萝猫 boluomao1.com 即此形态，官方 App 实测通过）。
2. **目录分页只用官方字段 `nextTocUrl`**（存在目录翻页时指向「下一页」链接，如 `.page2-chapter .page-range-list .cur + a@href`）。**禁止**在 chapterList 写巨型 `@js`，用 `for(page=2..N)` / `cp=1..N` 循环 `java.ajax`/`java.post` 一页页扒 HTML 自建章节数组——这是五次翻车的共同根因。唯一例外：目录本体由加密 API 返回（如书友社 `loadChapterPage`：AES 加密 POST 换 JSON），此时 `@js` 只负责取数据、拼 `<a href>` 片段数组，且仍必须配 `chapterName`/`chapterUrl` 字段解析。
3. **章节内分页只用 `nextContentUrl`**（文案只含「下一页」「下页」，禁止「下一章」「下一回」）。
4. **解码混淆数据**：XOR/字节场景的标准转法是 `java.base64DecodeToByteArray(s)` + `new Packages.java.lang.String(bytes, "UTF-8")`（Rhino 官方语法，菠萝猫实测可用）。语料里更主流的是 `java.base64Decode` 直接拿串、`java.createSymmetricCrypto(转换, key, iv).decryptStr(base64)`（AES 流派，70 份真实源在用，沙箱已支持）——**按页面实际混淆机制选**，不要一律套 XOR 模板。禁止：手写 UTF-8 重组、`java.bytesToStr`（真实书源仅 2/26861 在用，官方 App 未验证）、`new java.lang.String(...)`（不存在）、`String(byte[])` 直转。
5. **字符串修正**：域名替换等纯文本修正用 `.replace("www.a.com", "www.b.com")`；正则 replace 只能作用于 `String()` 包裹后的 JS 字符串，禁止对 `java.*` 裸返回值直接 `.replace(/正则/, ...)`（Rhino 报「选择不明确」）。
6. **正文清洗走 `replaceRegex`**（`##正则##`，全章合并后执行）；`content` 规则保持 `@html`/`@text` 短规则，不把整条清洗链塞进 JS。
7. **官方字段必填齐全**：`bookSourceName` `bookSourceUrl` `bookSourceGroup` `bookSourceType` `customButton` `customOrder` `eventListener` `lastUpdateTime` `respondTime` `weight` `enabledExplore` `enabledCookieJar` `bookSourceComment`（菠萝猫/书友社成品均按此清单出字段）。
8. **`searchUrl` 的 `@js:`**：以纯表达式返回 URL，或 `"url," + JSON.stringify({method,body,headers})`；不带 `return`；`key` 仅在 searchUrl 作用域注入。
9. **不存在的 API 一律不用**：`java.currentTimeMillis`、`raw.header(name)`、`java.getElements`/`java.getElement`、`java.decodeURI`、`java.utf8ToGbk`、`java.lang.reflect.Array`、`new java.lang.String`、JS `reversed()`（只有 `reverse()`）。以 `js-api.md` 沙箱清单为准。
10. **搜索外包给 rrssk 的站点（主站搜索框跳外站）一律以书友社成品为模板骨架起步**（四步链 @js、会话自管、script 加载伪装头、find 页翻页全部继承），再按 `knowledge/第三方搜索逆向实战-rrssk.md` 第 8 节清单逐项重取证站点差异；禁止在 rrssk 类站点上从零试错。模板是实战参考不是官方标准，规则对错仍以官方规则与语料共识为准。

## 必须遵守

1. 选择器只能使用证据里出现过的 tag / id / class / meta。禁止编造站点专属结构，禁止把某个小说站的写法当成所有网站的模板。
2. `nextContentUrl` 只表示**同一章的下一页**（文案是「下一页」「下页」）。禁止写成「下一章」「下一回」。没有分页就留空。
3. `nextTocUrl` 只表示目录分页，不是下一章。
4. `chapterList` 必须指向章节列表容器，不要用导航、分类、页脚链接。
5. `@css:` 只允许写在一条规则的开头一次；`||` 后面不要再写 `@css:`。优先 `id.xxx@tag.a` / `class.xxx@tag.a`。
6. 搜索规则必须来自「搜索结果页」证据。首页搜索表单只能用来写 `searchUrl`（保留 hidden 字段和 `{{key}}`）。没有结果页证据时，不要猜 `bookList`。**禁止把 ruleExplore/列表页选择器复制进 ruleSearch**——搜索结果页结构与列表页经常是两套 DOM（如列表页 `.post-book-item`、搜索页 `article.search-book-card`），必须 `fetch_page` 搜索页实测。
7. `replaceRegex` 是全部正文页合并之后的清洗，必须写成 `##正则##`；禁止贪婪 `##...$##` 从第一页吃到章末。
8. 只输出一个 JSON 对象，不要 Markdown，不要长注释。
9. 有真实正文容器时必须优先 CSS/XPath（如 `#content@html`、`id.chapterContent@text`），禁止为了“通用兜底”遍历整页最大文本块。
10. 工坊内嵌 Rhino 已知不兼容 `org.jsoup.Jsoup.parse(...)` 的直接调用；不得生成这种写法。只有证据证明必须用 JS 且 CSS/XPath 无法表达时，才允许显式 `Packages.org.jsoup.Jsoup`，并必须让脚本返回值而不是只 `java.log`。

## 验收闭环（save 前必须完成）

1. 四条链路都要被 `check_source` 实际跑过：搜索（关键词）、详情、目录、正文。书源有 `searchUrl` 时**必须显式传 searchKey** 验证搜索链路有结果；未传时 check_source 只会用「我」做兜底探测并在 warnings 标注，兜底通过不等于验收通过。
2. 搜索验收标准：「搜索」报告显示列表条数 > 0 且第一条 name/bookUrl 非空；为 0 时回到第 6 条重取证。
3. `debug_source` 的 entry 传关键词才会走搜索链路；传 URL 只验详情/目录/正文，不能替代搜索验收。

## 建议顺序

详情（书名/作者/封面/简介）→ 目录（章节列表）→ 正文（内容，必要时下一页）→ 搜索（结果列表）。站点没有的能力不要伪造。
