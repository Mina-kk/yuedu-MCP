# 正文规则避坑（官方源码核对版）

> 依据 LegadoTeam/legado 官方源码（`model/webBook/BookContent.kt`、`model/webBook/BookChapterList.kt`、`model/analyzeRule/AnalyzeRule.kt`、`model/analyzeRule/AnalyzeByJSoup.kt`、`utils/HtmlFormatter.kt`）逐条核对。
> 基础 `##` 替换语义见 [`basics.md`](basics.md)，字段合同见 [`generation-contract.md`](generation-contract.md)，本文只收源码级机制与跨环境差异。目录分页 `nextTocUrl` 见第九节；卷名与无 href 条目见第十节。

## 一、正文处理管线（BookContent.analyzeContent 源码顺序）

```
1. content 规则     → analyzeRule.getString(contentRule.content)   【result 是 String】
2. HtmlFormatter.formatKeepImg()  → 内置格式化（见第五节）
3. nextContentUrl   → analyzeRule.getStringList(nextContentUrl, isUrl=true)  【result 是 List】
4. 多页合并          → contentList.joinToString("\n")
5. replaceRegex     → analyzeRule.getString(replaceRegex, 合并后全文)  【合并后才执行！】
6. title 规则
```

## 二、坑 1：JS 中 `result` 的类型因字段而异

| 字段 | 源码调用 | JS 中 `result` |
|------|---------|---------------|
| `content` | `getString()` | **String**（多元素已 joinToString） |
| `nextContentUrl` / `nextTocUrl` | `getStringList(isUrl=true)` | **Java List\<String\>** |

- 症状：`EcmaError: TypeError: 找不到函数 match`（List 没有 `.match`/`.replace`）
- ✅ 凡 list 型规则的 JS，第一句先转换，三种来源全兼容：

```js
var s = '' + result;  // String、NativeArray、Java List 均可，后续对 s 操作
```

- 附：`getStringList` 里 `if (result is String) result = result.split("\n")` —— JS 返回带 `\n` 的字符串可给出**多个**下一页 URL；`isUrl=true` 会自动转绝对 URL 并去重。

## 三、坑 2：nextContentUrl 的翻页机制与「串章」风险

官方分页循环（BookContent）自带停止条件：

- 返回 URL 为空；URL 已访问过（防死循环）；**URL == 目录中的下一章 URL** 时停止
- 但**最后一章**的"下一章 URL"会回退成第 1 章 URL，保护失效 → 翻页规则必须自己过滤（如要求 URL 含本章 bookId/章节号特征，或与 `baseUrl` 同属一本书）
- 返回**恰好 1 个** URL → 串行翻页；返回**多个** → 当作完整页表**并发抓取**，不再执行翻页规则。目录字段 `nextTocUrl` 同样按 0/1/N 分支，但没有「下一章」哨兵，见第九节
- **工坊 `--` 正文通道没有 `mNextChapterUrl`**（阅读分页用来识别「下一章」的上下文为空），无法靠「URL == 下一章」停翻；空串也不会被阅读那样解析成当前页，而是 skip 并打 `nextContentUrl <empty> (skipped)`。该通道最多跟 **10** 页，日志会写「无下一章上下文」。这是通道保护，与阅读差异不要用沙箱结论改规则；真机以阅读日志为准

按链接文本匹配「下一页」的写法。**首选 CSS**（`@CSS:` 支持 `:contains`）：

```
@CSS:.prenext a:contains(下一页)@href
```

仅当「下一页」不是标准 `<a>` 文本（需二次过滤）才用 JS 正则（注意 `'' + result` 转换，list 型 result 第一句先转字符串）：

```
class.prenext@html<js>var s=''+result;var m=s.match(/href="([^"]+)"[^>]*>\s*下一页/);m?m[1]:''</js>
```

`nextContentUrl` 只表示**同一章的下一页**（文案「下一页」「下页」），禁止写成「下一章」「下一回」；`prevContentUrl`（上一页）官方已废弃，禁用。完整字段合同见 [`generation-contract.md`](generation-contract.md) 第 2 条。

