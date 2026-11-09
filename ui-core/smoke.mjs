// Node smoke test: import the wasm-bindgen output exactly as Vue would and
// call every exported function through the JSON-string boundary.
import init, {
  core_version,
  analyze_source_json,
  compare_source_json,
  summarize_evidence,
  filter_sources,
} from "../web-ui/src/wasm/studio_ui_core.js";

// `--target web` loads the .wasm via fetch(), which Node does not implement
// for file paths; pass the bytes explicitly. Browsers use the default path.
import { readFile } from "node:fs/promises";
const bytes = await readFile(new URL("../web-ui/src/wasm/studio_ui_core_bg.wasm", import.meta.url));
await init({ module_or_path: bytes });

const fail = [];
const check = (name, cond, extra) => {
  if (!cond) fail.push(`${name}: ${extra ?? ""}`);
  console.log(`${cond ? "ok  " : "FAIL"} ${name}`);
};

check("core_version", core_version() === "studio-ui-core/2.0.0", core_version());

const a = JSON.parse(analyze_source_json(JSON.stringify({
  bookSourceUrl: "https://example.com/s",
  bookSourceName: "示例",
  searchUrl: "https://example.com/search?q={{key}}",
  ruleToc: { chapterList: "id.list@tag.a" },
  ruleContent: { content: "id.c@html" },
  unknownField: { keep: [1, 2, 3] },
})));
check("analyze valid", a.valid === true && a.sourceCount === 1);
check("analyze stages", a.sources[0].hasSearch && a.sources[0].hasToc && a.sources[0].hasContent && !a.sources[0].hasExplore);
check("analyze keeps unknown", a.formatted.unknownField.keep.length === 3);

const bad = JSON.parse(analyze_source_json('{"bookSourceUrl":"https://a/","bookSourceUrl":"https://b/","bookSourceName":"A"}'));
check("analyze duplicate key rejected", bad.valid === false && bad.issues.some(i => i.code === "duplicate_key"));

const c = JSON.parse(compare_source_json('{"bookSourceUrl":"https://a/s","bookSourceName":"A"}', '{"bookSourceUrl":"https://a/s/","bookSourceName":"A"}'));
check("compare trailing slash is identity change", c.equal === false && c.identityChanged === true);

const e1 = JSON.parse(summarize_evidence(JSON.stringify({ status: "VALIDATED", scope: "static" })));
check("VALIDATED not a reader pass", e1.passed === false && e1.scope === "static");
const e2 = JSON.parse(summarize_evidence(JSON.stringify({ status: "legacy_debug_only", machineVerdict: "unverifiable" })));
check("unverifiable not a pass", e2.passed === false);
const e3 = JSON.parse(summarize_evidence(JSON.stringify({ status: "reader_verified", readerSidePassed: true, identityMatches: true, executionComplete: true, failureSignals: [] })));
check("reader_verified passes", e3.passed === true && e3.scope === "reader");
const e4 = JSON.parse(summarize_evidence(JSON.stringify({ status: "stale_state", readerSidePassed: true, identityMatches: true, executionComplete: true, failureSignals: [] })));
check("stale_state never passes", e4.passed === false);

const f = JSON.parse(filter_sources(JSON.stringify([
  { bookSourceUrl: "https://a/", bookSourceName: "书源 Alpha" },
  { bookSourceUrl: "https://b/", bookSourceName: "Beta" },
  { bookSourceUrl: "https://a/", bookSourceName: "书源 Alpha v2" },
]), "alpha"));
check("filter keeps same-url versions", f.total === 2 && f.items.length === 2);
check("filter unicode", JSON.parse(filter_sources(JSON.stringify([{ bookSourceUrl: "https://x/", bookSourceName: "中文源" }]), "中文")).total === 1);

if (fail.length) { console.error("\nFAILURES:\n" + fail.join("\n")); process.exit(1); }
console.log("\nall smoke checks passed");
