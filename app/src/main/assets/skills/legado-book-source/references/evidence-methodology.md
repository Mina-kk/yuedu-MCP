# 书源取证方法论（证据分级 / 覆盖边界 / 未知登记）

> 移植自通用逆向工程方法论（REA，morluto/rea）的证据语义，落到书源工坊工具链。本文只管一件事：**每条结论必须说清它由什么证据支持、覆盖多大范围、还剩什么未知**。与「失败分诊」（SKILL.md）、探针输出清单（`index.md`）互补——那两处讲"取什么证"，本文讲"怎么标注和兑现证据"。

## 一、四级证据语义

把每轮调试里产生的"事实"分成四级，引用和汇报时按级使用：

| 级别 | 定义 | 书源语境 |
|---|---|---|
| **observation（直接观察）** | 工具返回里白纸黑字读到的内容 | `fetch_page` 快照原文、HTTP 码、最终 URL；`get_http_log`/`get_capture` 的逐条请求记录；`analyze_html` 的选择器计数与节点文本；`eval_js`/`debug_source` 的 `lines` 输出 |
| **derivation（推导）** | 在观察上按确定规则加工得出 | 从快照按选择器提取的字段值；`inspect_rule`/`eval_js` 对快照跑规则的结果；`check_source` 逐阶段的 `facts`（章节数、首章 URL、正文字数） |
| **inference（推断）** | 由证据归纳、存在反例可能的结论 | "结构是 SSR 不是 CSR"、"目录可翻页"、"该站搜索无反爬"——这些都只是当前样本支持下的推断 |
| **unknown（未知）** | 没有证据或证据不覆盖 | 未跑过的阶段、验证墙后的页面、沙箱不执行的钩子、官方 App 未实测的行为 |

规则：**结论强度不能超过证据强度**（知识库 `js-reverse-s2-booksource.md` 已立此原则）。静态 `VALIDATED`、一次 HTTP 200、单一样本通过，都只支持"该证据覆盖的范围内成立"，不能外推成全站可用。

## 二、证据类型 → 工具 → 标注方式

| 证据类型 | 工具 | 应如何标注（context notes / 汇报） |
|---|---|---|
| 页面快照（observation） | `fetch_page` | 记 `pageId` + URL + HTTP code + 是否 `truncated`/`cacheHit`/`stale`。`truncated=true` 时预览不是全文——基于片段的下界结论要注明（"匹配 N 处"是 `read_page` 读完全文后的数，预览截断时只敢说"至少 N 处"） |
| 快照上的探针（derivation） | `read_page` / `analyze_html` / `inspect_rule` / `eval_js` | 记探针输入（选择器/JS 原文可略写）+ 关键输出（计数、首节点文本）。探针是"对该快照成立"，快照换了要重验 |
| 规则实跑（runtime evidence） | `debug_source` | 记阶段入口（`--章节URL` 等）+ 结果摘要（条数、字数、首项）。这是**真实执行**，但只覆盖这一次入口这一条链路 |
| 联网验收（runtime evidence） | `check_source(auto=true, refresh=true)` | 引用 `verdict.summary` 时必须同报 `stagesRan`/`stagesFailed`：summary 只说明"已跑且可判定的阶段"的结论，`unverified` = 没跑出可判定证据，**不等于**源坏了也不等于能用 |
| 静态校验（static check） | `check_source` 的 `validation` / `analyze_sources` | 是**静态**结构检查，绝不说它"观察到执行"；`validationOk=true` 不推出任何阶段可跑（verdict 代码也是这么写的：full 必须"校验+链路"双通过） |
| 网络抓包（observation） | HTTP 日志、`webview_capture`、`capture_once`、可见浏览器抓包 | 按 SKILL.md 抓包口径标注来源通道：`webview_capture` 是 OkHttp 供给/观察、非 WebView 原生栈；`capture_once` 逐跳 3xx 落 `capture_hop`；被拦跳记 `BLOCKED`、无体 POST 只记 `OBSERVED_ONLY`。跑完即销毁的会话在 notes 里留 `cap:contextId` |
| 验证状态（observation） | `get_verification_status` / `browser_verify` / 验证类响应的 `evidence` 字段 | 记 `status`/`marker`/`viaWebView`/`sessionId`；过盾成功属"该次会话内成立"，不写"该站已永久免验" |
| 用户手动操作（外部证据） | 可见浏览器抓包、验证中心人工过盾 | 标注为"人工完成/外部来源"；`checkResults` 外部传入的证据属"未核验来源"（verdict note 原文），引用时说明 |
| 环境状态（observation） | `get_domain_modes` / `get_cookies` / `get_logs` | 记读取时刻；Cookie/模式会随后续抓取变化，时间敏感的结论注明取证时点 |

