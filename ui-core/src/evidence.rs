//! `summarize_evidence` — turn runtime JSON into one honest UI badge.
//!
//! The rules come straight from the contract and are enforced in this order:
//!
//! 1. Input that cannot be parsed/understood is `invalid_input`, never a pass.
//! 2. A hard-failure status (`reader_fields_unconfirmed`, `conflict`,
//!    `stale_state`, `session_replaced`, `session_unconfirmed`, `timeout`, …)
//!    is absolute: it wins over any other field, including `readerSidePassed`.
//! 3. `machineVerdict=unverifiable` is never a pass.
//! 4. `verdictScope=sandbox_only` (or any sandbox-only signal) caps the scope at
//!    sandbox: static VALIDATED and sandbox `full` never become a reader pass.
//! 5. Only an explicit `reader_verified` delivery status, or
//!    `readerSidePassed=true` together with `identityMatches`/`confirmed` and a
//!    complete execution with no failure signals, yields `passed=true`.
//! 6. Absent fields are never inferred: the result is `unknown`, not a guess.

use serde_json::{Map, Value};

use crate::json::{parse_strict, Parsed};

/// Statuses that can never be a pass, whatever else the payload says.
const HARD_FAILURES: &[(&str, &str, &str)] = &[
    (
        "reader_fields_unconfirmed",
        "阅读端字段未确认",
        "本地字段尚未确认在阅读端生效，不能算通过",
    ),
    (
        "conflict",
        "阅读端内容冲突",
        "阅读中已存在同地址但内容不同的书源，需先确认覆盖",
    ),
    (
        "stale_state",
        "读到旧状态",
        "轮询读到的是检验开始前或其它版本的旧状态，不能据此判定通过",
    ),
    (
        "session_replaced",
        "检验会话被取代",
        "本次检验被另一个会话取代，没有拿到自己的结果",
    ),
    (
        "session_unconfirmed",
        "无法确认归属本次",
        "拿到的新状态无法确认来自本次检验，不能据此判定通过",
    ),
    (
        "timeout",
        "等待超时",
        "等待阅读端结果超时，阅读可能仍在检验，不代表通过",
    ),
    (
        "failed",
        "未通过",
        "阅读端检验结果为未通过",
    ),
    (
        "uncheckable",
        "无法检验",
        "阅读端判定该书源无法检验",
    ),
    (
        "reader_debug_incomplete",
        "调试不完整",
        "阅读端调试缺少完整终止证据，不能声称完整或通过",
    ),
    (
        "reader_failure_signals",
        "存在失败信号",
        "阅读端日志出现失败信号，不能算通过",
    ),
    (
        "reader_evidence_unmatched",
        "证据不匹配",
        "现有记录未确认属于当前书源内容与阅读配置",
    ),
    (
        "reader_not_executed",
        "阅读端未执行",
        "阅读端调试没有运行，不得宣称书源可用",
    ),
    (
        "not_in_reader",
        "阅读端无此书源",
        "回读未确认阅读中存在该书源",
    ),
    (
        "reader_unavailable",
        "阅读端不可达",
        "连接阅读失败，本次没有取得结果",
    ),
    (
        "session_ended_without_result",
        "会话无结果",
        "阅读端检验会话已结束但没有拿到该书源最终状态",
    ),
    (
        "reader_partial",
        "部分覆盖",
        "阅读端仅有部分阶段记录，未覆盖不等于通过",
    ),
    (
        "reader_executed",
        "已执行未验证",
        "阅读端调试已完整结束并记录阶段，但阶段到达不是输出正确",
    ),
    (
        "reader_debug_ran",
        "调试已运行",
        "阅读端调试已运行，旧版通道不产出通过结论",
    ),
    (
        "sandbox_only_not_reader_verified",
        "仅沙箱",
        "缺少当前阅读端执行记录，沙箱结果不能当作阅读端可用",
    ),
    (
        "error",
        "出错",
        "运行时返回错误，不能算通过",
    ),
];

const STATUS_KEYS: &[&str] = &["deliveryStatus", "status", "verifyStatus", "currentStatus"];

pub fn summarize(text: &str) -> Value {
    let doc = match parse_strict(text) {
        Parsed::Ok(Value::Object(m)) => m,
        Parsed::Ok(_) => return invalid("输入不是 JSON 对象，无法判断证据范围"),
        Parsed::Rejected(issues) => return invalid(&issues[0].message),
    };
    summarize_object(&doc)
}

