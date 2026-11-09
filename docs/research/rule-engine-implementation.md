# 规则解析核实现报告（ReaderRuleSession + io/legado/app/model/analyzeRule）

> 上游基线：LegadoTeam/legado `330e606c4d57e08adc056ff8ab7a4132c784da9a`（见 `docs/research/upstream-lock.json`）。
> 本地镜像：`research/yuedu-refactor/LegadoTeam__legado/src/app/src/main/java/io/legado/app/`。
> 本报告只描述**规则核**（解析、编排、typed 结果、变量层）。JS 引擎绑定与网络请求见
> `docs/research/runtime-implementation.md`。

## 1. 文件与上游对应

| 本仓库 | 上游 | 移植方式 |
|---|---|---|
| `app/src/main/java/com/mina/legadostudio/runtime/ReaderRuleSession.kt` | `model/analyzeRule/AnalyzeRule.kt` | 直接移植，去掉 Android/协程依赖 |
| `.../runtime/ReaderRulePart.kt` | `AnalyzeRule.kt` 的 `SourceRule`（`splitSourceRule` 647-739、`makeUpRule` 776-835） | 抽成独立类，保留字段名与语义 |
| `.../io/legado/app/model/analyzeRule/LegadoRuleEngine.kt` | `AnalyzeRule.detect` / 分派入口 | 保留 `Kind` 枚举与 `detect` |
| `.../analyzeRule/AnalyzeByJSoup.kt` | `AnalyzeByJSoup.kt` | 逐函数移植（含 `getString0`、`getStringList`、`getElements`） |
| `.../analyzeRule/AnalyzeByXPath.kt` | `AnalyzeByXPath.kt` | 同上（JXNode 保留 typed） |
| `.../analyzeRule/AnalyzeByJSonPath.kt` | `AnalyzeByJSonPath.kt` | 同上（JSON Map/List 不提前字符串化） |
| `.../analyzeRule/AnalyzeByRegex.kt` | `AnalyzeByRegex.kt` | 同上（capture 保留 `List<String>`） |
| `.../analyzeRule/RuleAnalyzer.kt` | `RuleAnalyzer.kt`（blob `5fd91e15d0`，六分支同源） | 花括号平衡扫描 |

`RuleAnalyzer.kt` 在 Team / HapeLee / Max / Rimchars / NG / T 六个分支是**同一 blob**，
所以规则切分层跨分支无分歧，直接移植 Team 版本即可（见 `rule-semantics.md` §0）。

## 2. 已实现的语义（逐条对应上游行号）

### 2.1 一份 session 接所有解析入口

`ReaderRuleSession` 是唯一的解析核。`getString` / `getStringList` / `getElement` / `getElements`
都在同一实例上执行，共享 `content` / `baseUrl` / `redirectUrl` / 三个 analyzer 缓存 / `localBindings`。

`java.getString` 等 JS 侧入口**委托给同一个 session**（见 runtime 报告 §2），
不再有第二套 CSS 实现。

### 2.2 `@put` / `@get` / `{{}}` / `$n` / `##`

| 语义 | 上游 | 实现 |
|---|---|---|
| `@put:{k:v}` 的值本身是规则，用 `getString` 求值后写入 | `putRule` 507-511 | `ReaderRuleSession.putRule` |
| `{{规则}}`：`isRule()` 为真走 `getString`，否则当 JS | `makeUpRule` 776-835 | `ReaderRulePart.makeUpRule` |
| `$n` 只对 `List` 生效，非 List 时保留字面量 | 同上 | 同上（`regType > DEFAULT_RULE_TYPE` 分支） |
| `##a##b` 全替换；四段 `##a##b##` 只替换首个匹配 | `replaceRegex` 544-568 | `ReaderRuleSession.replaceRegex` |
| `@get:{key}` 走变量层 | 同上 | 同上 |

**`$n` 与 typed regex 的关系**：`AnalyzeByRegex.getElements` 返回每次 match 的 capture
`List<String>`（含 group0），**不压成 `firstOrNull`**。因此 `:正则$1` 这类规则能在后续段
按组取值。旧实现把 capture 压成首项，`$1/$2` 无法按阅读语义取组——这是本次修复的重点之一。

### 2.3 `getString` / `getStringList` 的返回契约差异

| 入口 | 上游行为 | 行号 |
|---|---|---|
| `getStringList` | 结果若为 `String` 则 `split("\n")`；`isUrl=true` 转绝对 URL 并去重；否则 `as? List<String>` | 278-295 |
| `getString` | `toString()`；`unescape=true` 且含 `&` 时 HTML4 反转义；`isUrl=true` 时空串返回 `baseUrl`，否则转绝对 URL | 367-381 |
| `getElements` | `List`→过滤 null；`Array`/`NativeArray`→逐项；其余→**空列表** | 452-502 |
| `getElement` | Regex 返回含 group0 的列表；Json 返回 `getObject`；XPath/Default 返回 `getElements` | 387-417 |

**关键：`getString` 的 `when` 分支不含 `Regex`**（上游 347-359）。`:正则` 规则在 `getString`
路径上原样返回规则字面量，不取首个匹配。这是上游行为，不是缺陷，已在代码注释与
`ReaderRuleSessionTest` 中标明，**不按「看起来更合理」去改**。

