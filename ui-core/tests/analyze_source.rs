//! Contract tests for `analyze_source_json`.
//!
//! Every boundary named in `docs/UI_CORE_CONTRACT.md` §回归 has a test here:
//! JSON syntax errors, empty array, unknown-field preservation, same-URL
//! multiple versions, trailing slash / `#` tag / default port identity,
//! oversized input and duplicate keys.

mod common;

use common::*;
use serde_json::json;
use studio_ui_core::analyze_source_json;

fn analyze(text: &str) -> serde_json::Map<String, serde_json::Value> {
    obj(&analyze_source_json(text))
}

const FULL: &str = r#"{
  "bookSourceUrl": "https://example.com/source/1",
  "bookSourceName": "示例书源",
  "bookSourceType": 0,
  "searchUrl": "https://example.com/search?q={{key}}",
  "ruleSearch": {"bookList": "class.list@tag.li", "name": "tag.a@text"},
  "ruleBookInfo": {"name": "id.info@tag.h1@text"},
  "ruleToc": {"chapterList": "id.list@tag.a"},
  "ruleContent": {"content": "id.content@html"},
  "customUnknownField": {"nested": [1, 2, {"deep": true}]},
  "lastUpdateTime": 1700000000000
}"#;

#[test]
fn version_is_contract_version() {
    assert_eq!(studio_ui_core::core_version(), "studio-ui-core/2.0.0");
}

#[test]
fn accepts_single_object_and_reports_stage_flags() {
    let out = analyze(FULL);
    assert_eq!(b(&json!(out), "valid"), true);
    assert_eq!(n(&json!(out), "sourceCount"), 1);
    let sources = out["sources"].as_array().expect("sources array");
    assert_eq!(sources.len(), 1);
    let src = &sources[0];
    assert_eq!(s(src, "bookSourceUrl"), "https://example.com/source/1");
    assert_eq!(s(src, "bookSourceName"), "示例书源");
    assert_eq!(n(src, "bookSourceType"), 0);
    assert_eq!(b(src, "hasSearch"), true);
    assert_eq!(b(src, "hasExplore"), false);
    assert_eq!(b(src, "hasToc"), true);
    assert_eq!(b(src, "hasContent"), true);
    assert!(issue_codes(&json!(out)).is_empty(), "{out:?}");
}

#[test]
fn accepts_top_level_array() {
    let text = format!("[{FULL}, {{\"bookSourceUrl\":\"https://b.example/\",\"bookSourceName\":\"B\"}}]");
    let out = analyze(&text);
    assert_eq!(b(&json!(out), "valid"), true);
    assert_eq!(n(&json!(out), "sourceCount"), 2);
    let sources = out["sources"].as_array().unwrap();
    assert_eq!(s(&sources[1], "bookSourceUrl"), "https://b.example/");
    assert_eq!(b(&sources[1], "hasSearch"), false);
    // A source with no rules at all is still a *saveable draft*: no issue.
    assert!(issue_codes(&json!(out)).is_empty(), "{out:?}");
}

#[test]
fn empty_array_is_valid_with_zero_sources() {
    let out = analyze("[]");
    assert_eq!(b(&json!(out), "valid"), true);
    assert_eq!(n(&json!(out), "sourceCount"), 0);
    assert_eq!(out["sources"].as_array().unwrap().len(), 0);
    assert!(issue_codes(&json!(out)).is_empty());
}

#[test]
fn empty_object_is_invalid_missing_url_and_name() {
    let out = analyze("{}");
    assert_eq!(b(&json!(out), "valid"), false);
    let codes = issue_codes(&json!(out));
    assert!(codes.contains(&"missing_book_source_url".to_string()), "{codes:?}");
    assert!(codes.contains(&"missing_book_source_name".to_string()), "{codes:?}");
}

#[test]
fn syntax_error_is_structured_not_panic() {
    let out = analyze("{\"bookSourceUrl\": \"https://x/\", ");
    assert_eq!(b(&json!(out), "valid"), false);
    let codes = issue_codes(&json!(out));
    assert!(codes.contains(&"json_syntax_error".to_string()), "{codes:?}");
    // Issue carries a locatable path and a message; no panic, no partial sources.
    let issues = out["issues"].as_array().unwrap();
    assert!(issues[0].get("message").and_then(|m| m.as_str()).is_some());
    assert!(issues[0].get("path").and_then(|m| m.as_str()).is_some());
    assert_eq!(n(&json!(out), "sourceCount"), 0);
}

#[test]
fn top_level_scalar_is_rejected() {
    for text in ["\"just a string\"", "42", "null", "true"] {
        let out = analyze(text);
        assert_eq!(b(&json!(out), "valid"), false, "{text}");
        let codes = issue_codes(&json!(out));
        assert!(codes.contains(&"top_level_not_object_or_array".to_string()), "{text} {codes:?}");
    }
}

