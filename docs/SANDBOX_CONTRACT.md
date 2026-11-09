# 阅读语义沙箱重构契约 v2

## 最高验收门槛（覆盖草稿中的推断）

- 最终目标是沙箱适配阅读书源规则，不是生成一套让书源迁就沙箱的新规则；同一份原始书源与规则字符串进入两端，不自动改写用户数据。
- 规范来自固定上游实际调用链与对应阅读端执行。测试只验证规范，不能拿自写测试或 worker 的期望反过来定义规则。
- 每个兼容性修复保留上游 commit/文件/调用位置，先证明同输入的差异，再修沙箱；语法错误不得吞掉后当成空成功，平台缺口必须标为环境能力不足。
- 具体重载也属于语义：Team AnalyzeUrl 的 `RuleAnalyzer.innerRule("{{", "}}")` 是字符串分隔重载，不是花括号平衡重载。已有同源解析器应直接复用，不能另造更宽松的替代物。
- 离线 HTML/JSON、MockWebServer 与 JVM/Rhino 回归只证明所测路径；最终报告必须单列未验证的阅读端、平台 API、JS 共享作用域与生命周期范围，不称“完全兼容”。

## 出发点（已核对源码）

主参考固定文件：
- LegadoTeam AnalyzeRule `330e606c4d57e08adc056ff8ab7a4132c784da9a`，`app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt`。
- MD3 AnalyzeRule `2e2c63c3a7d2786719919fd0b11a197af9cf29bd` 同路径。
完整本地镜像位于本 profile research/yuedu-refactor/，参照报告补充差异。不能只改提示词，应真实修复执行链。

现有错误已读代码确认：
1. EmbeddedLegadoRuntime.extractAll 没有 SourceRule 的 @put/@get/{{}} 编排。
2. LegadoRuleEngine.elementList REGEX 把每次 match 的 capture List 压成 firstOrNull，$1/$2 后续字段无法按阅读语义取组。
3. RhinoEvaluator 每次创建 StudioJsApi 的 memoryStore；java.put/get不跨同书籍字段，java.getString/getStringList为简陋CSS实现且未自动绑定原内容。
4. Rhino src=previous 使链式JS的src不再是原页面；上游src是 AnalyzeRule.content、result是流经当前阶段结果。
5. requestValue先全局替换{{key}}成UTF-8编码，忽略真正 AnalyzeUrl 顺序及charset，sourceHeaders仅JSON不支持合法JS。
6. 上游getStringList最终String按换行拆分，现有toStringList把整串包单项；getElements返回非列表应为空而非任意标量包单项。

## 模块分工与接口（避免并发改同一文件）

### A. 解析核 worker（只写 ReaderRuleSession + io/.../analyzeRule +相关测试）

新增 `app/src/main/java/com/mina/legadostudio/runtime/ReaderRuleSession.kt`，不依赖Android。可复用现有 engine/analyzers，完整保留GPL来源与fixed commit。

建议冻结接口：
```kotlin
class ReaderRuleSession(
    content: Any?,
    baseUrl: String = "",
    private val engine: LegadoRuleEngine = LegadoRuleEngine(),
    private val evalJs: (script: String, result: Any?) -> Any? = { _, _ -> throw UnsupportedOperationException("JavaScript evaluator not configured") },
    private val evalWebJs: ((script: String, result: Any?) -> Any?)? = null,
    private val getVariable: (String) -> String = { "" },
    private val putVariable: (String, String) -> Unit = { _, _ -> },
) {
    var content: Any? // getter公开 setter私有；setContent修改
    var baseUrl: String // 同上
    fun setContent(content: Any?, baseUrl: String? = null): ReaderRuleSession
    fun getString(rule: String?, content: Any? = null, isUrl: Boolean = false, unescape: Boolean = true): String
    fun getStringList(rule: String?, content: Any? = null, isUrl: Boolean = false): List<String>?
    fun getElement(rule: String): Any?
    fun getElements(rule: String): List<Any>
    fun get(key: String): String
    fun put(key: String, value: String): String
}
```
`isUrl`处理与上游一致（getString无命中返回baseUrl、CSS取首、getStringList去重解析相对地址）；安全分页停止由外层负责，不篡改核心返回。
保留typed result：regex capture是List、Json Map/NativeObject、Element/JXNode不提前HTML化。SourceRule负责@put/@get、{{}}中规则或JS、$n组、##替换、mode判定和多段JS。尊重getString与getStringList不同，除非引用的branch确有更改。对WebJS缺平台使用明确Unsupported，不把它当CSS。错误必须抛出或类型化，不能静默返回虚假值。

### B. runtime/request worker（只写旧 runtime 除ReaderRuleSession，以及新request/session helpers、相关测试）

必须把 EmbeddedLegadoRuntime.inspect/extract/list fields 与 StudioJsApi.getString/list/element(s) 都接到 A 的同一 ReaderRuleSession，消除两个方言。新增统一运行上下文/源状态，使同一debug中source/book/chapter变量符合上游层级；通过callbacks把getVariable/putVariable接到对应对象。

保留 public RhinoEvaluator API兼容老tests。新增 bindings键或internal request context，将原始src、同字段/阶段session传递进去；java.setContent影响当前rule session，java.getString无显式content时取该session原内容，而不是上次空Document。不能让java.get(key)误当网络GET，区分一参变量读取与带headers网络重载（上游签名依据必须标清）；既有扩展如有冲突，明确profiles或兼容方式。

请求解析要按 AnalyzeUrl：支持 case-insensitive @js: 与 <js>、{{表达式}}、page/key上下文、headers动态JS、URL option宽容JSON；保留POST原始/表单/编码区别，charset要实际生效，不对JS结果自动二次编码。按上游支持 <page,...> 翻页模板。网络库jsLib可以受限 fetcher加载且缓存按源hash，或结构化environment error，不静默跳过。

source、book、chapter不是全局混在一个Map；同debug共享和多任务隔离要测。Profile只作为真实能力/差异选择，不声称所有平台Java APIs一致。保留受限ClassShutter，不能通过开放任意Runtime/Process/filesystem获得兼容。补正确的 environment diagnostics，不能把所有ReferenceError都归因源规则。

### C. 主导集成

原MCP tools调用路径不变，新增可选sandboxProfile元数据由主导协调。更新内置skill生成指导与API清单；将旧错误的“只允许沙箱支持写法”约束移除。补scope/compatibility报告并保持ReaderExecutionRegistry独立。

## TDD与证据

每个修复至少1条真实red测试，再green。核心cases须覆盖 @put→@get跨字段、JS java.put→java.get跨字段、src/result分离、regex group、JS对象列表字段、{{规则}}、{{JS}}、换行列表、全量CSS getString与URL首项、POST/GET编码及charset、jsLib生命周期、URL option。优先离线HTML/JSON与MockWebServer，不运行不受控公网源。

若上游行为本身存在缺陷，不以“修复”的名义偷偷偏离；保留profile范围并写测试。不同分支扩展不得假装通用。测试数、覆盖版本与环境差异要最终报告。

## 环境

JDK17、SDK36、Gradle8.13 `/opt/gradle-8.13/bin/gradle`。父任务正在初次下载依赖，完整基线worktree `/root/projects/yuedu-MCP-baseline` 可作差分/旧包测试。worker不要并发开多个重型Gradle，不改settings.gradle.kts/repositories。需要运行时用父任务准备的缓存；测试未跑必须明确pending。