`getString` 与 `getStringList` 在 JS 对象（`Map`）上的分派也不同：Team 版两者都有
`Mode.Js` / `Mode.Json` 分支（221-247、325-340），本地按 Team 保留。

### 2.4 typed 结果

- regex：`List<String>`（capture 组，含 group0）
- JSON：`Map` / `List`（不提前 `toString`）
- DOM：`org.jsoup.nodes.Element`（不提前 `outerHtml`）
- XPath：`JXNode`

只有 `stringify()` 在需要字符串时才转（`Element.outerHtml()` / JSON 序列化），
与上游 `result.toString()` 的时机一致。

### 2.5 错误不静默

- `onError` 回调：字段级错误由 `EmbeddedLegadoRuntime` 汇入 `fieldErrors`，带字段名/规则类型/规则原文。
- `@webjs` 无平台能力 → `UnsupportedOperationException`（明确「需要 WebView」），**不降级成 CSS**。
- JS 求值异常向上传播（`jsEvaluatorErrorsPropagate` 测试锁定）。
- `content` 为 null → `requireNotNull` 抛 `IllegalArgumentException`（上游 `setContent` 对 null 抛错）。

**不做的**：不把 `ReaderRuleSession` 的所有异常吞成空串。只有「规则无命中」这类上游本身返回
空值的路径才返回空；真正的解析/JS 错误必须抛出或进 `fieldErrors`。

### 2.6 `setContent` / `setBaseUrl`

`setContent` 重置 `isJSON`、`baseUrl`、`redirectUrl` 与三个 analyzer 缓存（上游 104-116）。
`isJSON` 是**整个 content 的属性**，不是逐段判定。

## 3. 与上游的差异（ADAPT）

| 差异 | 原因 | 影响面 |
|---|---|---|
| `NativeObject` 用 `Map` 表示（`isJsObject`） | 沙箱 JS 引擎返回 `Map` 而非 Rhino `NativeObject` | JS 对象分派路径 |
| `getString` 的 `LinkedTreeMap` 分支合并进 `Map` 分支 | Gson 的 `LinkedTreeMap` 就是 `Map` | 无行为差异 |
| `AnalyzeByRegex` 无 3 秒超时 | 沙箱在 `HttpFetcher` 层统一超时；正则层不单独限时 | 极端回溯正则可能慢，但不会挂死整链（有外层取消） |
| `@webjs` 抛 `UnsupportedOperationException` | 沙箱无 WebView（或由调用方注入 `evalWebJs`） | 见 runtime 报告 |
| `localBindings` 保留但仅供 `setLocal` | 上游用于 `local` 作用域；沙箱无对应实体 | 无外部可见差异 |

**未偏离的**：`getString` 不含 Regex 分支、`getStringList` 换行拆分、`getElements` 标量返回空、
`$n` 只对 List 生效、`##` 四段 replaceFirst——这些看起来「奇怪」的行为都是上游真实语义，
按用户要求**不投票决定、不偷偷修复**。

## 4. 测试

`app/src/test/java/com/mina/legadostudio/runtime/ReaderRuleSessionTest.kt`（33 例）：

- 默认 CSS 链读文本/属性、多结果换行拼接
- `@put`→`@get` 跨字段、`@put` 值是规则
- `{{规则}}` / `{{JS}}`、多段 JS、`src` vs `result`
- regex capture typed List、`$n` 取组、`getStringList` 展平 capture
- `##` 全替换 / 四段 replaceFirst
- 最终 String 按 `\n` 拆行
- JS 对象列表字段保留对象
- `getElements` 标量返回空、DOM 节点保留 typed
- `isUrl` 相对地址解析+去重、空结果回退 baseUrl、只取首个 DOM
- `@webjs` 无平台明确 Unsupported
- JS 异常传播
- `content` 不可空
- `setContent` 重置 analyzer 与 baseUrl

**测试状态：未运行。** 本机测试由父任务统一在 Gradle 中执行（预算约束，worker 不并发开
Gradle）。本次改动只做了独立 kotlinc 编译验证（见 §5）。

## 5. 编译验证（本次实际执行）

用 `kotlin-compiler-embeddable 2.3.20` 独立编译 `app/src/main/java` 全量 Kotlin 源 +
`QueryTTF.java`，classpath 取自 Gradle transforms 缓存 + `legado-rhino` 构建产物：

```
runtime/**            0 error
io/legado/app/model/analyzeRule/**   0 error
```

剩余 21 个 error 全部在**授权范围外**（`BuildConfig` / `McpService` 是 Gradle 生成代码，
独立编译时不存在；`VerificationBrowserView.kt:68` 的 `WebSettings`/`WebView` 类型不匹配
属 Vue/host 范围）。完整 Gradle 编译由父任务执行。

## 6. 未覆盖边界（明确声明）

1. **`@webjs` 真实 WebView 执行**：沙箱未接 WebView，只有 `evalWebJs` 注入点；无实机验证。
2. **正则超时**：未在规则核层加 3s 超时（依赖外层取消）；极端回溯正则的耗时未测。
3. **`local` 作用域**：`setLocal` 存在但无上游 `local` 实体的完整语义。
4. **`NativeObject` 原型链**：JS 对象以 `Map` 表示，原型上的方法不可见（上游 Rhino 可见）。
5. **真实公网书源**：所有测试用离线 HTML/JSON 与 MockWebServer，**不是真机/公网验证**。
