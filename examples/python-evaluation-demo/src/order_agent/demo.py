"""Orchestrate the user-side demo; all actual scoring happens on the platform."""
import json
import time
from contextlib import ExitStack
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4

import httpx
from agent_eval import EvaluationWorker
from agent_eval.spool import FileSpool
from agent_eval.transport import HttpTransport

from .agent import make_agent
from .catalog import dataset, evaluator_definitions, suite_content
from .platform import Platform, tls_context


def save(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def wait_report(api: Platform, experiment: str, timeout: float) -> tuple[dict, list[dict]]:
    until = time.monotonic() + timeout
    while True:
        report = api.request("GET", f"/reports/experiments/{experiment}")
        trials = api.request("GET", f"/reports/experiments/{experiment}/trials?limit=100")["items"]
        # A FAIL verdict may appear before all criteria finish. Wait for every evaluator.
        if (len(trials) == report["planned_attempts"] and trials
                and all(t["evaluation_state"] in {"COMPLETED", "PARTIAL"} for t in trials)):
            return report, trials
        if time.monotonic() >= until:
            raise TimeoutError("评测尚未完成。资源 ID 已保存在 manifest.json；请在网页查看，不要自动重跑未知执行。")
        time.sleep(2)


def download_report(api: Platform, experiment: str, directory: Path, timeout: float) -> dict:
    report, trials = wait_report(api, experiment, timeout)
    save(directory / "report.json", report)
    save(directory / "trials.json", trials)
    for trial in trials:
        trace = api.request("GET", f"/traces/{trial['trial_id']}?limit=100")
        save(directory / f"trace-{trial['case_index']}.json", trace)
    return report


AGENT_NAMES = {"correct": "订单助手", "buggy": "金额异常助手"}
RULE_NAMES = {"answer_exact": "答案一致性", "tool_called": "工具选择", "tool_args": "工具参数"}


def prepare_demo(api: Platform, manifest: dict, manifest_path: Path, judge_model_id: str | None = None) -> None:
    model = None
    if judge_model_id:
        model = next((item for item in api.request("GET", "/models") if item["id"] == judge_model_id), None)
        if model is None:
            raise ValueError("请选择本人可用的评审模型")
        manifest["judge_model"] = {"id": model["id"], "name": model["name"]}
        print(f"评审模型：{model['name']}；本轮将创建 6 次首轮 LLM 评审，费用按实际用量计算。", flush=True)
    manifest["evaluation_mode"] = "RULE_AND_LLM" if model else "RULE"
    manifest["dataset_version"] = api.publish("DATASET", "订单查询", dataset())
    save(manifest_path, manifest)
    versions = []
    for definition in evaluator_definitions():
        versions.append(api.publish("EVALUATOR", RULE_NAMES[definition["criterion_id"]], definition))
        manifest["evaluator_versions"] = list(versions)
        save(manifest_path, manifest)
    if model:
        judge = api.request("POST", "/templates/judges/evidence-support/copy", {
            "model_profile_id": model["id"], "price_version_id": model["price"]["id"],
        })
        versions.append(api.request("POST", f"/catalog/{judge['id']}/versions")["id"])
        manifest["evaluator_versions"] = list(versions)
        save(manifest_path, manifest)
    manifest["suite_version"] = api.publish("SUITE", "订单综合评分" if model else "订单规则评分", suite_content(versions))
    save(manifest_path, manifest)
    for variant, name in AGENT_NAMES.items():
        manifest["agents"][variant] = api.request("POST", "/agents", {"name": name, "version": "v1"})["id"]
        save(manifest_path, manifest)


def run_demo(api: Platform, ca: Path | None, root: Path, timeout: float, judge_model_id: str | None = None) -> Path:
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid4().hex[:8]
    run_dir = root / run_id
    run_dir.mkdir(parents=True, mode=0o700)
    manifest: dict[str, Any] = {"run_id": run_id, "platform": api.url, "status": "PREPARING", "agents": {}, "experiments": {}}
    manifest_path = run_dir / "manifest.json"
    save(manifest_path, manifest)
    print(f"本轮记录目录：{run_dir.resolve()}", flush=True)
    prepare_demo(api, manifest, manifest_path, judge_model_id)
    dataset_version = manifest["dataset_version"]
    suite_version = manifest["suite_version"]
    credential = api.request("POST", "/worker-credentials", {"agent_ids": list(manifest["agents"].values())})
    # Secret lives only in process memory; store its revocable identifier, never its value.
    manifest["credential_id"] = credential["id"]
    manifest["status"] = "RUNNING"
    save(manifest_path, manifest)
    try:
        with ExitStack() as resources:
            for variant, agent_id in manifest["agents"].items():
                directory = run_dir / variant
                directory.mkdir(mode=0o700)
                transport = HttpTransport(api.url, credential["secret"], client=httpx.Client(
                    verify=tls_context(ca), trust_env=False, timeout=httpx.Timeout(25, connect=5)))
                resources.callback(transport.close)
                spool = FileSpool(directory / "spool.sqlite")
                resources.callback(spool.close)
                worker = EvaluationWorker(transport, spool).register_agent(agent_id, make_agent(variant))
                resources.callback(worker.stop, 30)
                worker.start()
            for variant, agent_id in manifest["agents"].items():
                body = {"agent_id": agent_id, "agent_version": "v1", "dataset_version_id": dataset_version,
                        "suite_version_id": suite_version, "repeat_count": 1,
                        "deadline": (datetime.now(timezone.utc) + timedelta(minutes=5)).isoformat(),
                        "idempotency_key": run_id + ":" + variant}
                experiment = api.request("POST", "/experiments", body)["id"]
                manifest["experiments"][variant] = experiment
                save(manifest_path, manifest)
                print(f"{AGENT_NAMES[variant]} 报告：{api.url}/workspace/reports/{experiment}", flush=True)
            for variant, experiment in manifest["experiments"].items():
                report = download_report(api, experiment, run_dir / variant, timeout)
                print(f"{AGENT_NAMES[variant]}: PASS {report['passed_attempts']}/{report['planned_attempts']}, "
                      f"FAIL {report['failed_attempts']}, 未确定 {report['undetermined_attempts']}", flush=True)
        manifest["status"] = "COMPLETED"
    except BaseException:
        manifest["status"] = "INCOMPLETE"
        raise
    finally:
        try:
            api.request("DELETE", "/worker-credentials/" + credential["id"])
            manifest["credential_revoked"] = True
        except Exception:
            manifest["credential_revoked"] = False
            print("临时 Worker 凭据撤销未确认，请在平台 Agent 页面检查。", flush=True)
        save(manifest_path, manifest)
    return run_dir
