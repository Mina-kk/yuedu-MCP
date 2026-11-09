# ui-core 实现报告（Rust → WASM 前端领域核）

契约：`docs/UI_CORE_CONTRACT.md`（未修改）。crate：`ui-core/`，包名 `studio-ui-core`，`cdylib + rlib`。
边界：所有导出函数只收 `&str`、只回 `String`（JSON），无 `serde_wasm_bindgen` 对象互转。

## 实际执行结果

| 步骤 | 命令 | 结果 |
|---|---|---|
| 单元 + 集成测试 | `cargo test --manifest-path ui-core/Cargo.toml` | **90 passed / 0 failed**（7 lib + 25 analyze + 16 compare + 18 filter + 24 evidence） |
| WASM release 构建 | `cargo build --manifest-path ui-core/Cargo.toml --target wasm32-unknown-unknown --release` | 成功，`studio_ui_core.wasm` 228,087 B |
| wasm-bindgen | `wasm-bindgen --target web --out-dir web-ui/src/wasm …` | 成功，`_bg.wasm` 201,617 B + `.js` 10,502 B + `.d.ts` |
| JS 真实调用 smoke | `node ui-core/smoke.mjs` | **12/12 ok**，全部导出函数经 JSON string 调用 |

工具链：rustc/cargo 1.99.1，wasm-bindgen CLI 0.2.130（与 `Cargo.lock` 中 crate 版本一致），target `wasm32-unknown-unknown`。
依赖全部来自本机 cargo 缓存，已生成并保留 `ui-core/Cargo.lock`（serde 1.0.229 / serde_json 1.0.154 / wasm-bindgen 0.2.130，全部 `=` 精确锁定）。

## 导出函数

- `core_version() -> "studio-ui-core/2.0.0"`
- `analyze_source_json(text)` → `{valid,sourceCount,sources[],issues[],formatted}`
- `compare_source_json(left,right)` → `{equal,changedTopLevelFields,identityChanged}`（非法输入另加 `error{code,message,path,side}`）
- `summarize_evidence(text)` → `{scope,status,label,tone,passed,notes}`
- `filter_sources(items_json,query)` → `{items,total}`（非法输入 `{error{...}}`）

## 实现要点

**严格 JSON 层（`src/json.rs`）**：`serde_json::Value` 对重复键静默 last-wins，契约禁止。因此先用手写递归下降扫描器遍历原始文本，记录每个重复键的 JSON 路径（`$.ruleSearch.bookList`、`$[1].k`），只有扫描干净才交给 `serde_json` 建值——两遍解析保证不丢数据。递归深度上限 128，与 serde_json 自身限制一致，超限报 `nesting_too_deep` 而非栈溢出。BOM 容忍；2 MiB（UTF-8 字节）上限在任何解析前检查。

**analyze**：单对象或数组；`bookSourceUrl` 缺失/空白/非字符串、`bookSourceName` 缺失/非字符串均为 issue；数组元素非对象报 `source_not_object` 并保留索引。阶段标志只看规则是否**有内容**：`{"content": ""}` 这种空壳不算正文规则。空搜索/缺阶段**不产生 issue**——草稿可保存。`formatted` 是完整文档本身（`preserve_order`，键序也保留），未知字段原样保留。身份逐字保留，不折叠 `/`、`#`、端口、大小写。

**compare**：整文档结构相等（未知字段参与、数组顺序与长度参与、`1` vs `1.0` 保守不同——serde_json 的 `Value` 本身就区分整数与浮点）。`changedTopLevelFields` 只给**字段名**，绝不带值，`header` 里的 token 不会泄漏。身份比较完整 `bookSourceUrl` 字符串，不做任何规范化；数组文档按有序身份列表比较。

**summarize_evidence**：判定顺序即诚实性顺序——① 硬失败状态（`reader_fields_unconfirmed`/`conflict`/`stale_state`/`session_replaced`/`session_unconfirmed`/`timeout`/`failed`/`uncheckable`/`reader_debug_incomplete`/`reader_failure_signals`/`reader_evidence_unmatched`/`not_in_reader`/`reader_partial`/`reader_executed`/`sandbox_only_not_reader_verified`/`error` 等）压倒一切，即使同时带 `readerSidePassed:true` 也不通过；② `machineVerdict=unverifiable` 永不通过；③ `VALIDATED` 归为 `scope:"static"`，沙箱 `full`/`verdictScope=sandbox_only` 归为 `scope:"sandbox"`，都不是阅读端通过；④ 只有 `deliveryStatus/status=reader_verified`，或 `readerSidePassed=true` 且 `identityMatches|confirmed` + `executionComplete|complete` + 无失败信号，才 `passed=true`；⑤ 无字段不推断，返回 `scope:"unknown"`。`check_source` 把阅读摘要嵌在 `readerExecution` 下：当外层是 `verdictScope=sandbox_only` 时按嵌套记录判定，不被外层沙箱范围误压。

**filter**：视图操作，条目原样返回、顺序不变、同 URL 多版本不合并不去重；只查 `bookSourceName/name/bookSourceUrl/siteUrl` 四个摘要字段（规则体里的文本不参与）；大小写折叠含 `ß→ss`、`İ→i`、全角→半角。

## 与契约的偏差 / 需要父模型确认

1. **`formatted` 是 JSON 值而非字符串**：契约写 `formatted?`，未规定类型。返回值为对象/数组本身（`preserve_order` 保留键序），Vue 侧 `JSON.stringify(result.formatted, null, 2)` 即可得到格式化文本。若前端契约要求字符串，改 `analyze.rs` 一行即可。
2. **`filter_sources` 非法输入的形态**：契约只规定成功形状 `{items,total}`，错误时返回 `{error:{code,message,path}}`（不含 `items`），与 `compare` 的错误风格一致。
3. **`tone` 取值**：`success|warning|danger|neutral`（另有 `info` 预留未使用）。
4. **2 MiB 而非 2,000,000 字节**：契约说「2M 字符，统一按 UTF-8 字节，前端文案说 MB」，取 2×1024×1024。

## 文件

```
ui-core/Cargo.toml
ui-core/Cargo.lock
ui-core/build-web.sh          # 父集成入口：test + wasm32 release + wasm-bindgen
ui-core/smoke.mjs             # Node 真实调用 smoke（12 项）
ui-core/src/lib.rs            # #[wasm_bindgen] 导出 + JSON 渲染
ui-core/src/version.rs        # CORE_VERSION / MAX_INPUT_BYTES
ui-core/src/json.rs           # 严格解析、重复键、大小限制、路径
ui-core/src/analyze.rs
ui-core/src/compare.rs
ui-core/src/evidence.rs
ui-core/src/filter.rs
ui-core/tests/{common,analyze_source,compare_source,filter_sources,summarize_evidence}.rs
web-ui/src/wasm/*             # 构建产物（studio_ui_core.js/_bg.wasm/.d.ts）
```

`web-ui/src/wasm/**` 是构建产物，父集成时由 `ui-core/build-web.sh` 重新生成；未创建也未修改任何 Vue 源码。

## 未做（按分工）

- Vue 侧 `import init, {…} from './wasm/studio_ui_core.js'` 的接线、页面渲染、设备实测——父模型负责。
- 未访问 `apikey-vault`；未提交、未推送；未改动 Android/Kotlin/Vue/Gradle。
