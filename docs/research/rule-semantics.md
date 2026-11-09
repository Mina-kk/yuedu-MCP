# 书源规则语义研究报告（yuedu-MCP v2 沙箱重构 · 阶段一：只读研究）

> 范围：仅比较规则解析语义，不含 UI。所有结论来自 `research/yuedu-refactor/<owner__repo>/src` 下的固定 commit 快照（Git blob SHA 已校验），未执行任何网上书源 JS，未访问 apikey-vault。
> 本项目侧只读引用 `app/src/main/java/io/legado/app/model/analyzeRule/`、`com/mina/legadostudio/runtime/`、`app/src/test`。

## 0. 证据基线与可信度分级

| 分支 | 固定 commit | commit 日期 | 规则文件位置 | 可信度 |
|---|---|---|---|---|
| LegadoTeam/legado | `330e606c4d57e08adc056ff8ab7a4132c784da9a` | 2026-11-01 | `app/src/main/java/io/legado/app/model/analyzeRule/` | 主基线（规则引擎最全：`SourceHtmlParser`、`ReviewRuleParser`、`AnalyzeUrlNetworkOptions`） |
| HapeLee/legado-with-MD3 | `2e2c63c3a7d2786719919fd0b11a197af9cf29bd` | 2026-11-08 | 同上 | 高（与 Team 同源，差异仅 `parseSourceHtml` 与少量 Kotlin 惯用改写） |
| Suml-1/Legado_Max | `b3864087588ba624cb376e9f04772be2b6385656` | 2026-11-07 | 同上 | 高（`AnalyzeRule` 增调试埋点 + Rhino 变体；`AnalyzeByJSoup` 回退到旧 `clear()` 写法） |
| Rimchars/legado | `e111bf666199505d783e6910d8609f0e5cb043e0` | 2026-09-24 | 同上 | 中（规则文件与 Max 同 blob，`AnalyzeRule` 为精简版） |
| joestar817/legado_NG | `7b6087410f287511080f2a93b82795231f4b72fd` | 2026-11-05 | 同上 | 中高（`AnalyzeByJSoup/XPath/JSonPath/Regex` 与 Max 同 blob；`AnalyzeRule` 去掉 `localBindings`、改 cookie 作用域） |
| skybbk1001/legadoT | `b0c6067996a9467ed35c32b4de69171979a284de` | 2026-11-05 | `shared/src/commonMain/kotlin/.../analyzeRule/` | 中（KMP 化，`AnalyzeByJSoup` 回到旧 `clear()` 语义） |
| huajideshutiao/legado | `83657e54829b7603aa392bf072283805769ad385` | 2026-11-02 | `data/src/commonMain/kotlin/.../analyzeRule/` | 中高（Ksoup + rjpath + `selectXpath` 重写；`RuleCombiner` 抽出；正则 3s 超时） |
| Kototoro-app/Kototoro | `7ca2734fba7b830d8008012e4b2de1a052c4bc43` | 2026-11-09 | `app/src/main/kotlin/org/skepsun/kototoro/core/parser/legado/` | 参考（独立重写，非 legado 引擎；只作行为交叉验证） |
| CCSSNE/legadoC | `b3cb9f5405112d5ae1b99705bf62886b81a9d5f4`（own） | 2026-10-30 | — | **不作为基线**：默认分支 `own` 可能落后公开版，未取得公开主线 |
| Luoyacheng/legado-E | `8b87c5aba4df91c39a3a0939a68a1180b9f2ee1c` | 2026-08-01 | — | **仅历史参考**（100 天未更新） |
| mgz0227/legado-Harmony | `7419807075b5e8ab33320ac06f36dac51d5da707` | 2026-10-26 | 快照中无 `analyzeRule` 引擎源码 | **不可用于兼容性推断**，不得虚构其实现 |

关键事实：`RuleAnalyzer.kt` 在 Team / HapeLee / Max / Rimchars / NG / T 六个分支中是**同一 blob**（`5fd91e15d0`，14477 字节），只有 huajideshutiao 重写（`99cbadf666`，12565 字节）。因此"规则切分器"这一层可以直接移植 Team 版本，跨分支无分歧。

固定 commit 链接前缀：
`https://github.com/<owner>/<repo>/blob/<commit>/`

---

## 1. 上游真实语义（以 LegadoTeam commit `330e606` 为准，逐条给出源码行）

### 1.1 规则串的总编排：`AnalyzeRule.splitSourceRule` → `SourceRule`

1. **`<js>` / `@js:` 先切段**（`AnalyzeRule.kt:607-617`）：`JS_PATTERN` 匹配 `<js>([\s\S]*?)</js>|@js:([\s\S]*)`（大小写不敏感）。`@js:` 之后**整段**都是 JS，不再参与后续切分。段间非 JS 部分 trim 后非空才入列。
2. **`@webJs:` 再切段**（`:618-628`），模式 `WebJs`。
3. **每段构造 `SourceRule`**（`:647-739`）：
   - `mode == Js/Regex` 时规则串原样保留；否则按前缀判定：`@CSS:`→`Default`（**保留前缀**，`:665-668`）、`@@`→`Default`（剥 2 字符）、`@XPath:`→XPath、`@Json:`→Json、`isJSON || $. || $[`→Json、`/` 开头→XPath。
   - `isJSON` 是**整个 content 的属性**（`:107-110`：`content is Node ? false : content.toString().isJson()`），不是逐段判定。
   - 然后 `splitPutRule` 抽 `@put:{...}`（`:516-539`，正则 `@put:(\{[^}]+?\})`，**不允许嵌套花括号**）。
   - 然后 `evalPattern`（`@get:\{[^}]+?\}|\{\{[\w\W]*?\}\}`，`:1028-1029`）切 `@get:`/`{{}}`，剩余片段进 `splitRegex`。
   - **`$1`/`$2` 分组引用**（`:744-771`，正则 `\$\d{1,2}`）：命中则把该段 mode 提升为 **Regex**（`AnalyzeByRegex` 多级正则），除非已是 Js/Regex。
