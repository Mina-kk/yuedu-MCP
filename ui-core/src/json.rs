//! Strict JSON layer shared by every exported function.
//!
//! `serde_json::Value` silently keeps the last of two duplicate keys, which the
//! contract forbids ("不悄悄 last-wins"). Documents therefore go through a
//! purpose-built scanner first:
//!
//! 1. [`check_duplicates`] walks the raw text (a small recursive-descent
//!    scanner that understands just enough JSON to track object keys and their
//!    paths) and records every repeated key.
//! 2. Only when that pass is clean does `serde_json::from_str::<Value>` build
//!    the document, so it cannot lose data.
//!
//! Recursion is bounded by [`MAX_DEPTH`]: serde_json's own parser rejects
//! nesting deeper than 128, so a hostile 2 MB document cannot overflow the
//! stack here either — the scanner refuses at the same depth.

use serde_json::Value;

use crate::version::MAX_INPUT_BYTES;

/// Maximum nesting depth accepted. Matches serde_json's built-in limit so the
/// scanner and the real parser agree on what is acceptable.
pub const MAX_DEPTH: usize = 128;

/// One structural problem found in an input document.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Issue {
    /// JSON path of the problem, e.g. `$.ruleSearch.bookList`, `[2]`, `$`.
    pub path: String,
    /// Stable machine code, e.g. `json_syntax_error`, `duplicate_key`.
    pub code: &'static str,
    /// Human readable, locatable message. Never echoes input values.
    pub message: String,
}

impl Issue {
    pub fn new(path: impl Into<String>, code: &'static str, message: impl Into<String>) -> Self {
        Issue { path: path.into(), code, message: message.into() }
    }

    pub fn to_json(&self) -> Value {
        let mut m = serde_json::Map::new();
        m.insert("path".into(), Value::String(self.path.clone()));
        m.insert("code".into(), Value::String(self.code.to_string()));
        m.insert("message".into(), Value::String(self.message.clone()));
        Value::Object(m)
    }
}

/// A parsed-and-validated input document.
pub enum Parsed {
    /// Well-formed, duplicate-free JSON.
    Ok(Value),
    /// Rejected; `issues` is never empty.
    Rejected(Vec<Issue>),
}

/// Enforce the UTF-8 byte ceiling before any parsing work.
pub fn check_size(text: &str) -> Result<(), Issue> {
    if text.len() > MAX_INPUT_BYTES {
        return Err(Issue::new(
            "$",
            "input_too_large",
            format!(
                "输入为 {} 字节，超过上限 {} 字节（2 MB，按 UTF-8 字节计）",
                text.len(),
                MAX_INPUT_BYTES
            ),
        ));
    }
    Ok(())
}

/// Parse `text` strictly: size limit, JSON syntax, duplicate keys.
pub fn parse_strict(text: &str) -> Parsed {
    if let Err(issue) = check_size(text) {
        return Parsed::Rejected(vec![issue]);
    }
    let body = strip_bom(text);
    let mut dups = Vec::new();
    match check_duplicates(body, &mut dups) {
        Ok(()) if !dups.is_empty() => return Parsed::Rejected(dups),
        Ok(()) => {}
        // A syntax error is reported instead of duplicate noise from a
        // half-parsed document.
        Err(issue) => return Parsed::Rejected(vec![issue]),
    }
    match serde_json::from_str::<Value>(body) {
        Ok(v) => Parsed::Ok(v),
        // Unreachable in practice (pass 1 parsed the same text), but never
        // panic on caller input.
        Err(e) => Parsed::Rejected(vec![syntax_issue(&e)]),
    }
}

/// Remove a leading UTF-8 BOM, which some editors prepend to saved files.
pub fn strip_bom(text: &str) -> &str {
    text.strip_prefix('\u{feff}').unwrap_or(text)
}

fn syntax_issue(e: &serde_json::Error) -> Issue {
    Issue::new(
        format!("$:{}:{}", e.line(), e.column()),
        "json_syntax_error",
        format!("JSON 语法错误：第 {} 行第 {} 列，{}", e.line(), e.column(), e),
    )
}

/// Walk the raw document and collect duplicate object keys with JSON paths.
///
/// Returns `Err` on the first syntax error so callers can report a locatable
/// position instead of a pile of duplicate paths.
pub fn check_duplicates(body: &str, out: &mut Vec<Issue>) -> Result<(), Issue> {
    let mut sc = Scanner { b: body.as_bytes(), i: 0 };
    sc.ws();
    sc.value("$", 0, out)?;
    sc.ws();
    if sc.i != sc.b.len() {
        return Err(sc.err("JSON 语法错误：根值之后存在多余内容"));
    }
    Ok(())
}