fn summarize_object(root: &Map<String, Value>) -> Value {
    let mut notes: Vec<String> = Vec::new();

    // The reader summary may be nested (check_source puts it under
    // readerExecution). Keep it separate from the root: the root's
    // `verdictScope` describes the *outer* result and must not cap a nested
    // reader record.
    let nested_reader: Option<&Map<String, Value>> = root
        .get("readerExecution")
        .and_then(Value::as_object)
        .filter(|_| matches!(root.get("verdictScope"), Some(Value::String(s)) if s.eq_ignore_ascii_case("sandbox_only")));
    let fields: Map<String, Value> = match nested_reader {
        Some(inner) => {
            let mut m = inner.clone();
            // Identity/execution facts the outer payload may carry.
            for k in ["identityMatches", "executionComplete", "failureSignals"] {
                if let Some(v) = root.get(k) {
                    m.entry(k.to_string()).or_insert_with(|| v.clone());
                }
            }
            m
        }
        None => root.clone(),
    };
    let get = |k: &str| fields.get(k);

    // 1. Hard failures win over everything else.
    let mut hard: Option<(&str, &str, &str)> = None;
    for key in STATUS_KEYS {
        if let Some(Value::String(s)) = get(key) {
            let low = s.to_ascii_lowercase();
            if let Some(hit) = HARD_FAILURES.iter().find(|(code, _, _)| *code == low) {
                hard = Some(*hit);
                break;
            }
        }
    }
    if hard.is_none() {
        if let Some(Value::String(t)) = get("timeoutKind") {
            if !t.trim().is_empty() {
                hard = Some((
                    "timeout",
                    "等待超时",
                    "阅读端等待超时，阅读可能仍在检验，不代表通过",
                ));
            }
        }
    }
    if let Some((code, label, why)) = hard {
        notes.push(why.to_string());
        return verdict(
            scope_of(&fields),
            code,
            label,
            "danger",
            false,
            notes,
        );
    }

    // 2. unverifiable machine verdicts are never a pass.
    let unverifiable = matches!(get("machineVerdict"), Some(Value::String(s)) if s.eq_ignore_ascii_case("unverifiable"));
    if unverifiable {
        notes.push("machineVerdict=unverifiable：该通道不产出通过结论".to_string());
        let label = match get("status").and_then(Value::as_str) {
            Some(s) if !s.is_empty() => "仅调试日志（unverifiable）",
            _ => "无法核验（unverifiable）",
        };
        return verdict("reader_debug", "unverifiable", label, "warning", false, notes);
    }

    // 3. Sandbox-only / static evidence caps the scope: it is never a reader
    //    pass. `VALIDATED` is by definition a static verdict, so it implies
    //    static scope even when the payload carries no explicit scope key.
    let status_text = get("status").and_then(Value::as_str).map(str::to_string);
    let delivery = get("deliveryStatus").and_then(Value::as_str).map(str::to_string);
    let static_validation = status_text
        .as_deref()
        .map(|s| s.eq_ignore_ascii_case("validated"))
        .unwrap_or(false)
        || matches!(get("verdict"), Some(Value::String(s)) if s.eq_ignore_ascii_case("validated"));
    let sandbox_only = matches!(get("verdictScope"), Some(Value::String(s)) if s.eq_ignore_ascii_case("sandbox_only"))
        || matches!(get("scope"), Some(Value::String(s)) if s.eq_ignore_ascii_case("sandbox"))
        || get("sandbox").is_some()
        || get("stagesReached").is_some()
        || static_validation;

    if sandbox_only {
        let (code, label) = if static_validation {
            ("VALIDATED", "静态检查通过（非阅读端）")
        } else if status_text.as_deref() == Some("full")
            || matches!(get("verdict"), Some(Value::String(s)) if s.eq_ignore_ascii_case("full"))
        {
            ("full", "沙箱样本通过（非阅读端）")
        } else {
            (
                status_text.as_deref().unwrap_or("sandbox_only"),
                "仅沙箱结果（非阅读端）",
            )
        };
        notes.push("证据范围是沙箱/静态：不能当作阅读端通过".to_string());
        if let Some(missing) = get("missingStages").and_then(Value::as_array) {
            if !missing.is_empty() {
                notes.push(format!("未到达阶段：{}", missing.len()));
            }
        }
        // `VALIDATED` is a static verdict, not a sandbox sample run: report the
        // narrower scope so the UI cannot blur the two.
        let scope = if static_validation { "static" } else { "sandbox" };
        return verdict(scope, code, label, "warning", false, notes);
    }

    // Explicit contradictory evidence always overrides a positive status label.
    if failure_signals(get("failureSignals")) > 0
        || matches!(get("identityMatches"), Some(Value::Bool(false)))
        || matches!(get("confirmed"), Some(Value::Bool(false)))
        || matches!(get("executionComplete"), Some(Value::Bool(false)))
        || matches!(get("readerSidePassed"), Some(Value::Bool(false)))
    {
        notes.push("存在失败信号或明确未确认/未完成状态，不能算阅读端通过".to_string());
        return verdict("reader_partial", "reader_side_unconfirmed", "阅读端证据不完整（未通过）", "warning", false, notes);
    }

    // 4. Reader-side pass: only explicit, complete, identity-matched evidence.
    let reader_verified = delivery.as_deref() == Some("reader_verified")
        || status_text.as_deref() == Some("reader_verified");
    if reader_verified {
        notes.push("阅读端结构化检验结果，仅代表该次检验范围".to_string());
        return verdict("reader", "reader_verified", "阅读端已验证", "success", true, notes);
    }

    let reader_side_passed = matches!(get("readerSidePassed"), Some(Value::Bool(true)));
    if reader_side_passed {
        let identity = matches!(get("identityMatches"), Some(Value::Bool(true)))
            || matches!(get("confirmed"), Some(Value::Bool(true)));
        let complete = match get("executionComplete").or_else(|| get("complete")) {
            Some(Value::Bool(c)) => *c,
            _ => false,
        };
        let failures = failure_signals(get("failureSignals"));
        if !identity {
            notes.push("identityMatches/confirmed 未确认，不能算阅读端通过".to_string());
        }
        if !complete {
            notes.push("执行未完整结束，不能算阅读端通过".to_string());
        }
        if failures > 0 {
            notes.push(format!("存在失败信号 {failures} 项"));
        }
        if identity && complete && failures == 0 {
            notes.push("阅读端执行完整、身份匹配且无失败信号".to_string());
            return verdict("reader", "reader_passed", "阅读端通过", "success", true, notes);
        }
        return verdict(
            "reader_partial",
            "reader_side_unconfirmed",
            "阅读端证据不完整（未通过）",
            "warning",
            false,
            notes,
        );
    }

    // 5. Reader scope without a pass claim: report what is there, never infer.
    let reader_scope = matches!(get("verdictScope"), Some(Value::String(s)) if s.eq_ignore_ascii_case("reader"))
        || delivery.is_some()
        || get("readerSideExecuted").is_some()
        || get("sessionToken").is_some();
    if reader_scope {
        let code = delivery
            .clone()
            .or_else(|| status_text.clone())
            .unwrap_or_else(|| "reader_unconfirmed".to_string());
        notes.push("阅读端有记录但没有通过结论".to_string());
        return verdict("reader", &code, "阅读端未通过", "warning", false, notes);
    }

    // 6. Nothing recognizable: say so instead of guessing.
    notes.push("输入中没有可识别的证据字段，无法判断范围".to_string());
    verdict("unknown", "unknown", "未知证据", "neutral", false, notes)
}

