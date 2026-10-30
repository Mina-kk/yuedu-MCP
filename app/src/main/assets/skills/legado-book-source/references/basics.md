# 规则语法详解

> Legado 默认使用 Jsoup 语法。基础选择器表见 `template.yaml`，本文档覆盖 @ 分隔符、数组写法、JS 规则基础。

## @ 分隔符语法（**核心规则**）

`@` 为规则分隔符，每段规则可分为 3 段：`类型@名称@位置`。

| 段 | 说明 |
|----|------|
| 第一段 - 类型 | `class`, `id`, `tag`, `text`, `children` 等。`children` 获取所有子标签，不需要第二段和第三段。`text` 可根据文本内容获取元素 |
| 第二段 - 名称 | 如 class 名、tag 名、text 内容的一部分 |
| 第三段 - 位置 | class/tag/id 会匹配多个，需指定位置。正序从 0 开始，负数为倒序（-1=倒数第1个）。不加位置则获取所有匹配项。`!` 表示排除，格式 `!0:1:3` 排除第1、2、4个 |

最后一段为获取内容：`text`, `textNodes`, `ownText`, `href`, `src`, `html`, `all` 等。

正则替换：末尾加 `##正则##替换内容`，替换内容为空时第二个 `##` 可省略。

## 不要认错：字段 `##` vs 正文 `replaceRegex`

阅读官方语义（`AnalyzeRule` + `BookContent`）必须分开记，工坊调试也按这个跑，避免「高级调试看起来洗干净了，真源却对不上」。

| 位置 | 时机 | 写法 | 含义 |
|---|---|---|---|
| 任意字段末尾 `selector##regex##repl` | **先提取该字段，再替换** | `h1@text##广告-##` | 只清洗这一个字段 |
| `ruleContent.replaceRegex` | **全部正文页合并之后**，再当一条 `getString` 规则跑 | 通常写成 `##regex##` 或 `##regex##repl` | 清洗整章，不是逐页 |
| `##regex##repl###` 或第四段 `##` | replaceFirst | `##\\d+##$0###` | 只取**第一个匹配**；匹配失败得空串，不是原文 |

关键认错点：

1. `replaceRegex` 不要写成 CSS 选择器。少写开头的 `##` 时，阅读会把正文当 HTML 再提取一次，结果可能被掏空。
2. `##...$##` 这种锚定到文末的贪婪规则，在合并后会从**第一页的匹配点吃到整章结尾**。调试器若逐页净化会「看起来成功」，真源会丢掉后续页。分页广告用非贪婪 `##<div class="tail">[\\s\\S]*?</div>##`，或按实际节点写。
3. `@js:...` 会吃到规则结尾，后面的 `##` 是替换而不是 JS 字符串。JS 里要写 `##` 时用 `<js>...</js>`，并避免在 JS 正文里直接出现 `##`。
4. 列表规则（`bookList` / `chapterList`）即使写了 `##`，官方 `getElements` **不会**做字段替换。

多规则组合：`||` 表示或（任一匹配即可），`&&` 表示与（都要匹配）。

示例：

```
class.odd.0@tag.a.0@text
tag.dd.0@tag.h1@text##全文阅读
class.odd.0@tag.a.0@text||tag.dd.0@tag.h1@text##全文阅读
```

## 数组/区间写法

| 写法 | 说明 |
|------|------|
| `[index]` | 取指定索引，如 `[0]`, `[-1]` |
| `[!index,...]` | 排除指定索引，如 `[!0:2]` |
| `[start:end]` | 区间，start 为 0 或 end 为 -1 时可省略，如 `[:3]`, `[-2:]` |
| `[start:end:step]` | 带步长的区间，如 `[::2]`（每隔1个取1个） |

索引、区间两端、步长均支持负数。

`head@.1@text` ≡ `head@[1]@text` ≡ `head@children[1]@text`

## 列表倒置

规则最前面加负号 `-` 可使列表倒置，适用于目录倒序的网站。例：`-tag.dd` 获取所有 dd 标签并倒置顺序。区间反向：`tag.div[-1:0]` 也可实现反向。

## JS 规则注意

- `result` 是 Java 对象，字符串操作前必须 `String(result)` 转换
- 工坊生成规则优先 CSS/XPath，不要用 JS 遍历整页猜正文。
- 必须在 JS 中解析 HTML 时使用：`var doc = Packages.org.jsoup.Jsoup.parse(String(result));`（等价于 `org.jsoup.Jsoup.parse(...)`，官方 App 两者均可用；仅 Studio 沙箱内直调 `org.jsoup.Jsoup.parse` 会报「parse 不是函数」，沙箱内优先 `java.getString`/`@css:`）。
- 选择元素：`var element = doc.select("selector").first();`
- 获取文本：`var text = String(element.text());`
- 打印日志：`java.log("msg")`
- Legado 重写了 `java` 变量，原生 Java 用 `Packages.java.*`
- 前置规则: `<js>` 前可写其他规则，JS 中 `result` 为前置规则的结果
  - 例: `pre@html<js>处理逻辑</js>` 其中 result 为 `pre@html` 解析后的 HTML 内容

