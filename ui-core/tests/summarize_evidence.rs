//! Contract tests for `summarize_evidence`.
//!
//! The summarizer is the honesty gate between runtime JSON and the UI badge.
//! Every rule in the contract has a positive and a negative test: static
//! VALIDATED is never a reader pass, sandbox `full` is never a reader pass,
//! `unverifiable` is never a pass, and the failure statuses are absolute.

mod common;

use common::*;
use serde_json::{json, Value};
use studio_ui_core::summarize_evidence;

fn sum(text: &str) -> serde_json::Map<String, Value> {
    let raw = summarize_evidence(text);
    obj(&raw)
}

fn passed(text: &str) -> bool {
    b(&json!(sum(text)), "passed")
}

/// A reader-side record that *does* satisfy every contract condition.
const READER_VERIFIED: &str = r#"{
  "status": "reader_verified",
  "deliveryStatus": "reader_verified",
  "verdictScope": "reader",
  "readerSidePassed": true,
  "identityMatches": true,
  "executionComplete": true,
  "failureSignals": []
}"#;

#[test]
fn output_shape_is_complete() {
    let out = sum(READER_VERIFIED);
    for key in ["scope", "status", "label", "tone", "passed", "notes"] {
        assert!(out.contains_key(key), "missing {key} in {out:?}");
    }
    assert!(out["notes"].is_array(), "notes must be an array: {out:?}");
    assert!(s(&json!(out), "label").chars().count() > 0);
    let tone = out["tone"].as_str().expect("tone");
    assert!(["success", "warning", "danger", "neutral", "info"].contains(&tone), "tone {tone}");
}

#[test]
fn reader_verified_passes_with_reader_scope() {
    let out = sum(READER_VERIFIED);
    assert_eq!(b(&json!(out), "passed"), true);
    assert_eq!(s(&json!(out), "scope"), "reader");
    assert_eq!(s(&json!(out), "status"), "reader_verified");
    assert_eq!(s(&json!(out), "tone"), "success");
}

#[test]
fn reader_side_passed_true_with_identity_and_no_failures_passes() {
    let text = r#"{"readerSidePassed": true, "identityMatches": true, "failureSignals": [], "executionComplete": true}"#;
    assert!(passed(text));
    assert_eq!(s(&json!(sum(text)), "scope"), "reader");
}

