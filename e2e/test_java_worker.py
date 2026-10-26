def test_java_sdk_reports_nested_trace_and_all_evaluator_types(platform):
    report = platform.sdk_report("java")
    assert report["evaluator_types"] == {"RULE", "LLM", "PYTHON"}
    assert report["verdict"] == "PASS"
    assert report["root_spans"] == 1 and report["tool_spans"] == 1
