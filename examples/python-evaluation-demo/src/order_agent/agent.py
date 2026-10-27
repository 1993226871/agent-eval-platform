"""Business logic. No platform API calls or evaluation answers here."""
from collections.abc import Callable
from typing import Any

from agent_eval import AgentResult, TaskContext, observe

_ORDERS: dict[str, dict[str, Any]] = {
    "A": {"amount": 100, "status": "paid"},
    "B": {"amount": 250, "status": "shipped"},
}


@observe("tool", name="query_order")
def query_order(order_id: str) -> dict[str, Any]:
    """Read a simulated order system. Return a copy to avoid shared mutation."""
    return dict(_ORDERS.get(order_id, {"status": "not_found"}))


def make_agent(variant: str) -> Callable[[TaskContext], AgentResult]:
    if variant not in {"correct", "buggy"}:
        raise ValueError("variant must be correct or buggy")

    def run(context: TaskContext) -> AgentResult:
        if context.cancelled:
            return AgentResult({"status": "cancelled"})
        result = query_order(context.input["order_id"])
        if variant == "buggy" and "amount" in result:
            # Intentional cents/unit bug: trace stays correct, final answer is wrong.
            result["amount"] = result["amount"] / 100
        return AgentResult(result)

    return run
