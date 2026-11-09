//! `filter_sources` — list filtering for the source page.
//!
//! This is a *view* concern, so the contract is strict about what it may do:
//! every item survives as-is (no merging of versions sharing a URL, no
//! deduplication, no reordering), and matching is a Unicode-lowercase
//! substring test over four summary fields only — never over rule bodies.

use serde_json::{Map, Value};

use crate::json::{parse_strict, Issue, Parsed};

/// Fields searched, in the contract's order.
const SEARCH_FIELDS: &[&str] = &["bookSourceName", "name", "bookSourceUrl", "siteUrl"];

pub fn filter(items_json: &str, query: &str) -> Value {
    // Both inputs are size-checked before any parsing work.
    if let Err(issue) = crate::json::check_size(items_json) {
        return error(&issue);
    }
    if let Err(issue) = crate::json::check_size(query) {
        return error(&issue);
    }
    let doc = match parse_strict(items_json) {
        Parsed::Ok(v) => v,
        Parsed::Rejected(issues) => return error(&issues[0]),
    };
    let items = match doc {
        Value::Array(a) => a,
        _ => {
            return error(&Issue::new(
                "$",
                "top_level_not_array",
                "书源列表必须是 JSON 数组",
            ))
        }
    };

    let needle = fold(query.trim());
    let kept: Vec<Value> = if needle.is_empty() {
        items
    } else {
        items
            .into_iter()
            .filter(|item| matches(item, &needle))
            .collect()
    };

    let mut out = Map::new();
    out.insert("items".into(), Value::Array(kept.clone()));
    out.insert("total".into(), Value::from(kept.len() as u64));
    Value::Object(out)
}

fn matches(item: &Value, needle: &str) -> bool {
    let Some(map) = item.as_object() else {
        return false;
    };
    SEARCH_FIELDS.iter().any(|f| match map.get(*f) {
        Some(Value::String(s)) => fold(s).contains(needle),
        // Non-string summary fields are skipped, not fatal.
        _ => false,
    })
}

/// Unicode case folding for substring search.
///
/// `str::to_lowercase` is not enough for matching: it maps `ß` to `ß` while a
/// user typing `SS` expects a hit, and `İ` lowercases to `i` + combining dot.
/// Folding each char to its lowercase form, then re-folding the result and
/// dropping combining marks, makes both directions agree. The comparison stays
/// a plain substring test — no locale rules, no word segmentation.
fn fold(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for ch in s.chars() {
        // Full-width ASCII variants fold to their ASCII counterparts so a
        // full-width IME query matches half-width data and vice versa.
        let ch = match ch {
            '\u{ff01}'..='\u{ff5e}' => char::from_u32(ch as u32 - 0xfee0).unwrap_or(ch),
            _ => ch,
        };
        for lower in ch.to_lowercase() {
            if !is_combining_mark(lower) {
                out.push(lower);
            }
        }
    }
    // German sharp s expands to "ss" when case-folded.
    out.replace('ß', "ss")
}

fn is_combining_mark(c: char) -> bool {
    matches!(c as u32, 0x0300..=0x036f | 0x1ab0..=0x1aff | 0x20d0..=0x20ff | 0xfe20..=0xfe2f)
}

fn error(issue: &Issue) -> Value {
    let mut err = Map::new();
    err.insert("code".into(), Value::String(issue.code.to_string()));
    err.insert("message".into(), Value::String(issue.message.clone()));
    err.insert("path".into(), Value::String(issue.path.clone()));
    let mut out = Map::new();
    out.insert("error".into(), Value::Object(err));
    Value::Object(out)
}
