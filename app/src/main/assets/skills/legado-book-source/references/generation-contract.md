# 书源工坊生成合同

根据规则和真实 HTML 证据输出一个闭合的 BookSource JSON。不要假设本机有 Python 调试器或 legado 源码树。

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