## 四、坑 3：CSS 简写模式不支持 `:contains()`

源码（AnalyzeByJSoup.ElementsSingle）：简写 `tag.a:contains(x)` 会把 `a:contains(x)` 整体当**标签名**查 → 静默匹配为空。

三条可行替代（按推荐顺序）：

- `@CSS:` 前缀走纯 JSoup（支持 `:contains`，**首选**）：`@CSS:.prenext a:contains(下一页)@href`
- 简写独有 `text.xxx`（= getElementsContainingOwnText）：`class.prenext@text.下一页@href`
- JS 正则（兜底）：仅当锚文本不规范或需二次过滤时使用

同类静默坑：`select@value` 取不到值，应写 `option@value`。`option@value` 用在 `nextTocUrl` 上返回多条页链是合法语义（阅读当完整页表并发抓），见第九节。

## 五、坑 4：不要依赖 `@text` 多元素行为

- 官方 master：`@text` 多元素会 `joinToString("\n")` 全部返回
- 但**实测书源工坊环境只返回首个元素** → 行为随版本/环境不同
- ✅ 跨环境最稳：`@html` + JS 显式转换（同时可控地去广告、处理 `&nbsp;`）

## 六、附：HtmlFormatter 内置格式化（可直接利用）

正文规则返回后官方**自动**执行：`<p>/<br>/<div>/<h1-6>` 等标签 → `\n`、其他标签删除（保留 img）、HTML 实体反转义、每段自动加 `　　` 缩进。

→ content 直接返回 `@html` 也能读；加 JS 是为了**可控净化**。注意 `@html` 分支官方会自动移除 `script/style` 标签，但**只删标签不删内容**——`<style>` 内的 CSS 文本会漏进正文，需要整块剥离的场景（含官方 XHTML 内嵌排版 CSS）见 [`fanqie.md`](fanqie.md) 第 3.3 节的三段式顺序。
>
> **`replaceRegex` 作用在这段纯文本上，不是 `@html` 返回的 HTML。** 官方顺序是 content 规则 → `HtmlFormatter.formatKeepImg()`（块标签变行、去其它标签、保留 img、实体反转义）→ 逐行 trim → 多页合并 → 才执行 `replaceRegex`。写成 `##<div class="ad">.*?</div>##` 这种 HTML 形态在真机上对不上，因为标签已被剥掉；应对纯文本写（如 `##广告尾巴##`）。工坊 `--` 正文通道按同一顺序：先 formatKeepImg + 逐行 trim，再 replaceRegex。

## 七、正文规则验收清单

1. 多页章节：合并字数合理（页数×每页约 2000 字），结尾与网页末页一致
2. 单页章节：不误翻页（翻页规则返回空）
3. 首章/末章：「上一章/下一章」无链接的边界不报错、不串章（重点验最后一章）
4. **真实 App 实测** nextContentUrl 的 JS（list 型 result，工坊模拟测不出来）
5. 净化规则：清理后字数 ≈ 清理前 − 广告字数（是 0 就是 `##` 前缀丢了，回 [`basics.md`](basics.md) 对）
6. 目录页含非正文章节（请假条/通知）时抽查一章验证
7. 目录含卷名时抽查真正可读章节，不要用无 href 的卷名占位地址当正文入口（见第十节）
8. 正文 JSON/Ajax：站点 403/520、MCP `TIMEOUT`、连接中断与规则取空分开记；阅读端未覆盖的阶段不得写成已通过

## 八、参考模板（多页正文，源码核对版）

```json
"ruleContent": {
  "content": ".con@html",
  "nextContentUrl": "@CSS:.prenext a:contains(下一页)@href",
  "replaceRegex": "本章完[^\\n]*##"
}
```

净化规则写在 `replaceRegex`（`##正则##`，全章合并后执行，多个规则换行分隔）；`<p>/<br>` 转行、去标签、`&nbsp;` 反转义官方 HtmlFormatter 已自动完成（见第六节），不要复刻进 JS。
仅当确需整块剥离 `<style>` 等 HtmlFormatter 不处理的残留时，才在 content 里挂一小段 `<js>` 净化（见 [`fanqie.md`](fanqie.md) 第 3.3 节三段式），并注明原因。

