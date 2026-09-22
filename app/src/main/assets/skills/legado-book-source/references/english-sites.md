# 英文/国际站书源专题

做英文站书源前读本文件；语料现状与适配细节以知识库 `英文站收录与盲区-20260921.md`、`英文站适配反推-主流站.md`、`英文站适配反推-空白域.md` 为准。

## 第一步：查重（MCP-first）

1. `match_sources` 按域名/站名捞语料；`list_sources` 看本地工坊是否已有。
2. 本轮已收录 16 条英文源（royalroad / novelfire / scribblehub / libread / fanmtl / novelsemperor / yonglibrary / freewebnovel / novelfull / ranobes / grimmstories / divinedaolibrary / wtr-lab / readnovelfull / wuxiaworld / novelhall）。同名站点先 `get_source` 读原文，不要重造。
3. `novelbin.com` 已有源；`lightnovelworld.org` 已关站（不要为该域写新源）。

## 第二步：royalroad 实测规则（唯一活页验证过的底本）

| 阶段 | 规则 |
|---|---|
| 搜索 | `https://www.royalroad.com/fictions/search?title={{key}}`，列表 `.fiction-list-item` |
| 详情-书名 | `.fic-title h1` |
| 详情-作者 | `.fic-title h4 a` |
| 详情-封面 | `img.thumbnail` |
| 详情-简介 | `div.description` |
| 详情-分类 | `span.tags a` |
| 目录 | `table#chapters tr.chapter-row`，章节名 `a.0@text`、链接 `a.0@href`（备选：详情页 `window.chapters` 内 JSON 数组，`@js:` 解析） |
| 正文 | 章节页 `div.chapter-inner.chapter-content`（备选父容器 `div.portlet-body`） |

## 第三步：公仓空白站的反推制法

1. GitHub 找高星适配/刮削仓（下载器优先：WebToEpub、FanFicFare、mloader、ComicKhan、kisskh-dl、novel-scraper-toolkit 等）。
2. 读适配器源码，提炼四要素：搜索端点/返回格式、目录来源（页面 JSON / API 分页 / DOM）、正文容器、反爬防护（CF、token、签名、protobuf）。
3. 迁移成 Legado 规则串：纯 API 用 `@js:`/json 流；DOM 清晰用 css 流；防护重的先评估 WebView + webJs 能否过，过不了不要硬写。
4. `fetch_page` 抓活页 → `analyze_html` 在选择器快照上验证 → `debug_source` 调试 → `save_source` → `check_source(refresh=true)` 验收。**未经活页验证的规则必须标注待实测。**

## 第四步：域 verdict 速查

| 站点 | 做法 |
|---|---|
| quotev.com | 纯 DOM 无反爬，css 流直接写 |
| wattpad.com | API+HTML 双流，选择器见适配笔记 |
| foxaholic.com | CSS 明确，CF 重，先试 WebView |
| comick.dev | JSON API 干净，@js 流；漫画图片站 |
| mangago.me | 只做目录/元信息，图片成本高 |
| mangaplus / kisskh | protobuf+图片 XOR / 视频+kkey，Legado 做不了，放弃 |

## 采集端网络注意

本机与云端环境出口被多数英文站 CF/geo 挡（521/403/超时）。活页实测优先让用户在手机网络执行，或走海外出口；royalroad 可从云端环境直接验证。
