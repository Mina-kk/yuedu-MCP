# 语料库：17743 个唯一身份书源

内置语料库包含 **17743 个唯一身份书源、1544 个规则特征族**：由 29908 条候选（旧底库 26861 条 + YCK 快照 3047 条）按身份键全库去重与版本仲裁后整体重建，旧底库入选 15898 条、YCK 新入选 1845 条；1443 个待复核键与 2842 个隔离键不进入主库。重建后全局序号 `i` 与族编号 `f` 整体重新编号，与旧版本编号不对应。

身份键按源类型与保守归一化 URL 判断，保留协议、www、路径及有意义的 fragment 差异。本次是全库去重而非追加：同一身份键在主库只保留一个版本，规则实质相同的副本合并为一条，规则不同且可信时间可比较时保留较新者；时间缺失、相等或质量同级时整键转待复核，不猜「最新」。因此主库不再保留同身份多版本，但也不能把总数当成独立站点数。规则特征族按类型、请求方式和规则风格等特征分组，仅作结构参考，不保证同一 CMS（MCP 工具文案与返回字段沿用旧称「模板族」）。

入选只表示静态质量筛选通过，参考与隔离候选未混入；没有逐条联网验证，即使规则时间可信也不代表目标站当前可用。其中 2474 条入选记录缺少可信的规则时间，均不声称最新。下方 2026-09-22 写法统计仍以重建前的 26861 条底库为历史基数，未对本次重建重新统计。

## match_sources 返回字段

```json
{
  "query": "xbanxia.cc",
  "count": 1,
  "matches": [
    { "i": 123, "d": "xbanxia.cc", "n": "八戒书城", "t": 0, "f": "f_0002", "g": 9 }
  ],
  "hint": "..."
}
```

| 字段 | 含义 |
|---|---|
| `i` | 全局序号（传给 `get_corpus_source(i)` 取完整源） |
| `d` | 域名 |
| `n` | 书源名称 |
| `t` | 类型：0=文本 / 1=音频 / 2=漫画 / 3=文件 / 4=视频 |
| `f` | 模板族 ID（传给 `get_corpus_shard(f)` 取同族样例） |
| `g` | 位掩码：1=enabledCookieJar，2=有loginUrl，4=有loginCheckJs，8=语料含验证码特征，16=含Cloudflare特征，32=已禁用，64=四段规则不全 |

`limit` 参数 1..20，默认 10。按域名查最准；站名关键词用 contains 兜底。

## 命中后的两条路

- **同域命中** → `get_corpus_source(i)` 返回该源**完整 BookSource JSON 字符串**（已按官方公共字段清洗）。直接做底本，改域名与实测差异，不要从零写。
- **同族参考** → `get_corpus_shard(f)` 返回：

```json
{
  "familyId": "f_0002",
  "memberCount": 87,
  "searchMethod": "GET",
  "exemplarCount": 6,
  "exemplars": ["<完整书源JSON字符串>", "..."]
}
```

  样例按完整度优先挑 ≤6 个。**只学结构**：搜索方式、列表/正文容器层级、URL 拼接、分页写法；域名、编码、字段文案必须按目标站实测改写，勿照抄。

## 位掩码使用提示

- `g & 8`（验证码）或 `g & 16`（CF）：目标站大概率也有盾，提前读 [`verification.md`](verification.md) 与知识库「图文验证码」。
- `g & 2 | 4`（loginUrl/loginCheckJs）：站点要登录，读 [`login.md`](login.md)。
- `g & 64`（规则不全）：该源四段规则缺阶段，别拿它当完整模板。

## 语料写法共识（历史：2026-09-22 全量统计，n=26,861，重建前底库）

> 该统计针对重建前的 26861 条底库，**未随本次全库去重重建重新统计**，只作历史参考；数值、比例与序号均不代表当前主库。

写规则前先查这里，与共识冲突的写法要能说出理由：

| 指标 | 数值 |
|------|------|
| ruleToc.chapterList 纯 CSS（无 @js:/`<js>`） | 23,496（87.5%） |
| chapterList 含 `@js:` / `<js>` / `$` JSON path | 708 / 1,109 / 869 |
| JS 型 chapterList 同时配 chapterName+chapterUrl | 1,784/1,817（98.2%） |
| nextTocUrl 出现率 | 8,460（31.5%），惯用法以 `text.下一页@href` 为主 |
| nextContentUrl 出现率 | 11,587（43.1%） |
| weight / lastUpdateTime / customOrder / enabledExplore 出现率 | 26,857 / 26,859 / 26,856 / 26,856 |
| respondTime / enabledCookieJar / bookSourceComment | 25,591 / 25,590 / 25,064 |
| customButton / eventListener（多数取默认 false） | 各 503 |
| base64DecodeToByteArray / strToBytes 出现源数 | 70 / 14 |
| `new Packages.java.lang.String` / `java.bytesToStr` 出现源数 | 0 / 2（该两件套是 Rhino 官方语法、菠萝猫实测可用，但并非语料主流；语料主流是 `java.base64Decode` 与 `createSymmetricCrypto(...).decryptStr(...)`） |
| java.getElement / java.getElements 出现源数 | 236 / 510（官方 App 真实可用；Studio 沙箱未实现，调试期报错） |
| 坏 API 出现源数：utf8ToGbk / currentTimeMillis / decodeURI / reversed() / new java.lang.String | 0 / 0 / 0 / 0 / 1 |
| java.* 裸返回值直接 `.replace(/正则/,...)` | 59（非主流，且 Rhino 有「选择不明确」风险） |

## 分支兼容清单

产出书源只面向官方阅读（Legado）公共字段：

- 不要用 `mainJs` 纯 JS 单文件源：旧分支不识别。
- JS 规则避免 `Packages.java.*` 反射：iOS JavaScriptCore 无此能力。仅为兼容 iOS 分支的取舍——Android 官方阅读 App 的 base64 字节解码仍用标准两件套（`java.base64DecodeToByteArray` + `new Packages.java.lang.String(bytes, "UTF-8")`），不要因此弃用。
- header 必须是合法 JSON 字符串。
- 保存文件去 UTF-8 BOM。

## 生成方式

语料由 `scripts/build_corpus.py` 从书源快照构建，全库去重重建由 `scripts/reconcile_corpus.py` 驱动；重新生成可在任意 python3 环境运行。

- 输入：采集管线合并去重后导出的书源数组 `bookSource-merged.json`（公共字段以官方规则为准）。
- 输出：`index.json` + `shards/` 分片；成员多的族拆成 `f_XXXX-N.json` 多个 part，读取时自动排序合并，对调用方透明。
- 去重重建：`reconcile_corpus.py --base <现有语料> --src <新快照> --stage <新目录> --report <报告> --merge-source <来源标签>`，按身份键全库去重与版本仲裁后产出全新 stage；命令与切换流程见仓库根的 `BUILDING.md`。