## 规则类型标志

`{{......}}` 内使用规则必须有明显的规则标志，没有规则标志当作 js 执行。

| 标志 | 说明 |
|------|------|
| `@@` | 默认规则，直接写时可以省略 `@@` |
| `@XPath:` | XPath 规则，直接写时以 `//` 开头可省略 `@XPath` |
| `@Json:` | JSON 规则，直接写时以 `$.` 开头可省略 `@Json` |
| `:` | 正则规则，不可省略，只可用在书籍列表和目录列表 |
| `<js>...</js>` | JS 包裹标签，可在任意位置插入 JS 代码，也可作规则分隔符。例: `tag.li<js></js>//a` 或 `pre@html<js>处理逻辑</js>` |

## URL 变量

| 变量 | 说明 |
|------|------|
| `{{page}}` | 阅读内置分页变量，从 1 开始。**不是**搜索/发现 URL 的必填项。只有抓到真实后一页、且该书单与第一页不同，才写入；假分页（后一页相同、空列表、或站点根本不分页）不要写。第一页不带页参数时可用 `<,{{page}}>` 或 `{{page - 1 == 0 ? "": page}}` |
| `{{key}}` | 阅读内置搜索关键词变量，搜索 URL **必须包含**，发现页带输入框时使用 |
| `{{xxx}}` | 自定义变量，需通过 JS 定义，如 `source.put('bookId', '123')` |

搜索 URL 例（已核实后一页书单不同）：`https://example.com/search?q={{key}}&page={{page}}`

搜索 URL 例（无真实分页，不要硬加 page）：`https://example.com/search?q={{key}}`

发现 URL 例（已核实分页）：`https://example.com/top?page={{page}}`

核实方法：固定同一关键词/分类，分别取第一页与后一页的书籍列表，只有集合不同才算真分页。不要凭「URL 里有 page 参数」或页码按钮外观写入 `{{page}}`。沙箱缺页、超时不是改这条规则的依据，以阅读端搜索/发现实跑为准。

## URL 选项（常用）

URL 末尾以 `,{JSON}` 追加，格式如 `https://example.com/page,{"webView":true}`。完整列表见 `references/js-api.md`。

| 选项 | 说明 |
|------|------|
| `webView: true/false` | 是否使用 WebView 渲染页面（JS 动态渲染的网站必开） |
| `method: POST/GET/HEAD` | 请求方法，默认 GET |
| `charset: string` | 编码，如 utf-8、gbk |
| `header: {key:val}` | 自定义请求头 |
| `body: string` | POST 请求体 |

## 目录下一页（nextTocUrl）

阅读对 `nextTocUrl` 走 `getStringList(..., isUrl=true)`，并去掉等于当前页的地址，再按返回 URL **条数**分支（与正文 `nextContentUrl` 同构；目录并发页不再递归取下一页）：

| 条数 | 阅读端行为 |
|---|---|
| 0 | 不分页，只用当前页章节 |
| 1 | 串行跟随该 URL，直到空或已访问 |
| 大于 1 | 把返回列表当完整页表**并发**抓取；这些页上不再跑 `nextTocUrl` |

页码下拉用 `option@value`（不是 `select@value`）可一次给出全部页 URL，阅读端按「大于 1」并发。`getStringList` 遇到带换行的字符串会按行拆成多条 URL。不要把多条 URL 拼成一条请求地址。

大于 1 时，第一页的 `nextTocUrl` 必须给出**完整页表**；并发页不会再翻页。验收以阅读端 `++目录URL` 日志为准：完整终止、页数与章节数、是否并发。沙箱条数/拼接差异登记为 `sandbox_divergent`，不为迁就沙箱改规则。沙箱不作规则标准。

## 目录卷名与无链接条目（isVolume）

阅读 `BookChapterList` 解析每条目录时（官方约 237–287 行）先取 `chapterName` / `chapterUrl` / `isVolume`。`isVolume` 规则为真时该条是卷名，常见于无 `href` 的分组标题。随后：

| 条件 | 阅读端行为 |
|---|---|
| `chapterUrl` 空且 `isVolume` 为真 | 用 `title + index` 填 url（日志「一级目录未获取到url,使用标题替代」）。这是阅读内部占位，**不是**可请求的章节地址 |
| `chapterUrl` 空且不是卷名 | 退回当前页 `baseUrl`（日志「未获取到url,使用baseUrl替代」） |
| `chapterName` 空 | **不加入**目录列表 |

目录验收要找真正可读章节（有有效 `chapterUrl` 的条目），不要把卷名占位地址当 `--章节URL`，也不要为迁就沙箱给卷名伪造 href。工坊不必伪装阅读那套 `title+index` url；沙箱把卷名 url 留空、后面章节 url 正常，仍以是否存在可读章节为准。若全部条目都没有有效章节地址，目录仍失败。