## 九、目录分页 nextTocUrl（BookChapterList 源码核对）

阅读 `BookChapterList.analyzeChapterList` 对 `tocRule.nextTocUrl` 调用 `analyzeRule.getStringList(nextTocRule, isUrl=true)`（与 `nextContentUrl` 同一取值函数，链式 `@js` 的 `result` 是 List，见第二节）。当前页 URL 会从列表中去掉。得到的列表按长度分支（官方约 68–119 行）：

| 列表长度 | 阅读端行为 |
|---|---|
| 0 | 结束，只保留当前页章节 |
| 1 | 串行 `while`：请求该 URL，解析章节后再取该页的 `nextTocUrl`，直到空或已访问 |
| N>1 | 日志「并发解析目录」；按 `AppConfig.threadCount` 并发抓取这一整表；二级页传入 `getNextUrl=false`，不再执行 `nextTocUrl` |

因此页码 `<select>` 写 CSS `option@value`、或一次取出全部页链，都是**合法规则**：阅读把 N>1 当完整页表并发抓。不要为了「看起来像下一页」改成只取一条，也不要在 `chapterList` 里循环 `java.ajax` 自建数组。`select@value` 取不到值，应写 `option@value`（见第四节）。

工坊 `debug_source` / `check_source` 的 `++` 目录通道已按同一 0/1/N 列表语义展开，**不会**把多条 URL 拼成一个地址。与阅读的差异：

- N>1 时工坊**串行**一层展开，不是阅读的 `mapAsync` 并发
- 工坊总请求（首页 + 链式或一层展开）上限 **100** 页；阅读无此硬顶。达到上限时 `data.tocDiagnostics` 记 `endReason=page_limit`/`truncated=true`，日志写「目录分页达到上限」；证据层标 sandbox/采集不完整，`verdict` 不得为 `full`，不算书源规则失败。未达上限（如 28 页页表）仍按完整采集。工坊另按非空、已 resolve 的章节 URL 有界去重（近似 `BookChapterList.kt:129` 的 `LinkedHashSet`；官方 equals/hashCode 未核实，目录总数不保证与阅读一致）。首页与 `-1` 别名同章在沙箱只计一次
- 工坊空串 skip（与 `--` 正文通道一致）；阅读 `isUrl=true` 会把空串解析成当前页

这些是通道保护，**不是改规则依据**。若某个辅助环境仍把 `option@value` 拼成超长 URL 并返回 400，属于该工具缺陷：保留原规则，用阅读端 `++目录URL` 取证。完整字段合同见 [`generation-contract.md`](generation-contract.md)。

## 十、目录卷名与无 href 条目（BookChapterList 源码核对）

阅读解析 `chapterList` 每条时（官方 `BookChapterList` 约 237–287 行）同时取 `chapterName`、`chapterUrl`、`isVolume`：

1. `isVolume` 规则 `isTrue()` → 该条是卷名，不是正文章节。
2. url 为空且是卷名 → `url = title + index`，日志「一级目录未获取到url,使用标题替代」。这是阅读内部占位，**不是**可抓正文的地址。
3. url 为空且不是卷名 → `url = baseUrl`，日志「未获取到url,使用baseUrl替代」。
4. title 为空 → 不加入 `chapterList`。

目录验收在列表里找真正可读章节（非空、可请求的 `chapterUrl`），不要用第一条（常是卷名）当正文入口。工坊沙箱可能把卷名 url 留空、后面章节 url 正常；不要为消除「首项 url 为空」给卷名伪造 href，也不要求工坊产出与阅读相同的 `title+index` 字符串。全部条目都没有有效章节地址才算目录规则失败。

正文 JSON/Ajax 取证时，站点 403/520、MCP `TIMEOUT`、连接 `CONNECTION_LOST` 与规则失败要分开记；通道超时或盾/源站故障不是改 `content` 选择器的依据。阅读端未覆盖的阶段不得写成已通过。源码级分页见第九节；字段合同见 [`generation-contract.md`](generation-contract.md)。
