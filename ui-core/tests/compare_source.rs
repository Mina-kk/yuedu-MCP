//! Contract tests for `compare_source_json`.
//!
//! Structural equality over the *whole* document (unknown fields included),
//! full-`bookSourceUrl` identity, and a summary that never leaks values.

mod common;

use common::*;
use serde_json::json;
use studio_ui_core::compare_source_json;

fn cmp(left: &str, right: &str) -> serde_json::Map<String, serde_json::Value> {
    obj(&compare_source_json(left, right))
}

const A: &str = r#"{"bookSourceUrl":"https://a.example/s","bookSourceName":"A","ruleSearch":{"bookList":"class.x"},"extra":1}"#;

#[test]
fn identical_documents_are_equal() {
    let out = cmp(A, A);
    assert_eq!(b(&json!(out), "equal"), true);
    assert_eq!(b(&json!(out), "identityChanged"), false);
    assert_eq!(out["changedTopLevelFields"].as_array().unwrap().len(), 0);
}

#[test]
fn key_order_does_not_matter() {
    let left = r#"{"bookSourceUrl":"https://a/","bookSourceName":"A","x":1}"#;
    let right = r#"{"x":1,"bookSourceName":"A","bookSourceUrl":"https://a/"}"#;
    assert_eq!(b(&json!(cmp(left, right)), "equal"), true);
}

#[test]
fn unknown_field_difference_is_detected() {
    let right = r#"{"bookSourceUrl":"https://a.example/s","bookSourceName":"A","ruleSearch":{"bookList":"class.x"},"extra":2}"#;
    let out = cmp(A, right);
    assert_eq!(b(&json!(out), "equal"), false);
    let changed: Vec<&str> = out["changedTopLevelFields"]
        .as_array()
        .unwrap()
        .iter()
        .map(|v| v.as_str().unwrap())
        .collect();
    assert_eq!(changed, vec!["extra"]);
}

#[test]
fn deep_difference_reports_top_level_container() {
    let right = r#"{"bookSourceUrl":"https://a.example/s","bookSourceName":"A","ruleSearch":{"bookList":"class.y"},"extra":1}"#;
    let out = cmp(A, right);
    assert_eq!(b(&json!(out), "equal"), false);
    let changed: Vec<&str> = out["changedTopLevelFields"]
        .as_array()
        .unwrap()
        .iter()
        .map(|v| v.as_str().unwrap())
        .collect();
    assert_eq!(changed, vec!["ruleSearch"]);
}

#[test]
fn added_and_removed_fields_are_both_reported() {
    let left = r#"{"bookSourceUrl":"https://a/","bookSourceName":"A","gone":1}"#;
    let right = r#"{"bookSourceUrl":"https://a/","bookSourceName":"A","added":1}"#;
    let out = cmp(left, right);
    let mut changed: Vec<&str> = out["changedTopLevelFields"]
        .as_array()
        .unwrap()
        .iter()
        .map(|v| v.as_str().unwrap())
        .collect();
    changed.sort_unstable();
    assert_eq!(changed, vec!["added", "gone"]);
}

#[test]
fn integer_and_float_are_conservatively_different() {
    let left = r#"{"bookSourceUrl":"https://a/","bookSourceName":"A","weight":1}"#;
    let right = r#"{"bookSourceUrl":"https://a/","bookSourceName":"A","weight":1.0}"#;
    let out = cmp(left, right);
    assert_eq!(b(&json!(out), "equal"), false);
    assert_eq!(b(&json!(out), "identityChanged"), false);
}

