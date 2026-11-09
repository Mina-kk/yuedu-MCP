# 主导源码复核补充（覆盖研究草稿推断）

这份说明用于阻止把研究稿中的猜测直接写成兼容承诺。

1. `rule-semantics.md` §1.8说“SourceRule.init 在 Js/Regex时直接返回、不解析@put”不准确：源码只是 `rule = when` 分支返回字符串，后续 splitPutRule/evalPattern仍执行。以真实行号代码为准。
2. JS研究摘要表说 loginCheckJs 与 bodyJs 顺序“一致”，正文又指出相反；上游 bodyJs在AnalyzeUrl.getStrResponseAwait内，loginCheck在WebBook外，因此应 bodyJs→loginCheck。本项目旧顺序相反。修正文档，不制造自相矛盾结论。
3. 已补抓上游`help/source/BaseSourceExtensions.kt`、`model/SharedJsScope.kt`、`constant/AppPattern.kt`、`utils/EncoderUtils.kt`、Team `SourceHtmlParser.kt` 和header tests，路径仍在固定src快照。不要继续写“未取到getShareScope”。Team scope按jsLib哈希复用，额外sharedGlobalStateKey含libhash+sourceclass+sourcekey。不同分支scope实现不能一概“每源一个parent”，要核对。
4. 不选“多数分支共识”代替用户选择的profile。默认明确Team语义，MD3/legacy衍生差异独立声明；不要硬把Team已修复JS对象分派视为错误并倒退。
5. ReaderRuleSession的变量可同debug共享，但java/get重载必须依据上游：AnalyzeRule.get(String)是变量查询，JsExtensions.get(url,headers...)是网络，不能看到字符串http就擅自网络。一参旧扩展可明确范围，不保留含糊猜测。
6. 目录倒序标记 `-` 可能在WebBook/BookChapterList上层处理，不能只因AnalyzeRule不处理就宣布“阅读没有-语法”。引擎本身不做泛化-反转与目录/搜索列表调度层的反转是两回事；各层测试分开。
7. 当前branch-selection日期来自API commit date，不用索引pushed date替换，特别C/Harmony的研究表日期错位需要最终修正。
8. URL option `js` 的 `baseUrl` 在analyzeUrl里已归一到NetworkUtils.getBaseUrl(url)，result才是完整url。研究candidate #6 若把baseUrl视为整页url可能错，必须跑/按源码修正。
9. 读写真实内容与渲染样本测试不能把fixture报告为真实公网站点验证。
