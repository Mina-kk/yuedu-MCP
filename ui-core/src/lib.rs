//! `studio-ui-core` — frontend domain core for the Rust+Vue web UI.
//!
//! The public boundary is JSON strings only (see `docs/UI_CORE_CONTRACT.md`):
//! every exported function takes `&str` and returns `String`. No JS objects,
//! no `serde_wasm_bindgen` value conversion, so the same code is testable
//! natively with `cargo test` and callable from Vue after `wasm-bindgen`.
//!
//! This crate is *not* a rule sandbox. It never executes book-source rules and
//! never turns a static/sandbox observation into a reader-side pass.

#![forbid(unsafe_code)]

pub mod analyze;
pub mod compare;
pub mod evidence;
pub mod filter;
pub mod json;
pub mod version;

use wasm_bindgen::prelude::*;

/// Contract version of this crate, e.g. `studio-ui-core/2.0.0`.
#[wasm_bindgen]
pub fn core_version() -> String {
    version::CORE_VERSION.to_string()
}

/// Statically analyze a book-source document (object or array).
///
/// Returns `{valid, sourceCount, sources[], issues[], formatted}` as a JSON
/// string. Never panics on caller input and never claims a network or
/// reader-side result.
#[wasm_bindgen]
pub fn analyze_source_json(text: &str) -> String {
    render(analyze::analyze(text))
}

/// Structural comparison of two documents.
///
/// Returns `{equal, changedTopLevelFields, identityChanged}` or, for invalid
/// input, the same shape plus a structured `error` object.
#[wasm_bindgen]
pub fn compare_source_json(left: &str, right: &str) -> String {
    render(compare::compare(left, right))
}

/// Summarize runtime evidence JSON into `{scope,status,label,tone,passed,notes}`.
#[wasm_bindgen]
pub fn summarize_evidence(text: &str) -> String {
    render(evidence::summarize(text))
}

/// Filter a source list by a Unicode-lowercase substring over summary fields.
///
/// Returns `{items, total}`; every non-matching item is dropped but matching
/// items are returned verbatim and in order.
#[wasm_bindgen]
pub fn filter_sources(items_json: &str, query: &str) -> String {
    render(filter::filter(items_json, query))
}

/// Serialize a result. `Value` serialization cannot fail for the plain
/// data structures this crate builds, but the fallback keeps the boundary
/// panic-free even if that ever changes.
fn render(value: serde_json::Value) -> String {
    serde_json::to_string(&value).unwrap_or_else(|_| {
        r#"{"error":{"code":"serialize_failed","message":"结果序列化失败"}}"#.to_string()
    })
}