struct Scanner<'a> {
    b: &'a [u8],
    i: usize,
}

impl<'a> Scanner<'a> {
    fn ws(&mut self) {
        while self.i < self.b.len() && matches!(self.b[self.i], b' ' | b'\t' | b'\n' | b'\r') {
            self.i += 1;
        }
    }

    fn err(&self, msg: &str) -> Issue {
        let line = 1 + self.b[..self.i.min(self.b.len())].iter().filter(|c| **c == b'\n').count();
        Issue::new(
            format!("$:{}:{}", line, self.i),
            "json_syntax_error",
            format!("{msg}（第 {line} 行，字节偏移 {}）", self.i),
        )
    }

    fn peek(&self) -> Option<u8> {
        self.b.get(self.i).copied()
    }

    fn value(&mut self, path: &str, depth: usize, out: &mut Vec<Issue>) -> Result<(), Issue> {
        if depth > MAX_DEPTH {
            return Err(Issue::new(
                path,
                "nesting_too_deep",
                format!("JSON 嵌套超过 {MAX_DEPTH} 层，无法安全解析"),
            ));
        }
        match self.peek() {
            Some(b'{') => self.object(path, depth, out),
            Some(b'[') => self.array(path, depth, out),
            Some(b'"') => self.string().map(|_| ()),
            Some(b't') => self.literal(b"true"),
            Some(b'f') => self.literal(b"false"),
            Some(b'n') => self.literal(b"null"),
            Some(b'-' | b'0'..=b'9') => self.number(),
            Some(_) => Err(self.err("JSON 语法错误：无法识别的值")),
            None => Err(self.err("JSON 语法错误：文档意外结束")),
        }
    }

    fn object(&mut self, path: &str, depth: usize, out: &mut Vec<Issue>) -> Result<(), Issue> {
        self.i += 1; // '{'
        let mut seen: Vec<String> = Vec::new();
        self.ws();
        if self.peek() == Some(b'}') {
            self.i += 1;
            return Ok(());
        }
        loop {
            self.ws();
            if self.peek() != Some(b'"') {
                return Err(self.err("JSON 语法错误：对象字段名必须是字符串"));
            }
            let key = self.string()?;
            let child = join_key(path, &key);
            if seen.iter().any(|k| k == &key) {
                out.push(Issue::new(
                    child.clone(),
                    "duplicate_key",
                    format!("重复的 JSON 字段 `{key}`：拒绝保存，避免静默 last-wins"),
                ));
            } else {
                seen.push(key);
            }
            self.ws();
            if self.peek() != Some(b':') {
                return Err(self.err("JSON 语法错误：字段名后缺少 ':'"));
            }
            self.i += 1;
            self.ws();
            self.value(&child, depth + 1, out)?;
            self.ws();
            match self.peek() {
                Some(b',') => {
                    self.i += 1;
                }
                Some(b'}') => {
                    self.i += 1;
                    return Ok(());
                }
                _ => return Err(self.err("JSON 语法错误：对象缺少 ',' 或 '}'")),
            }
        }
    }

    fn array(&mut self, path: &str, depth: usize, out: &mut Vec<Issue>) -> Result<(), Issue> {
        self.i += 1; // '['
        self.ws();
        if self.peek() == Some(b']') {
            self.i += 1;
            return Ok(());
        }
        let mut idx = 0usize;
        loop {
            self.ws();
            let child = format!("{path}[{idx}]");
            self.value(&child, depth + 1, out)?;
            idx += 1;
            self.ws();
            match self.peek() {
                Some(b',') => {
                    self.i += 1;
                }
                Some(b']') => {
                    self.i += 1;
                    return Ok(());
                }
                _ => return Err(self.err("JSON 语法错误：数组缺少 ',' 或 ']'")),
            }
        }
    }

    /// Consume a JSON string literal and return its decoded value.
    fn string(&mut self) -> Result<String, Issue> {
        debug_assert_eq!(self.peek(), Some(b'"'));
        let start = self.i;
        self.i += 1;
        loop {
            match self.peek() {
                None => return Err(self.err("JSON 语法错误：字符串未结束")),
                Some(b'"') => {
                    self.i += 1;
                    break;
                }
                Some(b'\\') => {
                    self.i += 2;
                }
                Some(c) if c < 0x20 => {
                    return Err(self.err("JSON 语法错误：字符串中出现未转义控制字符"))
                }
                Some(_) => self.i += 1,
            }
        }
        // The slice is valid UTF-8 by construction (input was &str and we only
        // cut on ASCII delimiters), so this cannot fail.
        let raw = &self.b[start..self.i];
        serde_json::from_slice::<String>(raw).map_err(|_| self.err("JSON 语法错误：非法字符串转义"))
    }

