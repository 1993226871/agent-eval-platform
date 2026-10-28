"""A local order lookup, with no real payment or model service connections."""
import argparse
import json
import os
import signal
from datetime import datetime, timedelta, timezone
from threading import Event
from agent_eval import AgentResult, EvaluationWorker, TaskContext, observe


@observe("tool", name="query_order")
def query_order(order_id):
    orders = {"A": {"amount": 100, "status": "paid"}}
    return dict(orders.get(order_id, {"status": "not_found"}))


def agent(context: TaskContext) -> AgentResult:
    if context.cancelled:
        return AgentResult({"status": "cancelled"})
    return AgentResult(query_order(context.input.get("order_id")))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--local", action="store_true", help="run the fixed demonstration without a platform")
    parser.add_argument("--config", help="path to private SDK JSON configuration")
    args = parser.parse_args()
    if args.local:
        context = TaskContext("local-demo", "Query order A", {"order_id": "A"}, datetime.now(timezone.utc) + timedelta(seconds=60), Event())
        print(json.dumps(agent(context).output, ensure_ascii=False))
        return
    agent_id = os.environ.get("AGENT_EVAL_AGENT_ID")
    if not args.config and not agent_id:
        raise SystemExit("AGENT_EVAL_AGENT_ID is required")
    stopped = Event()
    signal.signal(signal.SIGINT, lambda *_: stopped.set())
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    worker = (EvaluationWorker.from_config(args.config, handler=agent) if args.config
              else EvaluationWorker.from_env().register_agent(agent_id, agent))
    try:
        worker.start()
        print("Order agent Worker is waiting for platform tasks.")
        stopped.wait()
    finally:
        worker.stop()


if __name__ == "__main__":
    main()
