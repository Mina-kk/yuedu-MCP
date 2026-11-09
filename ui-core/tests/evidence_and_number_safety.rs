use serde_json::{json, Value};
use studio_ui_core::{compare_source_json, summarize_evidence};

#[test]
fn explicit_failure_overrides_even_reader_verified() {
    for patch in [json!({"failureSignals":["http_403"]}), json!({"identityMatches":false}), json!({"executionComplete":false})] {
        let mut value = json!({"deliveryStatus":"reader_verified"});
        value.as_object_mut().unwrap().extend(patch.as_object().unwrap().clone());
        let result: Value = serde_json::from_str(&summarize_evidence(&value.to_string())).unwrap();
        assert_eq!(result["passed"], false, "input={value}");
    }
}

#[test]
fn distinct_large_integers_must_not_compare_equal() {
    let result: Value = serde_json::from_str(&compare_source_json(
        r#"{"bookSourceUrl":"https://site.test","n":123456789012345678901234567890}"#,
        r#"{"bookSourceUrl":"https://site.test","n":123456789012345678901234567891}"#,
    )).unwrap();
    assert_eq!(result["equal"], false);
}
