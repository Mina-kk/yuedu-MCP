//! `compare_source_json` — structural equality between two documents.
//!
//! Equality is over the *whole* JSON value: unknown fields count, array order
//! counts, and `1` vs `1.0` are conservatively different (serde_json's `Value`
//! equality already distinguishes integers from floats, so no special case is
//! needed to get that behaviour — it is the default and we keep it).
//!
//! Identity is the complete `bookSourceUrl` string compared verbatim: no
//! slash folding, no `#` stripping, no default-port elision, no case folding.
//!
//! The summary names only field *names*; values never appear in it, so a
//! secret inside `header` cannot leak through the diff.

use serde_json::{Map, Value};

use crate::json::{parse_strict, Issue, Parsed};

pub fn compare(left: &str, right: &str) -> Value {
    let l = match parse_strict(left) {
        Parsed::Ok(v) => v,
        Parsed::Rejected(issues) => return error("left", &issues),
    };
    let r = match parse_strict(right) {
        Parsed::Ok(v) => v,
        Parsed::Rejected(issues) => return error("right", &issues),
    };

    let equal = l == r;
    let mut changed: Vec<String> = Vec::new();
    match (&l, &r) {
        (Value::Object(a), Value::Object(b)) => {
            // Union of keys in a stable order: left's order first, then keys
            // only present on the right.
            for (k, av) in a {
                match b.get(k) {
                    Some(bv) if bv == av => {}
                    _ => changed.push(k.clone()),
                }
            }
            for k in b.keys() {
                if !a.contains_key(k) && !changed.iter().any(|c| c == k) {
                    changed.push(k.clone());
                }
            }
        }
        _ if equal => {}
        _ => {
            // Non-object documents (arrays of sources): the top-level shape
            // itself is what changed.
            changed.push("$".to_string());
        }
    }

    let identity_changed = identity(&l) != identity(&r);

    let mut out = Map::new();
    out.insert("equal".into(), Value::Bool(equal));
    out.insert(
        "changedTopLevelFields".into(),
        Value::Array(changed.into_iter().map(Value::String).collect()),
    );
    out.insert("identityChanged".into(), Value::Bool(identity_changed));
    Value::Object(out)
}

/// Identity of a document: its `bookSourceUrl` exactly as written, or `None`
/// when absent / not a string. Arrays keep the ordered list of identities so
/// reordering or adding a source is an identity change.
fn identity(doc: &Value) -> Option<Vec<String>> {
    match doc {
        Value::Object(map) => Some(vec![map
            .get("bookSourceUrl")
            .and_then(Value::as_str)
            .map(str::to_string)
            .unwrap_or_else(|| "\u{0}<missing>".to_string())]),
        Value::Array(items) => Some(
            items
                .iter()
                .map(|i| {
                    i.as_object()
                        .and_then(|m| m.get("bookSourceUrl"))
                        .and_then(Value::as_str)
                        .map(str::to_string)
                        .unwrap_or_else(|| "\u{0}<missing>".to_string())
                })
                .collect(),
        ),
        _ => None,
    }
}

fn error(side: &str, issues: &[Issue]) -> Value {
    let first = &issues[0];
    let mut err = Map::new();
    err.insert("code".into(), Value::String(first.code.to_string()));
    err.insert("message".into(), Value::String(first.message.clone()));
    err.insert("path".into(), Value::String(first.path.clone()));
    err.insert("side".into(), Value::String(side.to_string()));
    let mut out = Map::new();
    out.insert("equal".into(), Value::Bool(false));
    out.insert("changedTopLevelFields".into(), Value::Array(Vec::new()));
    out.insert("identityChanged".into(), Value::Bool(false));
    out.insert("error".into(), Value::Object(err));
    Value::Object(out)
}
