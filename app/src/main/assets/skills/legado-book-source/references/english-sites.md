# 英文/国际站书源专题

做英文站书源前读本文件。这里给的是**候选名单、历史选择器线索与反推方法**，不给任何站点「可用/不可用」「搜索通/不通」的结论，也不指定某个站是唯一底本或据此放弃某站。语料与适配细节另见知识库 `英文站收录与盲区-20260921.md`、`英文站适配反推-主流站.md`、`英文站适配反推-空白域.md`（均为历史笔记）。

**铁律：每个站都要在当前用户网络、当前日期重新抓页取证。** 另一台机器、另一天、另一个出口的结果都不迁移；本文件里的任何站点名都不构成「已通过」或「做不了」的判断。

## 第一步：查重（MCP-first）

1. `match_sources` 按域名/站名捞语料；`list_sources` 看本地工坊是否已有。
2. 历史轮次留下 16 个候选站名，可作查重线索（royalroad / novelfire / scribblehub / libread / fanmtl / novelsemperor / yonglibrary / freewebnovel / novelfull / ranobes / grimmstories / divinedaolibrary / wtr-lab / readnovelfull / wuxiaworld / novelhall）。**命中只说明语料里可能有现成 JSON 可参考**，先 `get_source` 读原文，再按本次实测决定改还是重写。
3. `novelbin.com` 与语料现有同站源重叠，先查重再动手。
4. `lightnovelworld.org` 见到该域的旧源或旧教程，先确认域名当前返回什么，再决定是否沿用；不要凭旧记录下结论。

## 第二步：历史选择器参考（示例站，需本次重测）

下表是 2026-09-21 某次抓页留下的结构记录，**只当一个站的样例**，既不证明该站现在可用，也不代表它是首选底本。用前当日重新 `fetch_page` 核对结构与选择器；换站、换版式、同站改版都要重新取证。

| 阶段 | 历史参考规则 |
|---|---|
| 搜索 | `https://www.royalroad.com/fictions/search?title={{key}}`，列表 `.fiction-list-item` |
| 详情-书名 | `.fic-title h1` |
| 详情-作者 | `.fic-title h4 a` |
| 详情-封面 | `img.thumbnail` |
| 详情-简介 | `div.description` |
| 详情-分类 | `span.tags a` |
| 目录 | `table#chapters tr.chapter-row`，章节名 `a.0@text`、链接 `a.0@href`（备选：详情页 `window.chapters` 内 JSON 数组，`@js:` 解析） |
| 正文 | 章节页 `div.chapter-inner.chapter-content`（备选父容器 `div.portlet-body`） |

可迁移的只是「结构思路」：列表容器取 `a@href`、详情页取标题/作者/封面、目录按行取章节名与链接、正文取主容器。具体节点的选择器一律以本轮快照为准。

## 第三步：从适配仓反推写法

1. GitHub 找高星适配/刮削仓（下载器优先：WebToEpub、FanFicFare、mloader、ComicKhan、kisskh-dl、novel-scraper-toolkit 等）。
2. 读适配器源码，提炼四要素：搜索端点/返回格式、目录来源（页面 JSON / API 分页 / DOM）、正文容器、反爬防护（CF、token、签名、protobuf）。**源码只说明适配器怎么写，不说明站点此刻是否可访问、返回结构是否还一致。**
3. 迁移成 Legado 规则串：纯 API 用 `@js:`/json 流；DOM 清晰用 css 流；需要浏览器态的先评估 WebView + webJs 能否过。
4. 当日闭环：`fetch_page` 抓活页 → `analyze_html` 在选择器快照上验证 → `debug_source` 调试 → `save_source` → `check_source(refresh=true)` 验收。**未经当日活页验证的规则必须标注待实测。**

## 第四步：接口画像（反推材料，不代替实测）

下表只描述适配器读出来的接口形态，用来估迁移成本，不是站点结论。网页可达与否、接口是否匹配都以当日实测为准；`apiProfile` 不匹配只说明没有结构化快速通道，不是页面失效。

| 站点 | 适配器看到的接口形态 | 迁移时要先确认 |
|---|---|---|
| quotev.com | 纯 DOM，无专门 API | 详情与目录的容器结构 |
| wattpad.com | API + HTML 双流 | 取数顺序与分页 |
| foxaholic.com | WordPress 结构，CSS 组织清晰 | 防护层能否过 WebView |
| comick.dev | REST JSON | 分页与鉴权；漫画图片在阅读端的支持 |
| mangago.me | 目录/元信息以 DOM 为主 | 章节图片获取成本 |
| mangaplus.shueisha.co.jp | Protobuf 二进制 + 图片处理 | 原生 JS 解析是否需要额外实现 |
| kisskh | 视频站，带签名与 kkey | 是否属于文本阅读场景 |

以上都是「怎么做」的材料：能不能做、当前返回什么，仍以本轮实测为准。

## 网络与取证纪律

- 采集机出口被部分英文站 CF/geo 挡住是**采集环境**现象（521/403/超时），不能外推成「用户手机也打不开」或「站点挂了」。
- 需要用户网络或海外出口时如实说明；推断性结论一律标 unknown，不写进成品汇报当事实。
- 汇报只写本轮哪个日期、哪个网络、哪几段实测结果；不写「该站搜索不可用」「该站已关站」这类固定结论。