4. **`makeUpRule`**（`:776-835`）**每次求值前**重算：倒序回填 `$n`（从 `result` 列表取第 n 项）、`{{js}}`（`isRule()` 判定为规则则递归 `getString`，否则 `evalJS`）、`@get:{key}`（`get(key)` 查变量池）；再按 `##` 切出 `rule / replaceRegex / replacement / replaceFirst`（第 4 段存在即 replaceFirst）。

### 1.2 `getString` / `getStringList` / `getElements` 的返回契约

| 入口 | 上游行为 | 行号 |
|---|---|---|
| `getStringList` | 结果若是 `String` 则 **`split("\n")`**；`isUrl=true` 时转绝对 URL 并去重；否则 `as? List<String>` | `AnalyzeRule.kt:278-295` |
| `getString` | 结果 `toString()`；`unescape=true` 且含 `&` 时 `StringEscapeUtils.unescapeHtml4`；`isUrl=true` 时**空串返回 `baseUrl`**，否则转绝对 URL | `:367-381` |
| `getElements` | 结果 `List`→过滤 null/`Scriptable.NOT_FOUND`；`Array`/`NativeArray`→逐项；其余→**空 `ArrayList()`** | `:452-502` |
| `getElement` | 单值路径：Regex 走 `AnalyzeByRegex.getElement`（返回含 group0 的列表），Json 走 `getObject`，XPath/Default 走 `getElements` | `:387-417` |
| 链式 `@js` 的 `result` | `getString` 链里是上一段的结果对象；`getStringList` 链里是上一段列表 | `:249-275` / `:341-364` |
| `NativeObject`（JS 对象）特例 | 首个规则段**只取一次**，键值直接访问 `result[rule]`，不走后续 mode 分派；`LinkedTreeMap` 同 | `:221-247`、`:325-340` |

**`getString` 与 `getStringList` 在 JS 对象上的分派不同**：Team 版 `getStringList` 对 `NativeObject` 有 `Mode.Js`/`Mode.Json` 分支（`:225-235`），`getString` 也有（`:329-336`）；而 Max / NG / T / huaji 已删除这两个分支（见 §3.6）。

### 1.3 CSS / 默认 Legado 选择器（`AnalyzeByJSoup`）

- **`@CSS:` 前缀**：`SourceRule` 保留前缀（`AnalyzeRule.kt:665-668`），`AnalyzeByJSoup.SourceRule`（`:509-517`）剥 5 字符并置 `isCss`。
- **CSS 分支不做 `&& || %%` 之外的任何 Legado 语法**：`element.select(ruleStrX.take(lastIndex))`，`lastIndex = ruleStrX.lastIndexOf('@')`（`:91-95`）。**注意**：`@CSS:` 段里 `@` 是"最后一段属性名"分隔符，CSS 选择器中出现 `@`（如属性选择器 `[data-x@y]`）会被误切。
- **`@CSS:` 段内不再识别 `@css:` 子前缀**：`getStringList` 只在 `sourceRule.isCss` 为真时走 CSS；`getElements` 同理（`:138-145`）。**`&&`/`||` 分支内再写 `@css:` 不会被剥除**，会被当作 CSS 选择器字面量传给 jsoup。
- **默认模式（无前缀）**：`getResultList`（`:196-219`）先 `RuleAnalyzer.trim()` 去前导 `@`/空白，再按 `@` 切段，逐段 `ElementsSingle().getElementsSingle`，最后一段是"取值器"。
- **取值器全集**（`getResultLast`，`:224-275`）：`text`、`textNodes`（每个元素内文本节点 trim 后 `\n` 连接）、`ownText`、`html`（**会从 DOM 里 remove 掉 script/style**）、`all`（`outerHtml`）、其他一律当**属性名**，`attr()` 取值，**空串与重复值被丢弃**（`:267-271`）。
- **索引语法**（`ElementsSingle`，`:288-506`）：
  - 旧写法 `tag.div.-1:10:2`、`tag.div!0:3`：`:` 分隔索引，`!` 排除、`.` 选择。
  - 新写法 `tag.div[-1, 3:-2:-10, 2]`、`[!it,...]`、`[-1:0]` 反向。
  - 负索引 `it + len`；区间两端越界钳制；`stepX >= len` 退化为只取首位；负 step 变 `stepX + len`（`:354-365`）。
  - **索引集合是 `mutableSetOf`**（`:321`）：重复索引只保留一次，**顺序按插入序**，不是按原列表顺序。
  - 排除 `!`：Team 用 `elements.deselect(pcInt)` 倒序删除（`:381-385`），不改源 DOM；Max/Rimchars/NG/T 用 `elements[pcInt] = null; removeAll(nullSet)`（Max `:385-389`），**会写 null 进列表**，对 `Elements.outerHtml()` 等有副作用差异。
  - 前缀识别（`:309-316`）：`children` / `class.X` / `tag.X` / `id.X` / `text.X`，其余 `temp.select(beforeRule)`。
- **`&&` / `||` / `%%` 组合**（`:83-121`）：
  - `||`：**第一个非空即 break**，不合并。
  - `&&`：顺序拼接（`addAll`）。
  - `%%`：**按第一段的下标交错**（`for i in results[0].indices { for temp in results { if (i < temp.size) add(temp[i]) } }`）——注意是"以第一段长度为准"，第一段更长时后面的段被截断，第一段更短时后面的段尾部被丢弃。
  - `elementsType` 是 `splitRule` 的**最后一个**分隔符（`RuleAnalyzer.splitRule` 只设一次 `elementsType`，`RuleAnalyzer.kt:167-175`）。混写 `a&&b||c` 时类型是 `||`，但切分按 `&&` 与 `||` 都切（`consumeToAny`），上游行为是"切完再统一按最后类型合并"。