跨阶段衔接的证据是 URL：上一阶段 derivation 产出的 `bookUrl`/`tocUrl`/`chapterUrl` 能实际流入下一阶段入口，才算链路连通——字段提取对了但 URL 不可达，链路仍是 unknown。

## 三、静态 ≠ 运行：沙箱与真机的覆盖边界

书源场景把这条原则拆成两层：**读规则/读代码是静态，`debug_source`/`check_source`/`eval_js` 是运行**；而**沙箱运行不等于官方 App 运行**。以下边界全部按 unknown 处理，不许外推：

- **`loginCheckJs`**：官方 App 在每次请求后执行；Studio 沙箱不执行（`EmbeddedLegadoRuntime` 无调用点）。沙箱过盾成功不能推出成品源过盾逻辑可用（`verification.md`、`troubleshoot.md` §4）。
- **`java.getElement(s)`、部分 `java.lang.*`、`headerMap`**：沙箱未实现/不注入，规则在沙箱跑通≠官方 App 能跑（`js-api.md`「明确不可用」清单）；反向同理，`java.bytesToStr` 是沙箱专有、官方未验证。
- **list 型 `result` 的 JS 行为**：`content-rules.md` 注明"真实 App 实测"是清单项——`nextContentUrl`/`nextTocUrl` 的 `<js>` 在沙箱可跑，但 list→String 转换的边界只能到官方 App 验证。
- **外部 jsLib**：沙箱不加载 `http(s)` 形式的 `jsLib`，只在 `lines` 留诊断。引用外链库的源，沙箱内的 JS 执行证据不覆盖库函数本体。
- **WebView/字体/验证码链路**：沙箱 `java.webView`/`startBrowserAwait`/`getVerificationCode`/`queryTTF`/`replaceFont` 已接通，但与官方 App 的交互路径不同；沙箱通过 = 逻辑可行，真机交互仍是 unknown。
- **媒体源（type>0）**：`check_source` 正文链路无法用字数证明可播/可显示，verdict 封顶 `partial`——把"音频/图片源全链路可用"写成结论就是越界。
- **快照时效**：快照 5 分钟新鲜、闲置 30 分钟过期、跨进程恢复标 `stale`；rrssk 签名按天滚动（`troubleshoot.md` §11）。所有"基于快照"的结论都带时间窗，跨窗复验必须 `refresh=true`。
- **比较/查重**：`analyze_sources` 的同 URL 冲突、同域候选只有"当前分页覆盖的版本"内成立；说"无重复"前确认 `list_sources` 已分页读完，否则报 unknown。

## 四、未知是一等公民：登记清单

遇到下列情况，在 `update_context` 的 notes 里显式登记一条 `UNKNOWN:`，**禁止用失败或"应该没问题"掩盖**：