#[test]
fn array_with_non_object_entry_is_invalid_but_keeps_index() {
    let out = analyze(r#"[{"bookSourceUrl":"https://a/","bookSourceName":"A"}, 7]"#);
    assert_eq!(b(&json!(out), "valid"), false);
    let issues = out["issues"].as_array().unwrap();
    let hit = issues
        .iter()
        .find(|i| i["code"] == json!("source_not_object"))
        .expect("source_not_object issue");
    assert_eq!(hit["path"], json!("$[1]"));
    // The valid entry is still described.
    assert_eq!(n(&json!(out), "sourceCount"), 1);
}

#[test]
fn wrong_types_for_url_and_name_are_issues() {
    let out = analyze(r#"{"bookSourceUrl": 5, "bookSourceName": ["x"]}"#);
    assert_eq!(b(&json!(out), "valid"), false);
    let codes = issue_codes(&json!(out));
    assert!(codes.contains(&"book_source_url_not_string".to_string()), "{codes:?}");
    assert!(codes.contains(&"book_source_name_not_string".to_string()), "{codes:?}");
}

#[test]
fn whitespace_only_url_is_an_issue_but_blank_name_is_not() {
    let out = analyze(r#"{"bookSourceUrl":"   ","bookSourceName":""}"#);
    assert_eq!(b(&json!(out), "valid"), false);
    let codes = issue_codes(&json!(out));
    assert!(codes.contains(&"book_source_url_blank".to_string()), "{codes:?}");
    assert!(!codes.contains(&"missing_book_source_name".to_string()), "{codes:?}");
}

#[test]
fn missing_stages_do_not_block_draft_save() {
    // No search/explore/toc/content rules at all: still a valid draft.
    let out = analyze(r#"{"bookSourceUrl":"https://draft.example/","bookSourceName":"草稿"}"#);
    assert_eq!(b(&json!(out), "valid"), true);
    assert!(issue_codes(&json!(out)).is_empty());
    let src = &out["sources"].as_array().unwrap()[0];
    assert_eq!(b(src, "hasSearch"), false);
    assert_eq!(b(src, "hasToc"), false);
}

#[test]
fn formatted_preserves_unknown_fields_and_all_data() {
    let out = analyze(FULL);
    let formatted = out.get("formatted").expect("formatted present");
    // formatted is the JSON document (string or value) — compare semantically.
    let doc: serde_json::Value = match formatted {
        serde_json::Value::String(text) => serde_json::from_str(text).expect("formatted is JSON"),
        other => other.clone(),
    };
    let original: serde_json::Value = serde_json::from_str(FULL).unwrap();
    assert_eq!(doc, original, "formatted must preserve every field and value");
    assert_eq!(doc["customUnknownField"]["nested"][2]["deep"], json!(true));
}

#[test]
fn formatted_keeps_array_shape() {
    let out = analyze(r#"[{"bookSourceUrl":"https://a/","bookSourceName":"A","x":1}]"#);
    let formatted = out.get("formatted").expect("formatted");
    let doc: serde_json::Value = match formatted {
        serde_json::Value::String(text) => serde_json::from_str(text).unwrap(),
        other => other.clone(),
    };
    assert!(doc.is_array(), "{doc}");
    assert_eq!(doc[0]["x"], json!(1));
}

#[test]
fn identity_is_verbatim_not_normalized() {
    // Trailing slash, `#` tag and explicit default port are distinct identities.
    let variants = [
        "https://example.com/source",
        "https://example.com/source/",
        "https://example.com/source#tag",
        "https://example.com:443/source",
        "https://EXAMPLE.com/source",
    ];
    let text: String = format!(
        "[{}]",
        variants
            .iter()
            .map(|u| format!(r#"{{"bookSourceUrl":"{u}","bookSourceName":"n"}}"#))
            .collect::<Vec<_>>()
            .join(",")
    );
    let out = analyze(&text);
    assert_eq!(b(&json!(out), "valid"), true);
    let sources = out["sources"].as_array().unwrap();
    let got: Vec<&str> = sources
        .iter()
        .map(|src| s(src, "bookSourceUrl"))
        .collect();
    assert_eq!(got, variants);
}

#[test]
fn duplicate_source_in_document_is_kept_as_two_sources() {
    // Same URL twice in one document: never merged, never silently collapsed.
    let out = analyze(
        r#"[{"bookSourceUrl":"https://a/","bookSourceName":"A v1","bookSourceType":0},
            {"bookSourceUrl":"https://a/","bookSourceName":"A v2","bookSourceType":0}]"#,
    );
    assert_eq!(n(&json!(out), "sourceCount"), 2);
    let sources = out["sources"].as_array().unwrap();
    assert_eq!(s(&sources[0], "bookSourceName"), "A v1");
    assert_eq!(s(&sources[1], "bookSourceName"), "A v2");
}

#[test]
fn duplicate_keys_are_rejected_not_last_wins() {
    let out = analyze(r#"{"bookSourceUrl":"https://a/","bookSourceUrl":"https://b/","bookSourceName":"A"}"#);
    assert_eq!(b(&json!(out), "valid"), false);
    let codes = issue_codes(&json!(out));
    assert!(codes.contains(&"duplicate_key".to_string()), "{codes:?}");
    // Neither value is silently promoted: no sources are described from a
    // document whose identity is ambiguous.
    assert_eq!(n(&json!(out), "sourceCount"), 0);
}

#[test]
fn duplicate_nested_rule_key_is_rejected() {
    let text = r#"{"bookSourceUrl":"https://a/","bookSourceName":"A",
        "ruleSearch":{"bookList":"class.a","bookList":"class.b"}}"#;
    let out = analyze(text);
    assert_eq!(b(&json!(out), "valid"), false);
    let issues = out["issues"].as_array().unwrap();
    let hit = issues.iter().find(|i| i["code"] == json!("duplicate_key")).expect("duplicate_key");
    assert_eq!(hit["path"], json!("$.ruleSearch.bookList"));
}

#[test]
fn duplicate_key_in_arbitrary_nested_object_is_rejected() {
    let text = r#"{"bookSourceUrl":"https://a/","bookSourceName":"A","x":{"y":[{"z":1,"z":2}]}}"#;
    let out = analyze(text);
    assert_eq!(b(&json!(out), "valid"), false);
    let issues = out["issues"].as_array().unwrap();
    let hit = issues.iter().find(|i| i["code"] == json!("duplicate_key")).expect("duplicate_key");
    assert_eq!(hit["path"], json!("$.x.y[0].z"));
}

#[test]
fn oversized_input_returns_issue_without_panic() {
    // 2 MiB + 1 byte of UTF-8 payload.
    let pad = "a".repeat(2 * 1024 * 1024);
    let text = format!(r#"{{"bookSourceUrl":"https://a/","bookSourceName":"{pad}"}}"#);
    assert!(text.len() > 2 * 1024 * 1024);
    let out = analyze(&text);
    assert_eq!(b(&json!(out), "valid"), false);
    let codes = issue_codes(&json!(out));
    assert!(codes.contains(&"input_too_large".to_string()), "{codes:?}");
}

#[test]
fn multibyte_input_is_measured_in_utf8_bytes() {
    // 700k CJK chars = 2.1 MB UTF-8: over the byte limit even though the
    // character count is under 2M.
    let pad = "书".repeat(700_000);
    let text = format!(r#"{{"bookSourceUrl":"https://a/","bookSourceName":"{pad}"}}"#);
    assert!(text.chars().count() < 2 * 1024 * 1024);
    let out = analyze(&text);
    let codes = issue_codes(&json!(out));
    assert!(codes.contains(&"input_too_large".to_string()), "{codes:?}");
}

#[test]
fn just_under_limit_is_accepted() {
    let pad = "a".repeat(2 * 1024 * 1024 - 64);
    let text = format!(r#"{{"bookSourceUrl":"https://a/","bookSourceName":"{pad}"}}"#);
    assert!(text.len() <= 2 * 1024 * 1024);
    let out = analyze(&text);
    assert_eq!(b(&json!(out), "valid"), true);
}

#[test]
fn boms_and_leading_whitespace_are_tolerated() {
    let out = analyze("\u{feff}\n\t {\"bookSourceUrl\":\"https://a/\",\"bookSourceName\":\"A\"}");
    assert_eq!(b(&json!(out), "valid"), true);
}

#[test]
fn does_not_claim_network_or_reader_results() {
    let out = analyze(FULL);
    let text = serde_json::to_string(&out).unwrap();
    for forbidden in ["readerSidePassed\":true", "\"networkVerified\":true", "\"passed\":true"] {
        assert!(!text.contains(forbidden), "analyze must not claim {forbidden}: {text}");
    }
}

#[test]
fn stage_flags_accept_rule_objects_and_ignore_unknown_types() {
    let out = analyze(
        r#"{"bookSourceUrl":"https://a/","bookSourceName":"A",
            "searchUrl":"https://a/s?q={{key}}",
            "exploreUrl":"分类::https://a/c?page={{page}}",
            "ruleToc":"class.list@tag.a",
            "ruleContent":{"content":5}}"#,
    );
    let src = &out["sources"].as_array().unwrap()[0];
    assert_eq!(b(src, "hasSearch"), true);
    assert_eq!(b(src, "hasExplore"), true);
    assert_eq!(b(src, "hasToc"), true);
    // ruleContent.content is a number: not a usable content rule.
    assert_eq!(b(src, "hasContent"), false);
}

#[test]
fn empty_string_rules_do_not_count_as_stages() {
    let out = analyze(
        r#"{"bookSourceUrl":"https://a/","bookSourceName":"A","searchUrl":"  ",
            "ruleToc":{"chapterList":""},"ruleContent":{"content":""}}"#,
    );
    let src = &out["sources"].as_array().unwrap()[0];
    assert_eq!(b(src, "hasSearch"), false);
    assert_eq!(b(src, "hasToc"), false);
    assert_eq!(b(src, "hasContent"), false);
}