- **`RuleAnalyzer.splitRule` 的平衡组**（`RuleAnalyzer.kt:131-159` / `:91-126`）：默认 `chompRuleBalanced`（引号内转义无效）；`code=true` 时 `chompCodeBalanced`（引号内 `\` 转义有效，且额外平衡 `[]`）。`[` `(` 视为"筛选器起点"，用于避免在 `[attr='a&&b']` 里切分。
- **空规则**：`elementsRule.isEmpty()` 时返回 `element.data()`（`:79`，Team 加了 `?: ""`）。

### 1.4 XPath（`AnalyzeByXPath`）

- 前缀 `/` 或 `@XPath:`；`@XPath:` 剥 7 字符。
- `parse`（`:17-41`）：`JXNode`→元素或重新解析；`Document`/`Element`/`Elements`→`JXDocument`；字符串：`</td>` 结尾包 `<tr>`、`</tr>`/`</tbody>` 结尾包 `<table>`、`<?xml` 走 XML parser。
- `getStringList`（`:92-131`）：`getResult(xPath)?.map { it.asString() }`。`getString`（`:133-154`）：`TextUtils.join("\n", result)`。
- **XPath 分支支持 `&& || %%`**，但 `getString` 只切 `&& ||`（`:135`），**不切 `%%`**。
- **XPath 分支不支持 `@` 链式**，也不支持 `@get:`/`$n`（这些在 `SourceRule` 层已处理，但 XPath 段内部不再切 `@`）。
- **`/` 开头判定发生在 `@CSS:`/`@XPath:` 之后**（`AnalyzeRule.kt:690-693`），所以 `@css:` 段不会被误判为 XPath。

### 1.5 JSONPath（`AnalyzeByJSonPath`）

- 前缀 `$.`/`$[`/`@Json:`，或**整个 content 是 JSON**（`isJSON`）。
- `getString`（`:34-71`）：先 `RuleAnalyzer(rule, true)`（code 平衡）切 `&& ||`；单段时先 `innerRule("{$.")` 替换内嵌 `{$.rule}`，再 `ctx.read`；`List` 结果 `joinToString("\n")`。
- `getStringList`（`:74-125`）：同上但 `%%` 也切；`List` 逐项 `toString()`。
- `getList`（`:132-172`）：`ctx.read<List<Any>>`，`%%` 交错合并。
- **`getString` 不切 `%%`**（`:37`），`getStringList` 切（`:77`）。
- **`{$.rule}` 内嵌**只在 `getString`/`getStringList` 单段路径处理，`getList` **不处理**（`:136-143` 直接 `ctx.read(rules[0])`）。
- `onError` 回调是 Team 新增（`:11-13`、`:55`、`:92`、`:141`），HapeLee/Max/Rimchars/NG 只有 `printOnDebug`。

### 1.6 正则（`AnalyzeByRegex`）

- 触发：规则首字符 `:`（`splitSourceRule(allInOne=true)`，`AnalyzeRule.kt:599-602`，且 `isRegex` 一旦置位对后续段生效 `:603-605`）；或段内出现 `$n`；或 `evalPattern` 命中且前文不含 `##`。
- `AnalyzeByRegex.getElements`（`:32-59`）：多级正则，**每一级把所有匹配 `append` 成一个串**再喂下一级（不是逐条匹配）；末级返回 `List<List<String>>`，第 0 项是 `group(0)`，未参与分组为 `""`。
- `getElement`（`:9-30`）：只取**第一个**匹配，`group(n)!!` **未参与分组会抛 NPE**（huaji 版本改为 `groupValueOrThrow` 对齐该行为，`:23-27`）。
- **`:` 前缀与 CSS 伪类冲突**：上游注释明说"其实 `:` 与伪类选择器冲突"（`:598`）。

### 1.7 `##` 替换（`AnalyzeRule.replaceRegex`，`:544-568`）

- `##match##replace`：`result.replace(regex, replacement)`（**全部替换**），正则编译失败则退化为**字面量替换**。
- `##match##replace###`（第 4 段）：取**第一个匹配的 `group(0)`**，对其 `replaceFirst`；**无匹配返回 `""`**（不是原串）。
- 替换串里的 `$1`/`$0` 由 Kotlin `Regex.replace` 解释为分组引用。
- **`replaceRegex` 在 `makeUpRule` 里从 `ruleStrS[1]` 取，不做 trim**；`rule` 本体 trim（`:824-834`）。

### 1.8 `@put` / `@get` / `{{}}`

- `@put:{key:rule,...}`：正则 `@put:(\{[^}]+?\})`，**JSON 值必须是字符串**（`Map<String,String>`），**不允许嵌套对象**；解析失败用宽松 GSON 再试一次（`:516-539`）。执行时机：**每个规则段求值前** `putRule(sourceRule.putMap)`（`:249-250`），值用 `getString(value)` 求。
- `@get:{key}`：`get(key)` 查 `localBindings` → `bookName`/`title` 特例 → `chapter` → `book` → `ruleData` → `source`，**空串视为未命中**（`:875-891`）。
- `{{expr}}`：`isRule()`（`@`/`$.`/`$[`/`//` 开头）→ 当规则递归求值；否则 `evalJS`。数值 `Double` 整数时格式化为 `%.0f`（`:794-812`）。
- **`@put`/`@get`/`{{}}` 只存在于非 JS 段**：`mode == Js || mode == Regex` 时 `init` 直接返回原串（`:663-664`），不解析 `@put`。

### 1.9 JS 绑定（`evalJS`，`:896-936`）

`java`（= `AnalyzeRule` 自身，含 `ajax/get/post/...`）、`cookie`、`cache`、`source`、`book`、`result`、`baseUrl`、`chapter`、`chapters`、`title`、`src`、`nextChapterUrl`、`rssArticle`、`fromBookInfo`、`paraIndex`/`paraData`/`page`（来自 `localBindings`）。作用域按书源共享（`getShareScope`）。

---

## 2. 本项目现状（只读定位）

| 文件 | 行 | 实现 |
|---|---|---|
| `analyzeRule/RuleAnalyzer.kt` | 全文件 | **与上游同一份**（378 行，`splitRule`/`innerRule`/`chompBalanced` 一致） |
| `analyzeRule/AnalyzeByJSoup.kt` | 68-122 | `getStringList`：支持 `@css:` 子前缀（`:90`、`:138`），`selectCss` 包 try/catch（`:513-521`） |
| | 127-174 | `getElements`：`sourceRule.isCss \|\| ruleStrS.any { @css: }`（`:138`） |
| | 179-203 | `getResultList`：`elements.clear()`（`:199`）——**与 Max/Rimchars/NG/T 同款旧写法** |
| | 273-492 | `ElementsSingle`：`removeAt` 倒序删除（`:370`），带注释"Upstream assigns null then removes it" |
| | 523-531 | `SourceRule`：只认 `@CSS:` 前缀，**不认 `@css:` 子前缀**（子前缀在 `getStringList` 层处理） |
| `analyzeRule/AnalyzeByXPath.kt` | 27-41 | `strToJXDocument`：**无 `parseSourceHtml`**（直接 `JXDocument.create(html1)`） |
| | 133-154 | `getString` 只切 `&& \|\|` |
| `analyzeRule/AnalyzeByJSonPath.kt` | 33-71 | `getString` 切 `&& \|\|`，`innerRule("{$. ")` |
| | 73-125 | `getStringList` 切 `&& \|\| %%` |
| | 131-172 | `getList`：**不处理 `{$.rule}` 内嵌** |
| `analyzeRule/AnalyzeByRegex.kt` | 9-30 | `getElement`：`group(groupIndex)!!` |
| | 32-59 | `getElements`：多级 append |
| `analyzeRule/LegadoRuleEngine.kt` | 22-36 | `extract`：**`-` 前缀反转列表**（`:23-24`、`:34`）；`-` 判定 `!startsWith("--")` |
| | 38-63 | `elements`/`elementList`：`-` 反转；CSS→`Element`，XPath→`JXNode`，JSON→原始对象 |
| | 94-99 | `detect(rule, content)`：JSON content 时无前缀→JSON_PATH |
| | 107-123 | `detect(rule)`：`@CSS:`/`@XPath:`/`@Json:`/`:`/`@@`/`/`/`$.`/`$[` |
| | 125-130 | `normalize`：**`removePrefix` 大小写敏感**（`@XPath:` 与 `@xpath:` 各写一次，`@CSS:` 不剥） |
| `runtime/LegadoStringRule.kt` | 12 | `jsPattern`：`<js>...</js>\|@js:([\s\S]*)` |
| | 44-59 | `parsePart`：`split("##")`，第 4 段或尾部 `###` → replaceFirst |
| | 69-81 | `replace`：**`replaceFirst` 无匹配返回 `""`**（对齐上游） |
| `runtime/EmbeddedLegadoRuntime.kt` | 603-604 | `extract`：`extractAll(listMode=false).joinToString("\n")` |
| | 611-655 | `extractAll`：逐段，`Js`/`Default` 分派，`##` 逐元素替换 |
| | 528-551 | `extractElements`：单段 Default 保留原始节点；多段时**序列化成 HTML 再解析**（`:539-543`） |
| | 712 | `hasJs`：`<js>`/`@js:`/`{{` |
| | 722-733 | `pseudoSelectorWarning` |

---

## 3. 差异清单（按严重度）

> 严重度：**S1** 会让真实书源取到错误值/取空；**S2** 语义偏离但影响面窄；**S3** 仅诊断/边界差异。
> "上游"均指 LegadoTeam `330e606`，链接前缀 `https://github.com/LegadoTeam/legado/blob/330e606c4d57e08adc056ff8ab7a4132c784da9a/`。

### S1-1 `-` 前缀被当成"列表反转"，上游根本没有这条语法

- 本项目：`LegadoRuleEngine.kt:23-24`（`extract`）、`:52-53`（`elementList`）、`:34`/`:61`（`asReversed()`）。
- 上游：`AnalyzeRule.SourceRule.init`（`AnalyzeRule.kt:662-696`）**没有任何 `-` 前缀处理**；`-` 只会落到 `else -> ruleStr`，由 `AnalyzeByJSoup` 当 CSS 选择器（jsoup 报 `Could not parse query`）或 XPath 报错。
- 影响：`-li@text` 在本项目返回倒序列表，真机返回**空或异常**。这是本项目**自造语义**，不是兼容性差异——书源里出现 `-` 前缀时两边结果必然不同。
- 现有测试 `LegadoRuleEngineTest.kt:50-54` **固化了这个自造行为**，重构时必须改写或删除。

### S1-2 `@css:` 子前缀被当成 CSS 分支，上游只认整段 `@CSS:`

- 本项目：`AnalyzeByJSoup.kt:90`（`sourceRule.isCss || ruleStrX.startsWith("@css:", true)`）、`:138`、`:141`；`selectCss` 会 `replace(Regex("^@css:"), "")`（`:513-521`）。
- 上游：`AnalyzeByJSoup.kt:90`（`if (sourceRule.isCss)`）——**没有子前缀判断**；`@css:` 段在非 `@CSS:` 整串里会被 `ElementsSingle` 当作 CSS 选择器 `@css:.x` 交给 `temp.select`，jsoup 抛 `Could not parse query`。
- 影响：`a@text||@css:.b@text` 这类混写在本项目能跑通、真机报错。现有测试 `LegadoRuleEngineTest.kt:35-48` 明确断言了"混写可跑"——**与上游相反**。
- 附带：`selectCss` 的 try/catch 吞掉异常返回空（`:516-520`），上游**直接抛**。这会让"规则写错"和"页面无此元素"在本项目不可区分。

### S1-3 JSON content 下的**字段规则**被误判为 JSONPath，上游是"键值直接访问"

- 本项目：`LegadoRuleEngine.kt:94-99`（`detect(rule, content)`：JSON content 且无前缀 → `JSON_PATH`）；`EmbeddedLegadoRuntime.kt:376` 把 `bookList` 的结果（JSON 对象/Map）直接喂给字段 `extract`。
- 上游：`AnalyzeRule.getString`（`:337-340`）对 `LinkedTreeMap`、`:325-336` 对 `NativeObject` **只取 `ruleList.first().rule` 当键**，`result[rule]`，**不经过 JsonPath**；`getStringList` 同理（`:245-247`）。只有 `$.`/`$[` 开头或 `@Json:` 才走 JsonPath。
- 影响：字段规则写 `name`（相对键）时，本项目走 `JsonPath.parse(item).read("name")`，jayway 会自动补 `$.`，**结果恰好相同**；但字段规则写 `data.name` 或含 `[` `]` 的键名、或键名本身是 `$.xxx` 字面量时，两边分叉。更关键的是 **`@put`/`@get`/`{{}}`/`##` 在 `NativeObject` 特例下的执行顺序不同**（上游 `putRule` 在 `makeUpRule` 之前，且 `getParamSize()>1` 时直接返回规则串）。
- 现有测试 `JsonListFieldRuleTest.kt:38-50`（`relativeKeyFieldsOnListItems`）依赖本项目行为，需按上游重写断言。

### S1-4 `getStringList` 对 JS 对象的分派：Team 有 `Mode.Js`/`Mode.Json` 分支，Max/NG/T/huaji 已删除

- Team：`AnalyzeRule.kt:225-235`（`NativeObject` 时 `mode == Js → evalJS`，`mode == Json → getStringList`）。
- Max：`AnalyzeRule.kt:219-227` 只剩 `getParamSize() > 1 ? rule : result[rule]`；NG `:219-227`、T `:184-196`、huaji `AnalyzeRuleCore.kt:214-222` 同。
- 本项目：`EmbeddedLegadoRuntime.kt:633-643` 对 `Js` 段一律 `rhino.evaluateRaw`，`Default` 段 `engine.extract`——**没有 NativeObject 特例**。
- 结论：**这条不能简单"对齐 Team"**。活跃分支（Max/NG/T/huaji，2026-11 更新）已经统一成"JS 对象只取键"。重构应以 **Max/NG/T/huaji 的共识**为准，把 Team 的 `NativeObject + Mode.Js` 分支视为 Team 特有。这是"跨分支 profile 差异"，不是"上游 vs 本项目"。

### S1-5 `%%` 合并语义：本项目 `AnalyzeByJSoup.getStringList` 与上游一致，但 `extractAll` 的列表模式不一致

- 上游 `%%`：以**第一段**下标为准交错（`AnalyzeByJSoup.kt:106-113`、`AnalyzeByXPath.kt:74-81`、`AnalyzeByJSonPath.kt:109-116`）。
- 本项目：`AnalyzeByJSoup.kt:106-113` 一致；但 `EmbeddedLegadoRuntime.extractAll`（`:625-651`）在 `listMode=true` 时把每段结果 `joinToString("\n")` 后又 `toStringList` 拆回，**多段 `&&` 的边界被换行抹平**，`%%` 交错发生在引擎内部，尚可；但 `getString`（`listMode=false`）在 `&&` 后 `joinToString("\n")` 再被下一段 `engine.extract` 当**单串**解析——上游 `getString` 也是逐段传 `result`（`:341-364`），**这一点两边一致**。
- 真正差异：`extractAll` 在 `Default` 段用 `engine.extract(result, ...)`（`:635-639`），`result` 是 `List<String>` 时 `LegadoRuleEngine.extract` 的 `jsonContent` 判定（`:72-75`）会把 List 当 JSON 数组交给 JsonPath——**上游不会**（上游 `Default` 段拿到 List 会走 `AnalyzeByJSoup(result)`，`parse` 里 `doc.toString()` 是 `[a, b]` 形式）。这条在下游 `$.` 规则上才触发，列为 S2。

### S2-1 `parseSourceHtml`（自闭合标签）缺失

- 上游 Team：`AnalyzeByJSoup.parse`（`:21-34`）与 `AnalyzeByXPath.strToJXDocument`（`:40`）都走 `parseSourceHtml`（`SourceHtmlParser.kt:12-19`，`TagSet.Html().onNewTag { set(Tag.SelfClose) }`）。
- 本项目：`AnalyzeByJSoup.kt:33` 直接 `Jsoup.parse`；`AnalyzeByXPath.kt:40` 直接 `JXDocument.create(html1)`。
- 影响：老书源里 `<a/>` 这类写法在 Team/HapeLee 下按自闭合解析，本项目按 HTML5 规则吞掉后续兄弟节点，**元素数量与 textNodes 结果不同**。HapeLee 与 Max/Rimchars/NG/T 都没有 `parseSourceHtml`——**这是 Team 独有**，需按 profile 开关。

### S2-2 `getResultList` 的 `elements.clear()`

- 本项目：`AnalyzeByJSoup.kt:199` `elements.clear()`。
- 上游 Team：`:216` 直接 `elements = es`（不 clear）；Max/Rimchars/NG/T 保留 `clear()`。
- 影响：`Elements` 是同一个可变列表，`clear()` 后 `es` 若与 `elements` 共享引用会清空结果。Team 视为 bug 修复。列为 S2，建议跟随 Team。

### S2-3 `@CSS:` 段内 `@` 分隔与 `lastIndexOf`

- 本项目：`AnalyzeByJSoup.kt:93` `body.take(lastIndex)`，`lastIndex > 0` 才切，否则整串当 CSS、取值器 `text`（`:94`）。
- 上游 Team：`:91-95` **不判断 `lastIndex > 0`**，`@CSS:` 段没有 `@` 时 `lastIndexOf` 返回 -1，`take(-1)` 抛异常。
- 影响：`@CSS:.x`（无取值器）在本项目返回 text，真机**抛异常**。本项目更宽容，但会掩盖书源错误。S2。

### S2-4 `getString` 的 `unescape` 范围

- 上游：`StringEscapeUtils.unescapeHtml4`（`AnalyzeRule.kt:369-373`），覆盖全部 HTML4 实体。
- 本项目：`LegadoStringRule.unescapeHtml`（`:83-84`）用 `org.jsoup.parser.Parser.unescapeEntities(value, false)`，**`inAttribute=false`**，对 `&nbsp;` 等处理与 commons-text 有细微差异（jsoup 不认识的实体原样保留）。
- 影响：`&nbsp;`、`&#x27;` 等边界实体。S3，但建议直接换 commons-text 或补齐实体表。

### S2-5 XPath `getString` 不切 `%%`

- 上游 Team：`AnalyzeByXPath.kt:135` 只切 `&& ||`；`getStringList`（`:96`）切 `&& || %%`。
- 本项目：`AnalyzeByXPath.kt:135` 同样只切 `&& ||`——**一致**。
- 但 huaji 版 `AnalyzeByXPath.kt:106-118` 的 `getString` 也切 `&& ||`，`getStringList` 切 `&& || %%`——一致。**这条不是差异**，记录以免误改。

### S2-6 `AnalyzeByRegex.getElement` 的 `group(n)!!`

- 本项目：`AnalyzeByRegex.kt:20` `resM.group(groupIndex)!!`——**与 Team 一致**（Team `:20`）。
- huaji：改为 `groupValueOrThrow`（`:23-27`），显式对齐"未参与分组抛异常"。
- 结论：一致，无需改。但本项目 `LegadoRuleEngine.extract` 的 REGEX 分支（`:32`）用 `getElements(...).mapNotNull { it.firstOrNull() }`，**只取 group0**，上游 `getString` 的 Regex 路径是 `getElement`（`AnalyzeRule.kt:400-403`）返回**全部 group**。这是真差异：`:\d+(\w+)` 类规则本项目只拿到整个匹配。**S1 级**，补记为 S1-6。

### S1-6 Regex 规则只取 `group(0)`，丢失分组

- 本项目：`LegadoRuleEngine.kt:32`、`:59`（`mapNotNull { it.firstOrNull() }`）。
- 上游：`AnalyzeRule.getString` 的 Regex 路径不直接走 `AnalyzeByRegex`，而是 `SourceRule.splitRegex` 把 `$n` 拆成多段（`:744-771`），`$1` 段由 `makeUpRule` 从 `result` 列表取第 1 项（`:783-791`）；`getElement` 的 Regex 分支（`:400-403`）返回 `List<String>`（含 group0）。`getStringList` 的 Regex 路径（`:255-264`）走 `else -> rule`（返回规则串本身）——**上游 `getStringList` 对 Regex 模式几乎不可用**，只有 `getElement` 和 `$n` 拆分可用。
- 影响：`:(\d+)章` 在本项目返回 `"12章"`（group0），真机在 `getString` 下同样返回 group0（因为 `$1` 才取分组）；但 `getElement` 场景真机返回 `[group0, group1]`。本项目 `elementList` 的 REGEX 分支（`:59`）也丢分组。**S1，影响 `getElements`/元素预览**。

### S3-1 `detect`/`normalize` 的前缀大小写

- 本项目：`LegadoRuleEngine.kt:126-129` `removePrefix("@XPath:")` + `removePrefix("@xpath:")`，**`@XPath:` 之外的大小写变体（如 `@XPATH:`）不剥**；`@CSS:` 不剥（交给 `AnalyzeByJSoup.SourceRule`）。
- 上游：`SourceRule.init` 用 `startsWith("@XPath:", true)`（`:675`），**大小写不敏感**。
- 影响：`@XPATH://div` 在本项目 `normalize` 后仍是 `@XPATH://div`，`AnalyzeByXPath` 会把它当 XPath 表达式解析失败。S2，建议改 `startsWith(..., ignoreCase=true)` + `substring`。

### S3-2 `{{}}` 扫描：`JsTemplates` 字符串扫描 vs 上游正则

- 本项目：`JsTemplates.findAll`（`domain/JsTemplates.kt:20-36`）用 `indexOf("{{")` + `indexOf("}}")`，**非贪婪、不处理嵌套**。
- 上游：`evalPattern` 正则 `\{\{[\w\W]*?\}\}`（`AnalyzeRule.kt:1028-1029`），同样非贪婪，但**允许跨 `}}` 的 `{{a}}b}}` 只匹配到第一个 `}}`**——与字符串扫描一致。
- 差异：本项目只在 `requestValue`（`EmbeddedLegadoRuntime.kt:517`）用 `JsTemplates`，**规则求值路径（`extractAll`）完全不处理 `{{}}`**（只在 `hasJs` 里检测，`:712`）。上游 `makeUpRule` 在**每个非 JS 段**处理 `{{}}`。这是 **S1 级缺失**，补记为 S1-7。

### S1-7 规则求值路径缺失 `@put` / `@get` / `{{}}`

- 本项目：`EmbeddedLegadoRuntime.extractAll`（`:611-655`）与 `LegadoStringRule.split`（`:24-42`）只切 `<js>`/`@js:`/`##`，**不解析 `@put:`、`@get:`、`{{}}`**；`grep -rn "@put\|@get" app/src/main/java` 只命中 `JsEnvBindings` 的 `putVariable`，**没有任何 `@put:` 规则解析**。
- 上游：`SourceRule.init`（`AnalyzeRule.kt:698-738`）+ `makeUpRule`（`:776-835`）+ `putRule`（`:507-511`）。
- 影响：任何用 `@put:{key:rule}` 存变量、再用 `@get:{key}` 或 `{{java.xxx}}` 回填的书源，在本项目**整段规则串被当选择器**，必然取空或报错。这是**最高价值修复点**。

### S1-8 `$1`/`$2` 分组引用缺失

- 本项目：无 `regexPattern`（`\$\d{1,2}`）处理；`LegadoStringRule` 只处理 `##`。
- 上游：`splitRegex`（`:744-771`）+ `makeUpRule` 的 `regType > defaultRuleType` 分支（`:783-791`）。
- 影响：`ruleSearch.name: "$1"` 这类"正则分组回填"书源在本项目取到字面量 `$1`。

### S3-3 `@webJs:` 未实现

- 上游：`WebJS_PATTERN`（`:618-628`），走 `BackstageWebView`。
- 本项目：无。沙箱无 WebView 规则执行能力，**应显式报"不支持"而不是静默当选择器**。S3（诊断质量）。

### S3-4 `isJson` 判定口径

- 上游：`content.toString().isJson()`（`AnalyzeRule.kt:107-110`），`Node` 一律 false。
- 本项目：`LegadoRuleEngine.isJsonContent`（`:83-88`）用"`{`…`}` 或 `[`…`]` 首尾字符"判定，**空串、纯空白、`{` 开头但非法 JSON 都会被判 true**。
- 影响：HTML 页面以 `{` 开头（少见）会被误判 JSON。S3。

### S3-5 `AnalyzeByJSonPath.getList` 不处理 `{$.rule}`

- 本项目 `:131-172` 与上游 `:132-172` **一致**（都不处理）。记录以免误改。

---

## 4. 跨分支 profile 的实际差异（不是"贴标签"，逐条给证据）

| 维度 | Team `330e606` | HapeLee `2e2c63c` | Max `b386408` | Rimchars `e111bf6` | NG `7b60874` | T `b0c6067` | huaji `83657e5` |
|---|---|---|---|---|---|---|---|
| `RuleAnalyzer` | blob `5fd91e15d0` | 同 | 同 | 同 | 同 | 同 | 重写 `99cbadf666`（329 行） |
| `parseSourceHtml`（`<a/>` 自闭合） | **有** | 无 | 无 | 无 | 无 | 无 | 无（Ksoup） |
| `@css:` 子前缀 | 无 | 无 | 无 | 无 | 无 | 无 | 无 |
| `getResultList` `clear()` | 无（`:216`） | **有**（`:199`） | 有 | 有 | 有 | 有 | 有 |
| `!` 排除实现 | `deselect` 倒序 | 写 null + `removeAll` | 写 null | 写 null | 写 null | 写 null | `removeAt` 倒序 |
| `NativeObject` 的 `Mode.Js/Json` 分支 | **有**（`:225`/`:329`） | 有 | **无** | 有 | **无** | **无** | **无** |
| `localBindings`（`page`/`paraIndex`） | 有（`:89`、`:914`） | 有 | **无** | 有 | **无** | 有 | 有 |
| `AnalyzeByJSonPath.onError` | **有** | 无 | 无 | 无 | 无 | 无 | 无（`parseOrNull` 吞异常） |
| JsonPath 实现 | jayway | jayway | jayway | jayway | jayway | jayway | **rjpath**（`:2`） |
| XPath 实现 | JsoupXpath `JXDocument` | 同 | 同 | 同 | 同 | 同 | **ksoup `selectXpath`**（`:10`） |
| 正则超时 | 无 | 无 | 无 | 无 | 无 | 无 | **3s**（`AnalyzeByRegex.kt:83`） |
| `%%` 合并 | 内联 | 内联 | 内联 | 内联 | 内联 | 内联 | **`RuleCombiner`**（`:1-56`） |
| `getElements` 返回 | 过滤 `NOT_FOUND`（`:477-501`） | 直接 `as List<Any>`（`:446-449`） | 同 HapeLee | 同 | 同 | 同 | 同 |

**可移植性结论**：
- `RuleAnalyzer` + `AnalyzeByJSoup` + `AnalyzeByXPath` + `AnalyzeByJSonPath` + `AnalyzeByRegex` 这 5 个文件在 Team/HapeLee/Max/Rimchars/NG 之间差异极小（< 60 行），**可以直接整体移植 Team 版本**，用 profile 开关控制 `parseSourceHtml` 与 `NativeObject` 分支。
- huaji 版是**独立重写**（Ksoup + rjpath + selectXpath + 正则超时），**不应作为移植源**，但它的 `RuleCombiner` 抽象和 `RegexTimeoutException` 值得借鉴（沙箱需要正则超时，否则恶意书源可挂死线程）。
- `AnalyzeRule.kt` 是唯一分歧大的文件（982–1299 行），**不应整体移植**（依赖 `BaseBook`/`BookSource`/`Debug`/`WebBook` 等 App 层）。应只移植 `SourceRule` 内部类（`init` + `splitRegex` + `makeUpRule` + `splitPutRule` + `putRule` + `replaceRegex`）到一个**无 Android 依赖**的 `RuleStringParser`。

---

## 5. 推荐重构方式

### 5.1 分层（可落地，不重写 DSL）

```
RuleStringParser          ← 移植 AnalyzeRule.SourceRule（init/splitRegex/makeUpRule/splitPutRule）
  ├─ splitSourceRule      ← <js>/@js: 切段（已有 LegadoStringRule，需补 @webJs: 显式报错）
  ├─ @put/@get/{{}}/$n    ← 新增，纯字符串层，无 JS 依赖（{{}} 的 JS 求值靠回调注入）
  └─ ## 替换              ← 已有 LegadoStringRule.replace，需补 "replaceRegex 不 trim"
RuleDispatcher            ← 移植 AnalyzeRule 的 mode 分派（getString/getStringList/getElements 三入口）
  ├─ AnalyzeByJSoup       ← 整体移植 Team 版 + profile: parseSourceHtml / clear()
  ├─ AnalyzeByXPath       ← 整体移植 Team 版
  ├─ AnalyzeByJSonPath    ← 整体移植 Team 版（jayway，保留 onError）
  └─ AnalyzeByRegex       ← 整体移植 + 正则超时（借 huaji 思路）
RuleValueAdapter          ← 替代 LegadoRuleEngine 的 -/detect/normalize 自造层
```

### 5.2 具体动作

1. **删除 `LegadoRuleEngine` 的 `-` 前缀反转**（`:23-24`、`:52-53`），改为上游语义：`-` 交给选择器，报错即报错。同步删除 `LegadoRuleEngineTest.kt:50-54`。
2. **删除 `@css:` 子前缀支持**（`AnalyzeByJSoup.kt:90`、`:138`、`:141`、`:513-521` 的 `replace(Regex("^@css:"))`），改为只认整段 `@CSS:`；`selectCss` 的 try/catch 改为**记录错误并抛 `RuleParseException`**（沙箱需要"规则写错"可见）。同步改写 `LegadoRuleEngineTest.kt:35-48`。
3. **新增 `RuleStringParser`**，把 `@put`/`@get`/`{{}}`/`$n` 从上游 `SourceRule` 原样搬过来（`AnalyzeRule.kt:507-539`、`:744-835`），`{{}}` 的 JS 求值通过 `(String, Any?) -> Any?` 回调注入 `RhinoEvaluator`。
4. **`getString`/`getStringList`/`getElements` 三入口按上游契约重写**：`getStringList` 的 `String → split("\n")`（`:278-281`）、`getString` 的 `unescapeHtml4` + `isUrl` 空串返回 `baseUrl`（`:367-381`）、`getElements` 的 `NOT_FOUND` 过滤（`:477-501`）。
5. **JSON content 的字段规则改为"键值直接访问"**（`AnalyzeRule.kt:245-247`/`:337-340`），并删除 `LegadoRuleEngine.detect(rule, content)` 的 JSON 自动切换（`:94-99`）；保留 `$.`/`$[`/`@Json:` 显式前缀。同步改写 `JsonListFieldRuleTest.kt:38-50`。
6. **`AnalyzeByRegex` 补分组**：`extract` 的 REGEX 分支改用 `AnalyzeByRegex.getElement`（返回全部 group），`elementList` 同理；加 3s 超时。
7. **profile 开关**（`RuleProfile` 数据类）：`parseSourceHtml`（Team=true，其余=false）、`nativeObjectJsBranch`（Team/HapeLee/Rimchars=true，Max/NG/T/huaji=false）、`resultListClear`（Team=false，其余=true）、`excludeViaDeselect`（Team=true，其余=false）。默认 profile = **Team**，因为规则引擎最完整；`Max`/`NG` 作为可选 profile 供"最新活跃分支"对照。
8. **`@webJs:` 显式不支持**：`RuleStringParser` 识别到 `@webJs:` 时返回结构化错误 `unsupported:webJs`，不要静默当选择器。

### 5.3 不建议做的事

- **不要**用正则拆分整个 DSL（`##`/`@put`/`{{}}`/`$n` 互相嵌套，正则必然错）。上游 `RuleAnalyzer` 的平衡组扫描是正确做法，已在本项目里，直接复用。
- **不要**移植 `AnalyzeRule.kt` 整体（依赖 `BaseBook`/`WebBook`/`Debug`）。
- **不要**把 huaji 的 Ksoup/rjpath 实现当基线（跨平台重写，与 Android 端行为有已知差异，如 `getElementById` 语义、`selectXpath` 的 `/@` 特判）。
- **不要**为 Harmony 分支虚构实现（快照无引擎源码）。

---

## 6. 五个最高价值修改点

1. **补 `@put` / `@get` / `{{}}` / `$n` 规则串层**（S1-7、S1-8）：这是本项目**完全缺失**的上游语法，影响所有用变量回填的书源；移植 `AnalyzeRule.SourceRule` 的 `init`/`makeUpRule`/`splitPutRule` 即可，无需 JS 依赖。
2. **删除 `-` 前缀反转与 `@css:` 子前缀**（S1-1、S1-2）：两处都是本项目自造语义，让沙箱"能跑但真机报错"，且现有测试固化了错误行为。
3. **JSON content 的字段规则改回"键值直接访问"**（S1-3）：`LegadoRuleEngine.detect(rule, content)` 的自动 JSONPath 切换与上游 `NativeObject`/`LinkedTreeMap` 特例相反，影响所有 JSON 书源的字段抽取与 `##`/`@put` 执行顺序。
4. **整体移植 Team 版 `AnalyzeByJSoup`/`AnalyzeByXPath`/`AnalyzeByJSonPath`/`AnalyzeByRegex`**（S1-6、S2-1、S2-2、S2-3）：四个文件与上游差异 < 60 行，移植成本低；同时补 `parseSourceHtml`、`getResultList` 不 clear、`@CSS:` 无取值器抛错、Regex 分组。
5. **建立 `RuleProfile` 跨分支开关**（§4）：把 `parseSourceHtml`、`NativeObject` 分支、`clear()`、`!` 排除实现做成显式 profile，默认 Team，可选 Max/NG；避免"贴标签式兼容"，也让后续对活跃分支（Max/NG/T/huaji）的差异可测。

---

## 7. 证据来源

- 上游：`research/yuedu-refactor/<owner__repo>/src/...`，commit 见 §0；Git blob SHA 见各仓库 `manifest.json` 的 `source_manifest`。
  - `LegadoTeam__legado/manifest.json`：`AnalyzeRule.kt` blob `7591a026a0`、`AnalyzeByJSoup.kt` `060cb74512`、`RuleAnalyzer.kt` `5fd91e15d0`、`SourceHtmlParser.kt` `5b173e0b98`。
  - `HapeLee__legado-with-MD3/manifest.json`：`AnalyzeRule.kt` `e11f050889`、`AnalyzeByJSoup.kt` `e6103d1e63`。
  - `Suml-1__Legado_Max/manifest.json`：`AnalyzeRule.kt` `9ea17f3120`、`AnalyzeByJSoup.kt` `958c0f2565`。
  - `huajideshutiao__legado/manifest.json`：`AnalyzeRuleCore.kt` `b2c78c675e`、`AnalyzeByRegex.kt` `02a3654be9`、`AnalyzeByXPath.kt` `c9c3f7daa8`。
- 本项目：`app/src/main/java/io/legado/app/model/analyzeRule/*.kt`、`app/src/main/java/com/mina/legadostudio/runtime/{LegadoStringRule,EmbeddedLegadoRuntime,LegadoRuleEngine?}.kt`、`app/src/main/java/com/mina/legadostudio/domain/JsTemplates.kt`、`app/src/test/java/com/mina/legadostudio/domain/{LegadoRuleEngineTest,LegadoStringRuleTest,JsonListFieldRuleTest}.kt`。
- 未使用：CCSSNE/legadoC（own 分支落后）、Luoyacheng/legado-E（100 天未更新）、mgz0227/legado-Harmony（无引擎源码）、apikey-vault（按要求不读）。
- 未执行任何网上书源 JS；未运行完整构建；未提交/推送。
