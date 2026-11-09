//! `analyze_source_json` — static draft check for a book-source document.
//!
//! What this does: parse strictly, describe each source, flag structural
//! problems, and hand back the untouched document as `formatted`.
//!
//! What this deliberately does *not* do: decide whether a source works. A
//! missing search/explore/toc/content rule is a perfectly saveable draft, so
//! it is reported as a stage flag and never as an issue. No network, no rule
//! execution, no reader-side conclusion.

use serde_json::{Map, Value};

use crate::json::{parse_strict, Issue, Parsed};

/// Field names that carry a rule for the given stage, in preference order.
const SEARCH_KEYS: &[&str] = &["searchUrl"];
const EXPLORE_KEYS: &[&str] = &["exploreUrl"];
const TOC_KEYS: &[&str] = &["ruleToc"];
const CONTENT_KEYS: &[&str] = &["ruleContent"];

/// Analyze a book-source document (single object or array).
pub fn analyze(text: &str) -> Value {
    let doc = match parse_strict(text) {
        Parsed::Ok(v) => v,
        Parsed::Rejected(issues) => return rejected(issues),
    };

    let mut issues: Vec<Issue> = Vec::new();
    let mut sources: Vec<Value> = Vec::new();

    match &doc {
        Value::Object(map) => {
            describe(map, "$", &mut sources, &mut issues);
        }
        Value::Array(items) => {
            for (i, item) in items.iter().enumerate() {
                let path = format!("$[{i}]");
                match item {
                    Value::Object(map) => describe(map, &path, &mut sources, &mut issues),
                    _ => issues.push(Issue::new(
                        path,
                        "source_not_object",
                        "数组元素不是对象，无法作为书源",
                    )),
                }
            }
        }
        _ => {
            return rejected(vec![Issue::new(
                "$",
                "top_level_not_object_or_array",
                "顶层必须是书源对象或书源数组",
            )])
        }
    }

    let valid = issues.is_empty();
    let mut out = Map::new();
    out.insert("valid".into(), Value::Bool(valid));
    out.insert("sourceCount".into(), Value::from(sources.len() as u64));
    out.insert("sources".into(), Value::Array(sources));
    out.insert(
        "issues".into(),
        Value::Array(issues.iter().map(Issue::to_json).collect()),
    );
    // `formatted` is the document itself with every field and value intact.
    // serde_json's map is order-preserving, so the original key order survives;
    // even under a sorting map the JSON *semantics* are unchanged.
    out.insert("formatted".into(), doc);
    Value::Object(out)
}

fn rejected(issues: Vec<Issue>) -> Value {
    let mut out = Map::new();
    out.insert("valid".into(), Value::Bool(false));
    out.insert("sourceCount".into(), Value::from(0u64));
    out.insert("sources".into(), Value::Array(Vec::new()));
    out.insert(
        "issues".into(),
        Value::Array(issues.iter().map(Issue::to_json).collect()),
    );
    Value::Object(out)
}

fn describe(
    map: &Map<String, Value>,
    path: &str,
    sources: &mut Vec<Value>,
    issues: &mut Vec<Issue>,
) {
    let url = map.get("bookSourceUrl");
    let name = map.get("bookSourceName");

    match url {
        None => issues.push(Issue::new(
            format!("{path}.bookSourceUrl"),
            "missing_book_source_url",
            "缺少 bookSourceUrl：书源身份不能为空",
        )),
        Some(Value::String(s)) if s.trim().is_empty() => issues.push(Issue::new(
            format!("{path}.bookSourceUrl"),
            "book_source_url_blank",
            "bookSourceUrl 为空白：书源身份不能为空",
        )),
        Some(Value::String(_)) => {}
        Some(_) => issues.push(Issue::new(
            format!("{path}.bookSourceUrl"),
            "book_source_url_not_string",
            "bookSourceUrl 必须是字符串",
        )),
    }
    match name {
        None => issues.push(Issue::new(
            format!("{path}.bookSourceName"),
            "missing_book_source_name",
            "缺少 bookSourceName：书源名称不能为空",
        )),
        Some(Value::String(_)) => {}
        Some(_) => issues.push(Issue::new(
            format!("{path}.bookSourceName"),
            "book_source_name_not_string",
            "bookSourceName 必须是字符串",
        )),
    }

    let mut src = Map::new();
    src.insert(
        "bookSourceUrl".into(),
        url.cloned().unwrap_or(Value::Null),
    );
    src.insert(
        "bookSourceName".into(),
        name.cloned().unwrap_or(Value::Null),
    );
    src.insert(
        "bookSourceType".into(),
        map.get("bookSourceType").cloned().unwrap_or(Value::Null),
    );
    src.insert("hasSearch".into(), Value::Bool(stage_present(map, SEARCH_KEYS)));
    src.insert("hasExplore".into(), Value::Bool(stage_present(map, EXPLORE_KEYS)));
    src.insert("hasToc".into(), Value::Bool(stage_present(map, TOC_KEYS)));
    src.insert(
        "hasContent".into(),
        Value::Bool(stage_present(map, CONTENT_KEYS)),
    );
    sources.push(Value::Object(src));
}

/// A stage counts as present when at least one of its fields holds a
/// non-blank rule: a non-empty string, or an object/array containing at least
/// one non-blank rule (a `{"content": ""}` shell is not a content rule).
fn stage_present(map: &Map<String, Value>, keys: &[&str]) -> bool {
    keys.iter().any(|k| map.get(*k).is_some_and(rule_has_content))
}

fn rule_has_content(v: &Value) -> bool {
    match v {
        Value::String(s) => !s.trim().is_empty(),
        Value::Object(o) => o.values().any(rule_has_content),
        Value::Array(a) => a.iter().any(rule_has_content),
        // Numbers/bools/null are not usable rules.
        _ => false,
    }
}
