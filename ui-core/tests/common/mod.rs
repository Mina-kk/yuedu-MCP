//! Shared helpers for the contract tests. Tests are written against the
//! public JSON-string boundary only, exactly like the Vue caller.
//!
//! Each integration test binary includes this module and uses a subset of the
//! helpers, so unused ones are expected.
#![allow(dead_code)]

use serde_json::Value;

pub fn parse(s: &str) -> Value {
    serde_json::from_str(s).unwrap_or_else(|e| panic!("output is not JSON: {e}\n{s}"))
}

pub fn obj(s: &str) -> serde_json::Map<String, Value> {
    match parse(s) {
        Value::Object(m) => m,
        other => panic!("expected JSON object output, got {other}"),
    }
}

pub fn arr(s: &str) -> Vec<Value> {
    match parse(s) {
        Value::Array(a) => a,
        other => panic!("expected JSON array output, got {other}"),
    }
}

pub fn b(v: &Value, key: &str) -> bool {
    v.get(key)
        .unwrap_or_else(|| panic!("missing key {key} in {v}"))
        .as_bool()
        .unwrap_or_else(|| panic!("key {key} is not bool in {v}"))
}

pub fn s<'a>(v: &'a Value, key: &str) -> &'a str {
    v.get(key)
        .unwrap_or_else(|| panic!("missing key {key} in {v}"))
        .as_str()
        .unwrap_or_else(|| panic!("key {key} is not string in {v}"))
}

pub fn n(v: &Value, key: &str) -> i64 {
    v.get(key)
        .unwrap_or_else(|| panic!("missing key {key} in {v}"))
        .as_i64()
        .unwrap_or_else(|| panic!("key {key} is not integer in {v}"))
}

/// Issue codes present in an `analyze_source_json` result.
pub fn issue_codes(v: &Value) -> Vec<String> {
    v.get("issues")
        .and_then(|i| i.as_array())
        .map(|a| {
            a.iter()
                .filter_map(|i| i.get("code").and_then(|c| c.as_str()).map(str::to_string))
                .collect()
        })
        .unwrap_or_default()
}
