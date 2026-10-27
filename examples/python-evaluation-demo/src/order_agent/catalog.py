"""Platform-side task gold and evaluation configuration; never sent to the agent."""
from typing import Any


def dataset() -> dict[str, Any]:
    return {"cases": [
        {"instruction": "查询订单 A，返回金额和状态。", "input": {"order_id": "A"},
         "expected": {"amount": 100, "status": "paid"}},
        {"instruction": "查询订单 B，返回金额和状态。", "input": {"order_id": "B"},
         "expected": {"amount": 250, "status": "shipped"}},
        {"instruction": "查询不存在的订单 Z。", "input": {"order_id": "Z"},
         "expected": {"status": "not_found"}},
    ]}


def evaluator_definitions() -> list[dict[str, Any]]:
    return [
        {"type": "RULE", "criterion_id": "answer_exact", "threshold": 1,
         "required_evidence": ["output", "expected"],
         "config": {"operator": "eq", "selector": "/output", "expected_selector": "/expected"}},
        {"type": "RULE", "criterion_id": "tool_called", "threshold": 1,
         "required_evidence": ["trace"],
         "config": {"operator": "tool_set", "tools": ["query_order"], "mode": "exact"}},
        {"type": "RULE", "criterion_id": "tool_args", "threshold": 1,
         "required_evidence": ["trace", "task_input"],
         "config": {"operator": "tool_arguments", "tool": "query_order", "selector": "/args/0",
                    "expected_selector": "/task_input/order_id", "comparison": "eq", "aggregation": "all"}},
    ]


def suite_content(versions: list[str]) -> dict[str, Any]:
    if len(versions) != 3:
        raise ValueError("Three evaluator versions are required")
    return {"entries": [
        {"evaluator_version": version, "dimension": dimension, "required_to_pass": True, "weight": 1}
        for version, dimension in zip(versions, ["outcome", "process", "process"], strict=True)
    ]}
