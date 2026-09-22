# 正文规则避坑（官方源码核对版）

> 依据 LegadoTeam/legado 官方源码（`model/webBook/BookContent.kt`、`model/analyzeRule/AnalyzeRule.kt`、`model/analyzeRule/AnalyzeByJSoup.kt`、`utils/HtmlFormatter.kt`）逐条核对。
> 基础 `##` 替换语义见 [`basics.md`](basics.md)，字段合同见 [`generation-contract.md`](generation-contract.md)，本文只收源码级机制与跨环境差异。

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
- 返回**恰好 1 个** URL → 串行翻页；返回**多个** → 当作完整页表**并发抓取**，不再执行翻页规则

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

同类静默坑：`select@value` 取不到值，应写 `option@value`。

## 五、坑 4：不要依赖 `@text` 多元素行为

- 官方 master：`@text` 多元素会 `joinToString("\n")` 全部返回
- 但**实测书源工坊环境只返回首个元素** → 行为随版本/环境不同
- ✅ 跨环境最稳：`@html` + JS 显式转换（同时可控地去广告、处理 `&nbsp;`）

## 六、附：HtmlFormatter 内置格式化（可直接利用）

正文规则返回后官方**自动**执行：`<p>/<br>/<div>/<h1-6>` 等标签 → `\n`、其他标签删除（保留 img）、HTML 实体反转义、每段自动加 `　　` 缩进。

→ content 直接返回 `@html` 也能读；加 JS 是为了**可控净化**。注意 `@html` 分支官方会自动移除 `script/style` 标签，但**只删标签不删内容**——`<style>` 内的 CSS 文本会漏进正文，需要整块剥离的场景（含官方 XHTML 内嵌排版 CSS）见 [`fanqie.md`](fanqie.md) 第 3.3 节的三段式顺序。

## 七、正文规则验收清单

1. 多页章节：合并字数合理（页数×每页约 2000 字），结尾与网页末页一致
2. 单页章节：不误翻页（翻页规则返回空）
3. 首章/末章：「上一章/下一章」无链接的边界不报错、不串章（重点验最后一章）
4. **真实 App 实测** nextContentUrl 的 JS（list 型 result，工坊模拟测不出来）
5. 净化规则：清理后字数 ≈ 清理前 − 广告字数（是 0 就是 `##` 前缀丢了，回 [`basics.md`](basics.md) 对）
6. 目录页含非正文章节（请假条/通知）时抽查一章验证

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