| 未知 | 需要补的权威/环境 |
|---|---|
| 验证码/JS 挑战/CF 盾后的真实页面结构 | 人工过盾（`browser_verify`）后的 `fetch_page` 快照 |
| 登录墙/付费章节的正文规则 | 用户账号态 Cookie（`set_cookie`/`apply_webview_cookies`）或用户实测 |
| WASM/字体反爬的 gid→汉字映射 | 字形轮廓比对（`queryTTF`/`replaceFont`），`decodeWoff2` 的码点→gid 不构成映射 |
| 沙箱不支持的钩子与 API（loginCheckJs、getElement、外部 jsLib、transformed glyf 的 woff2） | 官方阅读 App 真机实测，或改写方案 |
| 某阶段站点根本没有（无搜索、无发现） | 标"不支持"，**不是**失败也不是通过 |
| `verdict.summary=unverified`（任何阶段没跑出可判定证据） | 具体入口重跑；报告"未判定"而不是脑补 |
| 环境故障（MCP 断连、网络超时） | 恢复环境后重跑；期间该阶段证据状态记 unknown，不当规则故障改规则 |
| 单点样本 vs 全站 | 换一组关键词/书籍做烟雾测试前，"全站可用"一律 unknown |

**边界案例（真实报错）**：书源 JS 调 `java.get(url, headers)` 时 `headers` 传 null/undefined，曾在 `AnalyzeUrl.get` 触发 `parameter headers` 的 NPE。教训：JS→Java 调用的**参数契约本身**是要取证的对象——沙箱与官方 App 对空参/缺省参的容忍度可能不同，用最小 `eval_js` 探针分别验证 `java.get(url)`、`java.get(url,{})`、`java.get(url,'{}')` 的实跑行为，把"第 N 参可否为 null"记为已知事实而非默认假设。

## 五、summary-first 与比较纪律

- 大结果一律先读摘要：`fetch_page` 预览标准 `auto` 首次缺省 6000 字（`?compact=1` 的 `auto` 未传 `maxChars` 时缺省 2000 字；`preview` 模式任意连接缺省 6000 字；`maxChars` 显式传入时优先，区间 0..12000；`responseMode="reference"` 不回正文，全文仍在 `pageId`）、调试输出截断（正文 4000/样本 800/目录 20 条）、超 12000 字自动转 `resultId`+预览。用 `read_page`/`read_result` 按 offset/limit 分页，**不要重跑同一工具**——`cacheHit=true`、body 空是正常复用，`reference` 与 `auto` 缓存命中同样不回正文，正文只在 `pageId`。
- 声明"新增/缺失/不同"必须双侧覆盖：比较两个版本的书源、两页快照、两次抓包时，没读完的一侧报 unknown。"搜索页 A 有 banner、B 没有"只在 A、B 都完整读完时可下。
- 有界搜索保持有界：`analyze_html` 计数为 0 只说明"该快照上该选择器 0 命中"，不推出"页面没有这个元素"（动态渲染/懒加载/验证页都可能假空）——先用观察确认快照本身是目标页面。

## 六、发现台账（notes 固定格式）

`update_context` 的 notes 按此结构记（≤4000 字符，每行一条）：

```
阶段: 正文 | 目标: xxx站正文规则
E1 [obs] pageId=p12 https://x/ch/1 code=200 整页12k字
E2 [drv] E1 上 .content@html → 3820字, 含"本章完"尾
E3 [rt ] debug_source --章节URL → content 3810字, nextContentUrl 1条
E4 [drv] check_source verdict: full (4/4, validationOk)
INF1 [inf] 单页章节(翻页规则空→不翻页) — 样本n=2, 需末章复验
U1  [unk] 末章"下一章"边界 → 需官方App实测 list型result
下一步: 只验末章 nextContentUrl
```

规则：**每次改规则必须由一条新证据驱动**；汇报时逐条引用 E/INF/U 编号；"完成" = 所有声明支持的阶段有 rt 级证据 + 台账里无未关闭的 U。

## 七、完成前自查（对照原任务清单）

1. 每个"已完成"阶段：指得出至少一条 rt 级证据（debug/check 实跑），且该证据覆盖本次入口链路？
2. 每条结论标了级别？inference 有没有写成 observation（"我看到"≠"我推断"）？
3. 未跑/未覆盖阶段是列成 unknown，还是被一句"应该可用"带过？
4. 引用的快照是否仍在时效内（5 分钟/`stale`）？跨天证据有没有重验？
5. 验证/Cookie/域名模式等会话态证据，有没有被写成"永久成立"？
