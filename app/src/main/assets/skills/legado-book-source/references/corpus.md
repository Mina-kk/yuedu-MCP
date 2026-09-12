# 语料库：4256 书源特征索引

内置语料库由 4256 个现成书源去重而成，按内容规则签名聚成 **696 个模板族**；同族 = 同 CMS / 同模板结构，规则骨架基本一致，可参考代表样例改写。

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

## 分支兼容清单

产出书源只面向官方阅读（Legado）公共字段：

- 不要用 `mainJs` 纯 JS 单文件源：旧分支不识别。
- JS 规则避免 `Packages.java.*` 反射：iOS JavaScriptCore 无此能力。
- header 必须是合法 JSON 字符串。
- 保存文件去 UTF-8 BOM。

## 生成方式

语料由 `scripts/build_corpus.py` 从去重书源生成；重新生成可在任意 python3 环境运行。