/// Count failure signals. A non-empty string counts as one signal (it is a
/// failure marker, not an absence); `null`/missing counts as none.
fn failure_signals(v: Option<&Value>) -> usize {
    match v {
        None | Some(Value::Null) => 0,
        Some(Value::Array(a)) => a.len(),
        Some(Value::String(s)) => usize::from(!s.trim().is_empty()),
        Some(Value::Bool(true)) => 1,
        Some(Value::Object(o)) => o.len(),
        Some(_) => 0,
    }
}

fn scope_of(fields: &Map<String, Value>) -> &'static str {
    if matches!(fields.get("verdictScope"), Some(Value::String(s)) if s.eq_ignore_ascii_case("sandbox_only")) {
        "sandbox"
    } else if fields.get("readerExecution").is_some() || fields.get("readerSidePassed").is_some() {
        "reader"
    } else if fields.get("sessionToken").is_some() || fields.get("status").is_some() {
        "reader"
    } else {
        "unknown"
    }
}

fn invalid(message: &str) -> Value {
    let mut notes = vec!["输入不是可解析的运行时 JSON，无法判断证据范围".to_string()];
    if !message.is_empty() {
        notes.push(message.to_string());
    }
    verdict("unknown", "invalid_input", "输入无效", "danger", false, notes)
}

fn verdict(
    scope: &str,
    status: &str,
    label: &str,
    tone: &str,
    passed: bool,
    notes: Vec<String>,
) -> Value {
    let mut out = Map::new();
    out.insert("scope".into(), Value::String(scope.to_string()));
    out.insert("status".into(), Value::String(status.to_string()));
    out.insert("label".into(), Value::String(label.to_string()));
    out.insert("tone".into(), Value::String(tone.to_string()));
    out.insert("passed".into(), Value::Bool(passed));
    out.insert(
        "notes".into(),
        Value::Array(notes.into_iter().map(Value::String).collect()),
    );
    Value::Object(out)
}