#[test]
fn array_order_and_length_matter() {
    let left = r#"{"bookSourceUrl":"https://a/","bookSourceName":"A","l":[1,2]}"#;
    assert_eq!(
        b(&json!(cmp(left, r#"{"bookSourceUrl":"https://a/","bookSourceName":"A","l":[2,1]}"#)), "equal"),
        false
    );
    assert_eq!(
        b(&json!(cmp(left, r#"{"bookSourceUrl":"https://a/","bookSourceName":"A","l":[1,2,3]}"#)), "equal"),
        false
    );
}

#[test]
fn identity_uses_full_url_verbatim() {
    let pairs = [
        ("https://a.example/s", "https://a.example/s/"),
        ("https://a.example/s", "https://a.example/s#tag"),
        ("https://a.example/s", "https://a.example:443/s"),
        ("https://a.example/s", "https://a.example/s?x=1"),
        ("https://a.example/s", "https://A.example/s"),
        ("https://a.example/s", "http://a.example/s"),
    ];
    for (l, r) in pairs {
        let left = format!(r#"{{"bookSourceUrl":"{l}","bookSourceName":"A"}}"#);
        let right = format!(r#"{{"bookSourceUrl":"{r}","bookSourceName":"A"}}"#);
        let out = cmp(&left, &right);
        assert_eq!(b(&json!(out), "equal"), false, "{l} vs {r}");
        assert_eq!(b(&json!(out), "identityChanged"), true, "{l} vs {r}");
        let changed: Vec<&str> = out["changedTopLevelFields"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| v.as_str().unwrap())
            .collect();
        assert_eq!(changed, vec!["bookSourceUrl"], "{l} vs {r}");
    }
}

#[test]
fn identity_added_or_removed_counts_as_changed() {
    let with = r#"{"bookSourceUrl":"https://a/","bookSourceName":"A"}"#;
    let without = r#"{"bookSourceName":"A"}"#;
    assert_eq!(b(&json!(cmp(with, without)), "identityChanged"), true);
    assert_eq!(b(&json!(cmp(without, with)), "identityChanged"), true);
}

#[test]
fn identity_compared_as_exact_string_not_trimmed() {
    let left = r#"{"bookSourceUrl":"https://a/","bookSourceName":"A"}"#;
    let right = r#"{"bookSourceUrl":"https://a/ ","bookSourceName":"A"}"#;
    assert_eq!(b(&json!(cmp(left, right)), "identityChanged"), true);
}

#[test]
fn summary_never_leaks_values() {
    let left = r#"{"bookSourceUrl":"https://a/","bookSourceName":"A","header":"{\"Authorization\":\"Bearer SECRET-TOKEN-1\"}","ruleSearch":{"bookList":"class.secret"}}"#;
    let right = r#"{"bookSourceUrl":"https://a/","bookSourceName":"A","header":"{\"Authorization\":\"Bearer SECRET-TOKEN-2\"}","ruleSearch":{"bookList":"class.other"}}"#;
    let raw = compare_source_json(left, right);
    assert!(!raw.contains("SECRET"), "summary leaked a secret: {raw}");
    assert!(!raw.contains("class.secret"), "summary leaked a value: {raw}");
    assert!(!raw.contains("class.other"), "summary leaked a value: {raw}");
}

#[test]
fn invalid_left_returns_structured_error() {
    let out = cmp("{not json", A);
    assert_eq!(b(&json!(out), "equal"), false);
    let err = out.get("error").expect("error object");
    assert_eq!(s(err, "code"), "json_syntax_error");
    assert!(err.get("message").and_then(|m| m.as_str()).is_some());
    // The contract shape is always present; on error it must claim no diff.
    assert_eq!(
        out["changedTopLevelFields"].as_array().map(|a| a.len()),
        Some(0),
        "no diff claimed on error"
    );
    assert_eq!(out["identityChanged"], json!(false));
}

#[test]
fn invalid_right_returns_structured_error() {
    let out = cmp(A, "");
    let err = out.get("error").expect("error object");
    assert_eq!(s(err, "code"), "json_syntax_error");
    assert_eq!(s(err, "side"), "right");
}

#[test]
fn duplicate_keys_on_either_side_is_an_error() {
    let dup = r#"{"bookSourceUrl":"https://a/","bookSourceUrl":"https://b/","bookSourceName":"A"}"#;
    let out = cmp(dup, A);
    let err = out.get("error").expect("error object");
    assert_eq!(s(err, "code"), "duplicate_key");
    assert_eq!(s(err, "side"), "left");

    let out = cmp(A, dup);
    let err = out.get("error").expect("error object");
    assert_eq!(s(err, "code"), "duplicate_key");
    assert_eq!(s(err, "side"), "right");
}

#[test]
fn oversized_side_is_an_error_not_a_panic() {
    let pad = "a".repeat(2 * 1024 * 1024);
    let big = format!(r#"{{"bookSourceUrl":"https://a/","bookSourceName":"{pad}"}}"#);
    let out = cmp(&big, A);
    let err = out.get("error").expect("error object");
    assert_eq!(s(err, "code"), "input_too_large");
    assert_eq!(s(err, "side"), "left");
}

#[test]
fn non_object_documents_are_compared_structurally() {
    // Arrays are legal book-source documents for the analyzer; compare them too.
    let out = cmp(r#"[{"bookSourceUrl":"https://a/","bookSourceName":"A"}]"#, r#"[{"bookSourceUrl":"https://a/","bookSourceName":"A"}]"#);
    assert_eq!(b(&json!(out), "equal"), true);
    assert_eq!(b(&json!(out), "identityChanged"), false);

    let out = cmp(r#"[{"bookSourceUrl":"https://a/"},{"bookSourceUrl":"https://b/"}]"#, r#"[{"bookSourceUrl":"https://a/"},{"bookSourceUrl":"https://c/"}]"#);
    assert_eq!(b(&json!(out), "equal"), false);
    assert_eq!(b(&json!(out), "identityChanged"), true);
}