#[test]
fn reader_side_passed_without_identity_does_not_pass() {
    assert!(!passed(r#"{"readerSidePassed": true, "failureSignals": []}"#));
    assert!(!passed(r#"{"readerSidePassed": true, "identityMatches": false, "failureSignals": []}"#));
    // identity unknown (not a bool) is not a confirmation either
    assert!(!passed(r#"{"readerSidePassed": true, "identityMatches": "yes", "failureSignals": []}"#));
}

#[test]
fn reader_side_passed_requires_execution_complete() {
    assert!(!passed(
        r#"{"readerSidePassed": true, "identityMatches": true, "executionComplete": false, "failureSignals": []}"#
    ));
    assert!(!passed(
        r#"{"readerSidePassed": true, "identityMatches": true, "complete": false, "failureSignals": []}"#
    ));
}

#[test]
fn reader_side_passed_with_failure_signals_does_not_pass() {
    assert!(!passed(
        r#"{"readerSidePassed": true, "identityMatches": true, "executionComplete": true, "failureSignals": ["http_403"]}"#
    ));
    // A non-empty string is a failure signal too, not "no signals".
    assert!(!passed(
        r#"{"readerSidePassed": true, "identityMatches": true, "executionComplete": true, "failureSignals": "http_403"}"#
    ));
}

#[test]
fn reader_side_passed_as_string_is_not_a_pass() {
    assert!(!passed(
        r#"{"readerSidePassed": "true", "identityMatches": true, "executionComplete": true, "failureSignals": []}"#
    ));
}

#[test]
fn validated_static_is_never_a_reader_pass() {
    let out = sum(r#"{"status": "VALIDATED", "verdict": "VALIDATED", "scope": "static"}"#);
    assert_eq!(b(&json!(out), "passed"), false);
    assert_eq!(s(&json!(out), "scope"), "static");
    assert_eq!(s(&json!(out), "status"), "VALIDATED");
    assert_ne!(s(&json!(out), "tone"), "success");
}

#[test]
fn sandbox_full_verdict_is_not_a_reader_pass() {
    for text in [
        r#"{"status": "full", "verdict": "full", "verdictScope": "sandbox_only", "stagesReached": ["搜索","详情","目录","正文"]}"#,
        r#"{"status": "PASSED", "verdictScope": "sandbox_only", "readerWebVerification": "not_performed"}"#,
        r#"{"stagesReached": ["搜索","详情","目录","正文"], "missingStages": [], "sandbox": {"verdict": "full"}}"#,
    ] {
        let out = sum(text);
        assert_eq!(b(&json!(out), "passed"), false, "{text}");
        assert_eq!(s(&json!(out), "scope"), "sandbox", "{text}");
        assert_ne!(s(&json!(out), "tone"), "success", "{text}");
    }
}

#[test]
fn machine_verdict_unverifiable_is_not_a_pass() {
    for text in [
        r#"{"status": "legacy_debug_only", "machineVerdict": "unverifiable"}"#,
        r#"{"status": "PASSED", "machineVerdict": "unverifiable"}"#,
        r#"{"machineVerdict": "unverifiable", "logVerdict": "all_stages_reached_no_failure", "readerSidePassed": true, "identityMatches": true, "executionComplete": true, "failureSignals": []}"#,
    ] {
        assert!(!passed(text), "{text}");
    }
}

#[test]
fn hard_failure_statuses_never_pass() {
    for status in [
        "reader_fields_unconfirmed",
        "conflict",
        "stale_state",
        "session_replaced",
        "session_unconfirmed",
        "timeout",
        "failed",
        "FAILED",
        "uncheckable",
        "UNCHECKABLE",
        "reader_debug_incomplete",
        "reader_failure_signals",
        "reader_evidence_unmatched",
        "reader_not_executed",
        "not_in_reader",
        "reader_unavailable",
        "session_ended_without_result",
        "sandbox_only_not_reader_verified",
        "reader_partial",
        "reader_executed",
        "reader_debug_ran",
        "error",
    ] {
        let text = format!(
            r#"{{"status": "{status}", "readerSidePassed": true, "identityMatches": true, "executionComplete": true, "failureSignals": []}}"#
        );
        let out = sum(&text);
        assert_eq!(b(&json!(out), "passed"), false, "{status}");
        assert_ne!(s(&json!(out), "tone"), "success", "{status}");
    }
}

#[test]
fn hard_failure_statuses_are_recognized_even_in_delivery_status() {
    for status in ["reader_fields_unconfirmed", "stale_state", "session_replaced", "timeout"] {
        let text = format!(r#"{{"deliveryStatus": "{status}"}}"#);
        let out = sum(&text);
        assert_eq!(b(&json!(out), "passed"), false, "{status}");
        assert_eq!(s(&json!(out), "status"), status, "{status}");
    }
}

#[test]
fn delivery_status_reader_verified_passes() {
    let out = sum(r#"{"deliveryStatus": "reader_verified", "identityMatches": true, "executionComplete": true}"#);
    assert_eq!(b(&json!(out), "passed"), true);
    assert_eq!(s(&json!(out), "scope"), "reader");
}

#[test]
fn timeout_kind_alone_blocks_even_with_a_passed_status() {
    // Reader-side timeout observed by the MCP waiter: never a pass.
    assert!(!passed(
        r#"{"status": "PASSED", "timeoutKind": "idle", "readerSidePassed": true, "identityMatches": true, "executionComplete": true, "failureSignals": []}"#
    ));
}

#[test]
fn missing_fields_are_not_inferred() {
    for text in ["{}", r#"{"unrelated": 1}"#, r#"{"status": "weird_new_status"}"#] {
        let out = sum(text);
        assert_eq!(b(&json!(out), "passed"), false, "{text}");
        assert_eq!(s(&json!(out), "scope"), "unknown", "{text}");
        assert_eq!(s(&json!(out), "tone"), "neutral", "{text}");
        assert_eq!(s(&json!(out), "status"), "unknown", "{text}");
    }
}

#[test]
fn invalid_json_is_a_structured_warning_not_a_pass() {
    let out = sum("{oops");
    assert_eq!(b(&json!(out), "passed"), false);
    assert_eq!(s(&json!(out), "scope"), "unknown");
    assert_eq!(s(&json!(out), "status"), "invalid_input");
    assert_eq!(s(&json!(out), "tone"), "danger");
}

#[test]
fn non_object_json_is_a_structured_warning() {
    let out = sum("[1,2,3]");
    assert_eq!(b(&json!(out), "passed"), false);
    assert_eq!(s(&json!(out), "status"), "invalid_input");
}

#[test]
fn oversized_input_is_rejected() {
    let pad = "a".repeat(2 * 1024 * 1024);
    let out = sum(&format!(r#"{{"status":"{pad}"}}"#));
    assert_eq!(b(&json!(out), "passed"), false);
    assert_eq!(s(&json!(out), "status"), "invalid_input");
}

#[test]
fn notes_explain_why_and_never_leak_values() {
    let out = sum(r#"{"status": "legacy_debug_only", "machineVerdict": "unverifiable", "header": "Bearer SECRET-XYZ"}"#);
    let notes = out["notes"].as_array().unwrap();
    assert!(!notes.is_empty());
    let joined: String = notes.iter().filter_map(|n| n.as_str()).collect::<Vec<_>>().join("\n");
    assert!(!joined.contains("SECRET"), "notes leaked a value: {joined}");
    assert!(joined.contains("unverifiable"), "notes should name the blocking field: {joined}");
}

#[test]
fn nested_reader_execution_is_understood() {
    // check_source nests the reader summary under readerExecution.
    let text = r#"{"verdictScope": "sandbox_only",
        "readerExecution": {"executed": true, "identityMatches": true, "executionComplete": true,
                            "failureSignals": [], "readerSidePassed": true}}"#;
    let out = sum(text);
    assert_eq!(b(&json!(out), "passed"), true);
    assert_eq!(s(&json!(out), "scope"), "reader");
}

#[test]
fn nested_reader_execution_with_failure_does_not_pass() {
    let text = r#"{"verdictScope": "sandbox_only",
        "readerExecution": {"executed": true, "identityMatches": true, "executionComplete": true,
                            "failureSignals": ["http_500"], "readerSidePassed": true}}"#;
    assert_eq!(b(&json!(sum(text)), "passed"), false);
}

#[test]
fn sandbox_scope_with_reader_claims_stays_sandbox() {
    // verdictScope=sandbox_only is authoritative: the UI must not upgrade it.
    let out = sum(
        r#"{"verdictScope": "sandbox_only", "status": "reader_verified", "readerSidePassed": true,
            "identityMatches": true, "executionComplete": true, "failureSignals": []}"#,
    );
    assert_eq!(b(&json!(out), "passed"), false);
    assert_eq!(s(&json!(out), "scope"), "sandbox");
}

#[test]
fn label_is_localized_chinese() {
    let out = sum(READER_VERIFIED);
    let label = out["label"].as_str().expect("label");
    assert!(label.chars().any(|c| ('\u{4e00}'..='\u{9fff}').contains(&c)), "{label}");
}

#[test]
fn deterministic_output_for_same_input() {
    let a = summarize_evidence(READER_VERIFIED);
    let b_out = summarize_evidence(READER_VERIFIED);
    assert_eq!(a, b_out);
}
