# 阅读书源参考路由

每轮创建、修改或继续调试先读 `SKILL.md` 与本文件，再只加载当前阶段需要的参考。客户端读不到本地文件时用 MCP 工具分页读取：`get_skill_reference("legado-book-source", "references/xxx.md", offset, limit)`。

## MCP-first 基线

默认在书源工坊真实运行时中工作：

1. `match_sources` 语料命中（域名/站名），再 `list_sources` 查重。
2. 已有源用 `get_source` 读取原文；语料命中用 `get_corpus_source` / `get_corpus_shard` 取底本。
3. `fetch_page` 抓页存快照，用 `analyze_html` / `eval_js` 在快照上做探针。
4. 用 `debug_source` 验证当前阶段（JSON 直接传入，不 save）。
5. 完成后 `save_source` 保存 → `get_source` 回读 → `check_source(refresh=true)` 实时验收。
6. 需要网络级证据时按 SKILL.md「抓包取证」选工具：自动探测 `webview_capture`，单次请求逐跳证据 `capture_once`，要人点页/翻页则提示用户开 App 可见浏览器抓包；读取统一 `list_captures` → `get_capture` / 活会话 `poll_capture` → `get_http_log` → 二进制 `get_capture_resource`。`webview_capture` 是 OkHttp 供给/观察证据（非 WebView 原生网络栈抓包），跑完即销毁、不可交互；URL/query 可能含令牌，转贴前检查。
7. 不要引导用户打开已删除的「项目列表」。本地书源在底栏「书源」页，可导入至阅读或删除。排查请用 `get_logs` / `get_http_logs` / `get_crash_logs`；日志页支持一键导出当天 HTTP/操作日志，需要原始报文时让用户点「导出」直接贴出。

MCP 连接故障属于环境故障。先恢复连接，不以本地猜测代替应用内调试。旧 `scripts/legado-debug.py` 仅是用户明确同意后的备用入口。

## 按阶段读取

| 当前阶段 | 必读文件 |
|---|---|
| 写规则之前 | `references/corpus.md`（语料命中） |
| 第三方搜索（rrssk）/ 主站搜索框跳外站 | 知识库 `第三方搜索逆向实战-rrssk.md` 第 0 节模板铁律 + 第 8 节换站复用清单（一律以书友社成品为骨架起步，禁止从零试错） |
| 目标站即阿里书屋 m.ali75.com 时的挑战页取证 | 知识库 `JS挑战与动态搜索避坑实战-阿里书屋.md`（仅同站取证参考，非通用模板；通用排查走下表「按失败现象」） |
| 目标站即爱书网 dm.aqxsw66.com 时的整本 TXT 伪目录取证 | 知识库 `TXT整本站伪目录实战-爱书网.md`（仅同站取证参考，非通用模板；通用整本站先查语料 `references/corpus.md` + `references/content-rules.md`） |
| 初始化、基础字段、详情、搜索、目录、正文 | `references/basics.md` |
| 生成 JSON 的字段清单与硬性合同（必填字段、分页/JS 位置约束） | `references/generation-contract.md`，字段逐项说明见 `references/template.yaml`（字段清单，非必套站点结构） |
| 取证口径：证据分级、观察/推导/运行/未知标注、覆盖边界、台账格式 | `references/evidence-methodology.md` |
| 正文多页合并 / 翻页 / 净化验收 | `references/content-rules.md` |
| 单站深案：番茄小说（官方接口/AES/字体混淆/插图） | `references/fanqie.md` |
| 英文/国际站书源（查重 → royalroad 底本 → 适配仓反推 → 活页验收） | `references/english-sites.md` |
| 请求失败、403、验证页、动态页面 | `references/verification.md`, `references/troubleshoot.md` |
| WebView、webJs、调用网页函数 | `references/webjs.md`, `references/troubleshoot.md` |
| 登录、按钮、回调、变量持久化 | `references/login.md`, `references/patterns.md` |
| 发现分类与布局 | `references/discovery.md` |
| 漫画正文与图片 | `references/comic.md`, `references/basics.md` |
| 多线路、多类型、跨页状态 | `references/patterns.md`, `references/js-api.md` |
| JS 基础语法、排序过滤与常用 API | `references/js-tutorial.md`, `references/js-api.md` |
| 订阅源/RSS | `references/basics.md`, `references/js-api.md` |

## 按失败现象读取

