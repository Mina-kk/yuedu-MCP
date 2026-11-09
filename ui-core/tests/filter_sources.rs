//! Contract tests for `filter_sources`.
//!
//! Filtering is a *view* operation: every item survives, versions sharing a
//! URL are never merged, and matching is Unicode-lowercase substring over the
//! four summary fields only.

mod common;

use common::*;
use serde_json::{json, Value};
use studio_ui_core::filter_sources;

fn f(items: &str, query: &str) -> serde_json::Map<String, Value> {
    obj(&filter_sources(items, query))
}

fn names(out: &serde_json::Map<String, Value>) -> Vec<String> {
    out["items"]
        .as_array()
        .expect("items array")
        .iter()
        .map(|i| {
            i.get("bookSourceName")
                .or_else(|| i.get("name"))
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string()
        })
        .collect()
}

const ITEMS: &str = r#"[
  {"bookSourceUrl":"https://a.example/","bookSourceName":"书源 Alpha","bookSourceType":0},
  {"bookSourceUrl":"https://b.example/","bookSourceName":"Beta 中文","bookSourceType":0},
  {"bookSourceUrl":"https://c.example/","name":"gamma","siteUrl":"https://site.example/","bookSourceType":1},
  {"bookSourceUrl":"https://a.example/","bookSourceName":"书源 Alpha v2","bookSourceType":0}
]"#;

#[test]
fn empty_query_keeps_every_item() {
    let out = f(ITEMS, "");
    assert_eq!(n(&json!(out), "total"), 4);
    assert_eq!(out["items"].as_array().unwrap().len(), 4);
}

#[test]
fn whitespace_only_query_keeps_every_item() {
    let out = f(ITEMS, "   ");
    assert_eq!(n(&json!(out), "total"), 4);
}

#[test]
fn ascii_match_is_case_insensitive() {
    let out = f(ITEMS, "ALPHA");
    assert_eq!(n(&json!(out), "total"), 2);
    assert_eq!(names(&out), vec!["书源 Alpha", "书源 Alpha v2"]);
}

#[test]
fn unicode_case_folding_matches() {
    // ß upper-cases to SS; İ/ı and full-width forms must not crash either.
    let items = r#"[{"bookSourceUrl":"https://x/","bookSourceName":"Straße Buch"},
                   {"bookSourceUrl":"https://y/","bookSourceName":"İstanbul"},
                   {"bookSourceUrl":"https://z/","bookSourceName":"ＡＢＣ全角"}]"#;
    assert_eq!(n(&json!(f(items, "STRASSE")), "total"), 1);
    assert_eq!(n(&json!(f(items, "straße")), "total"), 1);
    assert_eq!(n(&json!(f(items, "istanbul")), "total"), 1);
    assert_eq!(n(&json!(f(items, "abc")), "total"), 1);
}

#[test]
fn chinese_substring_matches() {
    let out = f(ITEMS, "中文");
    assert_eq!(n(&json!(out), "total"), 1);
    assert_eq!(names(&out), vec!["Beta 中文"]);
}

#[test]
fn matches_url_and_site_url() {
    assert_eq!(n(&json!(f(ITEMS, "b.example")), "total"), 1);
    let out = f(ITEMS, "site.example");
    assert_eq!(n(&json!(out), "total"), 1);
    assert_eq!(names(&out), vec!["gamma"]);
}

#[test]
fn same_url_versions_are_not_merged() {
    let out = f(ITEMS, "alpha");
    let items = out["items"].as_array().unwrap();
    assert_eq!(items.len(), 2);
    assert_eq!(s(&items[0], "bookSourceUrl"), "https://a.example/");
    assert_eq!(s(&items[1], "bookSourceUrl"), "https://a.example/");
    assert_ne!(s(&items[0], "bookSourceName"), s(&items[1], "bookSourceName"));
}

#[test]
fn item_order_is_preserved() {
    let out = f(ITEMS, "e");
    let urls: Vec<&str> = out["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|i| s(i, "bookSourceUrl"))
        .collect();
    assert_eq!(urls, vec!["https://a.example/", "https://b.example/", "https://c.example/", "https://a.example/"]);
}

#[test]
fn no_match_returns_empty_items_with_zero_total() {
    let out = f(ITEMS, "zzzz-not-there");
    assert_eq!(n(&json!(out), "total"), 0);
    assert!(out["items"].as_array().unwrap().is_empty());
}

#[test]
fn items_are_returned_verbatim_with_unknown_fields() {
    let items = r#"[{"bookSourceUrl":"https://a/","bookSourceName":"A","custom":{"deep":[1,2]}}]"#;
    let out = f(items, "a");
    let got = &out["items"].as_array().unwrap()[0];
    assert_eq!(got["custom"]["deep"], json!([1, 2]));
}

#[test]
fn only_summary_fields_are_searched() {
    // A rule body containing the query must not match.
    let items = r#"[{"bookSourceUrl":"https://a/","bookSourceName":"A","ruleSearch":{"bookList":"needle"}}]"#;
    assert_eq!(n(&json!(f(items, "needle")), "total"), 0);
}

#[test]
fn non_string_summary_fields_are_skipped_not_fatal() {
    let items = r#"[{"bookSourceUrl":5,"bookSourceName":null,"name":["x"]},
                   {"bookSourceUrl":"https://ok/","bookSourceName":"ok"}]"#;
    let out = f(items, "ok");
    assert_eq!(n(&json!(out), "total"), 1);
}

#[test]
fn invalid_items_json_is_a_structured_error() {
    let out = f("{not an array", "x");
    let err = out.get("error").expect("error object");
    assert_eq!(s(err, "code"), "json_syntax_error");
    assert_eq!(out.get("items").and_then(|i| i.as_array()).map(|a| a.len()), None);
}

#[test]
fn oversized_items_is_a_structured_error() {
    let pad = "a".repeat(2 * 1024 * 1024);
    let items = format!(r#"[{{"bookSourceUrl":"https://a/","bookSourceName":"{pad}"}}]"#);
    let out = f(&items, "a");
    let err = out.get("error").expect("error object");
    assert_eq!(s(err, "code"), "input_too_large");
}

#[test]
fn oversized_query_is_a_structured_error() {
    // One byte past the ceiling: 2 MiB exactly is still allowed.
    let query = "q".repeat(2 * 1024 * 1024 + 1);
    let out = f(ITEMS, &query);
    let err = out.get("error").expect("error object");
    assert_eq!(s(err, "code"), "input_too_large");
}

#[test]
fn empty_array_is_fine() {
    let out = f("[]", "x");
    assert_eq!(n(&json!(out), "total"), 0);
}

#[test]
fn query_is_trimmed() {
    let out = f(ITEMS, "  beta  ");
    assert_eq!(n(&json!(out), "total"), 1);
}

#[test]
fn duplicate_keys_in_items_are_an_error() {
    let items = r#"[{"bookSourceUrl":"https://a/","bookSourceName":"A","bookSourceName":"B"}]"#;
    let out = f(items, "a");
    let err = out.get("error").expect("error object");
    assert_eq!(s(err, "code"), "duplicate_key");
}
