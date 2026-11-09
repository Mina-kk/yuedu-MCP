# 父级语义复核与测试修正依据

本记录只说明修复依据，不是阅读端/公网通过证明。所有修改后还需重跑对应测试。

## 上游固定依据

Team `330e606c4d57e08adc056ff8ab7a4132c784da9a`；完整采样路径在本 profile `research/yuedu-refactor/LegadoTeam__legado/src/`，不是仓库内同名路径。

- `AnalyzeRule.kt:255–263,347–358`：`getString/List` 的 Regex mode 返回展开后的规则文本。拒绝worker为了绿测加的二次正则提取/firstOrNull。`getElements(":...")` 才提取capture，随后在capture上用`$1/$2`。`593–605` 的 isRegex 是会话属性，提取后字符串字段沿用该模式。
- `AnalyzeRule.kt:387–417`：`getElement` 的 CSS/XPath 返回整组节点，而不是名字暗示的单个节点；JS测试改为one[0].attr，保持typed列表。
- `AnalyzeRule.kt:776–842`：`{{}}`只有@、$.、$[、//开头算规则；`{{class.name@text}}`是非法JS，不扩张DSL。@get后普通文本不是继续CSS，必须显式{{@CSS:...}}。
- `AnalyzeRule.kt:544–568`：四段##替换取第一个匹配的group0再替换，不是整串replaceFirst。测试原来期望baaa错误；改为b，三段全替换仍覆盖bbbb。
- `AnalyzeRule.kt:341–358,607–617`：@text给下一段JS的是文本，连续JS用后段结果，不自动拼接。修正想象的Element/拼接期望。
- `AnalyzeRule.kt:104–105`：setContent(null)被拒，不能靠null恢复最初网页。
- `AnalyzeByJSoup.kt`：@CSS整串模式分支不重复剥@css；无效选择器不静默空成功。撤销worker吞SelectorParseException的变更，新增明确抛错用例。
- Java Regex的默认\w不覆盖汉字：捕获中文的测试改用[^|]+，不是改真实书源。
- `AnalyzeByXPath`的TextUtils.join换为纯JVM等价join，保留空集合与null语义。
- `AnalyzeUrl.kt:188–211`：JS替换当前result，字面量仅用@result显式插入；@js到串尾，不能夹未闭合伪尾部。测试改合法<js>片段。
- `AnalyzeUrl.kt:395–410`：page/key由调用方注入，不是replaceTemplates凭空构造。测试eval回调显式给page。
- `NetworkUtils.kt`固定URL源码复核：getBaseUrl返回scheme://authority，**不带尾斜杠**；encodedForm/encodedQuery要逐字符检查，不是只要有一个%xx就认整串已编码。
- `AnalyzeUrl.kt:302–307,333–388`：无Content-Type且非JSON/XML的POST body在{{}}替换后按&/=拆字段，再URLEncoder；不会把key值里原有&当独立不可分字符预编码。旧测试期待%26是旧沙箱行为，固定上游会保留额外分隔符&&。显式java.encodeURI(key)的既有%xx仍保留。
- `SharedJsScope.kt:231–259`：JSON jsLib映射仅处理URL值；非URL值忽略；非JSON则整体作为JS。撤销worker“map内联片段都执行”的错误假设。下载失败明确环境错误，不静默跳库。

## 父级独立回归发现

- Rust比较超过u64的大整数原先丢精度而误判equal：加入arbitrary_precision，保留用户原始JSON字符串提交。
- `reader_verified`不能压过明确failureSignals/identityMatches=false/executionComplete=false：新增先失败后通过回归。
- Vue save_source的conflict是**已经保存新版本的回执**，不能当未写入再发第二次；按返回projectId回读。
- 书源/技能放弃确认曾递归弹相同对话框；设置emit不返回Promise导致保存busy立即消失；页面脏状态与settings脏状态分离；全有针对性回归。
- 主题必须持久化宿主palette+theme，不能下次bootstrap再覆盖为默认；部分settings保存失败不得toast全部成功。

## 真实书源实测补充

用户授权选取5个公开原始书源实测，书源来自 XIU2/Yuedu 固定 `426b24f59aa763ee0f2d9cc094612d2ef42c8293`，每项为原文JSON切片并校验SHA256。实际证据位于本profile `reports/five-public-sources/`；未提交抓取正文、凭据或第三方源到代码库。

- `AnalyzeUrl.kt:200,216–229` 的实际调用再次复核：URL JS 的 null 结果转为字符串 `null`，`{{}}` 使用 RuleAnalyzer 的字符串分隔重载，整段替换为空则不赋值。原来的自写平衡扫描错误；同输入差分测试3项先RED后GREEN，直接复用与固定上游字节一致的RuleAnalyzer。
- 起点真实源触发 `source.getKey()` 缺失；固定 Team `BookSource.kt:110–112` 返回完整bookSourceUrl。新增RED后补齐原始anchor返回，GREEN通过；JVM后续缺CookieStore属于本次无Android环境，仍不报可读。
- 未改的速读谷源在正式阅读3.26101819（不同于研究快照的commit）经发现→详情→目录→正文实测可读；Android UI正文翻页1→2已留图。搜索两词均0结果，不能称搜索全通过。
- 同源同章节首轮沙箱/阅读均目录344项、正文4个网页；当时移除空白后2260字符完全一致。后续正文按固定 HtmlFormatter/BookContent 格式调用链修复，不改变源规则。
- 发现 kind/lastChapter 丢失已定位为现代 jsoup Elements.clear/removeAt 会删除 DOM：删除 getResultList 的无必要 clear，链式节点选择用 deselectAll，排除索引用 deselect；与固定上游一致。新增3项 DOM 不变/字段重排回归先红后绿，原公开源及记录网页另做回放，不能把回放称新公网通过。
- 69书吧原文搜索JS在两端都报同样invalid flag，保留失败，不修改沙箱放宽语法来假通过。
- 此批是5源样本测试，不是全站/所有源兼容证明；阅读调试日志和UI证据不伪称结构化PASSED。

## 验收边界

首次完整Android测试1492项40失败，不删除测试或跳过掩盖；修正后以新JUnit XML为准。原始结果在本profile reports/yuedu-android-failures90.json。测试fixture、离线页面、MockWebServer不代表阅读端真机或公网书源通过。
