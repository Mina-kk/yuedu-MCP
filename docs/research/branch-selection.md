# 热门活跃阅读分支筛选

## 筛选方式

索引站用于发现候选，不把页面缓存 star 或“源码更新”字段直接当作实时事实。[1] 本次用 GitHub API 重新读取仓库元数据、默认分支 HEAD、完整文件树并按固定提交下载规则源码。筛选门槛：未归档、至少 150 star、近 90 天默认源码有更新且能审查阅读规则实现；日期和 star 是采集时快照，不代表永久状态。

采集时刻：2026-11-09 16:10 UTC。源码下载均校验 Git blob SHA；机器证据位于本 profile `research/yuedu-refactor/`。

## 主兼容依据

| 分支 | Star | 固定提交 | HEAD提交日 UTC | 角色 |
|---|---:|---|---|---|
| MD3 — HapeLee/legado-with-MD3 | 6917 | `2e2c63c3a7d2786719919fd0b11a197af9cf29bd` | 2026-11-08 | 热门主参照，解析和 JS 作用域重点 [2] |
| 阅读 Beta — LegadoTeam/legado | 1870 | `330e606c4d57e08adc056ff8ab7a4132c784da9a` | 2026-11-01 | 现有运行时血缘基准 [3] |
| Archive — rimvoid/legado | 663 | `e111bf666199505d783e6910d8609f0e5cb043e0` | 2026-09-24 | Sigma 系列扩展差异 [5] |
| MAX SUM — Suml-1/Legado_Max | 426 | `b3864087588ba624cb376e9f04772be2b6385656` | 2026-11-07 | 活跃 MAX 家族差异 [4] |
| 薯条 KMP — huajideshutiao/legado | 333 | `83657e54829b7603aa392bf072283805769ad385` | 2026-11-02 | common/JVM/native 语义边界和 URL 编码 [6] |
| NG — joestar817/legado_NG | 207 | `7b6087410f287511080f2a93b82795231f4b72fd` | 2026-11-05 | Sigma 下游特性差异 [7] |
| T — skybbk1001/legadoT | 152 | `b0c6067996a9467ed35c32b4de69171979a284de` | 2026-11-05 | 调度/JS API 扩展，仅有证据才纳入 [8] |

以上主要为 GPL-3.0 家族，不合并它们互相冲突的扩展。公共规则语义与分支能力单独记录，profile 不能只是名称标签。

## 交叉参考（不能反过来定义 Android 规则）

| 项目 | Star | 固定提交 | 用途 |
|---|---:|---|---|
| 彩读 ColorTxt | 1976 | `0e54e3aebedb61f2b6de16f8b40cd92da488275a` | 活跃桌面重写，存在 TypeScript Legado 引擎；用于发现移植坑，不优先复制 MPL 文件进 GPL 项目 [10] |
| Kototoro | 677 | `7ca2734fba7b830d8008012e4b2de1a052c4bc43` | 活跃 Android 兼容层，明确以 MD3 模式实现；可参考运行时端口分离和离线回归测试，逐文件保留许可 [9] |
| 阅读 C | 209 | `b3cb9f5405112d5ae1b99705bf62886b81a9d5f4`（默认 own） | 近期发版但提交时间经发布脚本固定，不能用时间臆测最新源码；非标准 GPL-PLUS 许可，暂只研究差异，不直接搬特有实现 [13] |

## 不作为本轮实现依据

- 鸿蒙版虽然高星、有新安装包，但公开 README 明说不再开源、公开仓库仅发包；无法审查当前规则实现，不声称忠实兼容。[11]
- 原 Sigma 3067 star，默认源码 100 天未更新：保留血缘参考，不纳入“近 90 天持续更新”的主集合。[12]
- LightNovelReader 2332 star 且活跃，但走独立插件数据源体系；所读源码树没有本次要移植的 Legado AnalyzeRule 引擎，不以热度替代规则相关性。[14]
- MR 111 star，低于本轮阈值；暂不扩展实现面。[15]
- 索引上标明弃坑的原版、原 MAX、旧 Tauri、亦搜、ReadCat 不进入主集合。[1]

## 后续产物

- `rule-semantics.md` / `rule-cases.json`：规则解析与元素流水线。
- `js-network-semantics.md` / `js-network-cases.json`：JS、请求、分页、平台 API。
- 兼容承诺只绑定列出的固定提交和已实际运行测试；研究源码不等于所有分支 APK 已实测。

## Sources

[1] https://legado.erchuang.online
[2] https://github.com/HapeLee/legado-with-MD3
[3] https://github.com/LegadoTeam/legado
[4] https://github.com/Suml-1/Legado_Max
[5] https://github.com/rimvoid/legado
[6] https://github.com/huajideshutiao/legado
[7] https://github.com/joestar817/legado_NG
[8] https://github.com/skybbk1001/legadoT
[9] https://github.com/Kototoro-app/Kototoro
[10] https://github.com/ssnangua/ColorTxt
[11] https://github.com/mgz0227/legado-Harmony
[12] https://github.com/Luoyacheng/legado-E
[13] https://github.com/CCSSNE/legadoC
[14] https://github.com/dmzz-yyhyy/LightNovelReader
[15] https://github.com/DandanLLab/mr