    fn literal(&mut self, lit: &[u8]) -> Result<(), Issue> {
        if self.b[self.i..].starts_with(lit) {
            self.i += lit.len();
            Ok(())
        } else {
            Err(self.err("JSON 语法错误：非法字面量"))
        }
    }

    fn number(&mut self) -> Result<(), Issue> {
        let start = self.i;
        if self.peek() == Some(b'-') {
            self.i += 1;
        }
        while matches!(self.peek(), Some(b'0'..=b'9' | b'.' | b'e' | b'E' | b'+' | b'-')) {
            self.i += 1;
        }
        let raw = &self.b[start..self.i];
        serde_json::from_slice::<Value>(raw)
            .map(|_| ())
            .map_err(|_| self.err("JSON 语法错误：非法数字"))
    }
}

fn join_key(path: &str, key: &str) -> String {
    let simple = !key.is_empty()
        && key
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-');
    if simple {
        format!("{path}.{key}")
    } else {
        let quoted = serde_json::to_string(key).unwrap_or_else(|_| "\"?\"".into());
        format!("{path}[{quoted}]")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dups(text: &str) -> Vec<String> {
        let mut out = Vec::new();
        assert!(check_duplicates(text, &mut out).is_ok(), "{text}");
        out.into_iter().map(|i| i.path).collect()
    }

    #[test]
    fn clean_documents_have_no_duplicates() {
        assert!(dups(r#"{"a":1,"b":{"c":[1,2,{"d":null}]}}"#).is_empty());
        assert!(dups("[]").is_empty());
        assert!(dups("{}").is_empty());
        assert!(dups("3").is_empty());
        assert!(dups(r#""str""#).is_empty());
        assert!(dups(r#"[{"a":1},{"a":2}]"#).is_empty());
        assert!(dups(r#"{"a":"a","b":"b"}"#).is_empty());
        assert!(dups(r#"{"a":{"k":1},"b":{"k":2}}"#).is_empty());
    }

    #[test]
    fn repeated_keys_are_found_at_every_depth() {
        assert_eq!(dups(r#"{"a":1,"a":2}"#), vec!["$.a"]);
        assert_eq!(dups(r#"{"x":{"y":[0,{"z":1,"z":2}]}}"#), vec!["$.x.y[1].z"]);
        assert_eq!(dups(r#"[{"k":1,"k":1}]"#), vec!["$[0].k"]);
        assert_eq!(dups(r#"{"a\"b":1,"a\"b":2}"#), vec![r#"$["a\"b"]"#]);
    }

    #[test]
    fn syntax_error_is_reported() {
        let mut out = Vec::new();
        let err = check_duplicates(r#"{"a":}"#, &mut out).unwrap_err();
        assert_eq!(err.code, "json_syntax_error");
        assert!(err.path.starts_with("$:"), "{err:?}");
        assert!(check_duplicates(r#"{"a":1"#, &mut out).is_err());
        assert!(check_duplicates(r#"{"a":1} x"#, &mut out).is_err());
        assert!(check_duplicates("", &mut out).is_err());
        assert!(check_duplicates(r#"{"a":01}"#, &mut out).is_err());
    }

    #[test]
    fn deep_nesting_is_refused_not_overflowed() {
        let deep = format!("{}{}", "[".repeat(5000), "]".repeat(5000));
        let mut out = Vec::new();
        let err = check_duplicates(&deep, &mut out).unwrap_err();
        assert_eq!(err.code, "nesting_too_deep");
    }

    #[test]
    fn escaped_strings_are_decoded_for_key_comparison() {
        // "\u0061" decodes to "a", so this is a duplicate of "a".
        assert_eq!(dups(r#"{"a":1,"\u0061":2}"#), vec!["$.a"]);
    }

    #[test]
    fn bom_is_stripped() {
        assert_eq!(strip_bom("\u{feff}{}"), "{}");
        assert_eq!(strip_bom("{}"), "{}");
    }

    #[test]
    fn size_limit_is_enforced() {
        let big = "a".repeat(MAX_INPUT_BYTES + 1);
        assert!(check_size(&big).is_err());
        let ok = "a".repeat(MAX_INPUT_BYTES);
        assert!(check_size(&ok).is_ok());
    }
}
