# 语料库：26861 书源特征索引

内置语料库由 26861 个现成书源去重而成，按内容规则签名聚成 **1838 个模板族**；同族 = 同 CMS / 同模板结构，规则骨架基本一致，可参考代表样例改写。

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

## 语料写法共识（2026-09-22 全量统计，n=26,861）

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

语料由 `scripts/build_corpus.py` 从去重书源生成；重新生成可在任意 python3 环境运行。

- 输入：`Mina/yuedu-source-hub` 采集管线产出的 `out/export/bookSource-merged.json`（16 个 GitHub 仓库 + 7 个独立站点订阅源合并去重，公共字段以官方规则为准）。
- 输出：`index.json` + `shards/` 分片；成员多的族拆成 `f_XXXX-N.json` 多个 part，读取时自动排序合并，对调用方透明。
- 重建 / 复跑 / 排障流程见 `Mina/yuedu-source-hub/AUTOMATION.md`。