| 现象 | 读取文件 |
|---|---|
| 返回 verification_required / webview_mode_enabled | `references/verification.md` |
| 选择器无结果、字段为空或错位 | `references/basics.md` |
| 正文多页漏内容、翻页串章、净化后字数变 0 | `references/content-rules.md` |
| 正文插图丢失、正文出现 CSS 文本、字体乱码（番茄等站点） | `references/content-rules.md`, `references/fanqie.md` |
| 搜索无结果、乱码、分页或 URL 参数异常 | `references/basics.md`, `references/troubleshoot.md` |
| 搜索走外站（rrssk）、signJs 签名、`__snc` 403、q 绑定会话 | 知识库 `第三方搜索逆向实战-rrssk.md`（模板铁律 + 关键坑 + 换站清单） |
| HTTP 200 但响应是 JS 挑战页/拦截页（搜索选择器全空、不是规则故障） | `references/troubleshoot.md` 第 1-4、9-10 节，`references/webjs.md`；仅当目标站是阿里书屋 m.ali75.com 时再读知识库 `JS挑战与动态搜索避坑实战-阿里书屋.md`（同站取证，勿当通用模板） |
| 整本 TXT/下载站（一本书=一个文件，无章节概念） | `references/corpus.md`（先查语料模板族）、`references/content-rules.md`；仅当目标站是爱书网 dm.aqxsw66.com 时再读知识库 `TXT整本站伪目录实战-爱书网.md`（同站取证，伪目录骨架勿照抄到其他站） |
| 浏览器有内容但普通请求拿不到 | `references/troubleshoot.md`, `references/webjs.md` |
| 需要逐跳重定向证据 / 静态资源与页面事务取证 / 活会话持续追加 | SKILL.md「抓包取证」选择表：`capture_once` / `webview_capture` / 可见浏览器 → `list_captures` → `get_capture` / `poll_capture` → `get_http_log` / `get_capture_resource` |
| 403、验证盾、跳转、UA、Cookie | `references/verification.md`, `references/troubleshoot.md` |
| JS 报错、Rhino 兼容、java.* 用法 | `references/basics.md`, `references/js-api.md`, `references/js-tutorial.md` |
| 目录倒序、章节乱序、过滤广告章节 | `references/js-tutorial.md` |
| 发现页 JSON、分类和按钮布局 | `references/discovery.md` |
| 漫画图片不显示、403、懒加载或解密 | `references/comic.md`, `references/troubleshoot.md` |

## 领域知识库

技能参考之外的专题文档在知识库，用 `search_knowledge(关键词)` 找命中片段，再 `read_knowledge(path, offset, limit)` 分页读全文：

- 验证码识别与处理：`knowledge/图文验证码.md`
- 正文乱码 / 编码：`knowledge/Legado书源编码处理指南.md`
- CSS 选择器全集：`knowledge/css选择器规则.txt`
- 输出格式约束：`knowledge/书源输出模板_严格模式.md`
- 真实书源写法合集：`knowledge/真实书源模板库.txt`
- 书源 JS 入门教程（写法位置/内置变量/三板斧/API表/文件/字体/调试/常见坑）：`knowledge/js-beginner-tutorial.md`
- JS 逆向总纲（四阶段方法论、何时止损换通道）：`knowledge/js-reverse-s0-methodology.md`
- JS 逆向入门 SOP（反爬六分类、定位归因、重放测试、坑清单）：`knowledge/js-reverse-s2-basic.md`
- JS 逆向进阶（AST 反混淆、反调试对照、补环境、wasm、RPC、TLS 指纹）：`knowledge/js-reverse-s2-advanced.md`
- 逆向迁移到书源（调试通道、生态判定、过反爬底线、靶场→书源映射）：`knowledge/js-reverse-s2-booksource.md`
- 官方规则教程蒸馏（字段地图 / 搜索地址 / 调试方法 / 列表规则三语法，规则语义争议的最高准则）：`knowledge/legado-rules-map.md`

目标站出现动态签名/加密参数/动态 Cookie/字体反爬时，先按 `js-reverse-s2-basic.md` 的反爬六分类定位类型，再按对应专题操作；结论用 `eval_js` 验证后写进书源规则。

## 探针原则

在改规则前，用快照上的探针输出最小证据：

- 响应码和最终 URL
- HTML 长度与关键片段
- 候选选择器匹配数量
- 第一个节点的文本、属性和链接
- JavaScript 的输入值、输出值和异常

取证口径（证据分级、快照/实跑/静态校验的覆盖边界、unknown 登记、notes 台账格式）见 `references/evidence-methodology.md`；探针产出按该文四级标注，结论强度不超过证据强度。

需要网络级证据时：开启 HTTP 日志 → 复现一次 → 读取该次请求详情；要逐跳重定向或整会话取证时用 SKILL.md「抓包取证」里的抓包工具。不要批量复现制造噪音。

## 每阶段记录

用 `update_context(contextId, notes)` 保存：

- 当前阶段
- 本轮读取的参考文件
- 测试入口与预期值
- 修改字段
- `debug_source` 结果摘要
- 下一步只处理哪个字段或阶段
