"""Local execution and an authenticated, one-command platform demonstration."""
import argparse
import getpass
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from threading import Event

from agent_eval import TaskContext

from .agent import make_agent
from .demo import download_report, run_demo
from .platform import Platform, PlatformError


def main() -> int:
    parser = argparse.ArgumentParser(description="VeyraEval 独立 Python Agent 示例")
    commands = parser.add_subparsers(dest="command", required=True)
    local = commands.add_parser("local", help="不连接平台，测试 Agent 的业务输出")
    local.add_argument("--variant", choices=["correct", "buggy"], default="correct")
    local.add_argument("--order", default="A")
    for name in ("demo", "report"):
        command = commands.add_parser(name, help="创建并执行对比评测" if name == "demo" else "只读获取既有实验报告")
        command.add_argument("--url", default="https://localhost:8443")
        command.add_argument("--email", required=True, help="你的平台登录邮箱")
        command.add_argument("--ca", type=Path, help="开发证书路径；不关闭 TLS 校验")
        command.add_argument("--timeout", type=float, default=180)
        command.add_argument("--output", type=Path, default=Path(".local/runs"))
        if name == "demo":
            judge = command.add_mutually_exclusive_group()
            judge.add_argument("--judge", action="store_true", help="登录后选择评审模型，运行规则与LLM混合评测")
            judge.add_argument("--judge-model", help="使用指定的本人可用模型UUID执行LLM评审")
        if name == "report":
            command.add_argument("experiment", help="已有实验 UUID；不会创建或重新执行任务")
    args = parser.parse_args()
    if args.command == "local":
        context = TaskContext("local", "查询订单", {"order_id": args.order},
                              datetime.now(timezone.utc) + timedelta(seconds=30), Event())
        print(json.dumps(make_agent(args.variant)(context).output, ensure_ascii=False))
        return 0
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    if not sys.stdin.isatty():
        parser.error("请在本机交互终端输入密码，密码不要放在命令参数或聊天中。")
    api = Platform(args.url, args.ca)
    try:
        password = getpass.getpass("平台登录密码（输入不回显，不保存）：")
        try:
            api.login(args.email, password)
        finally:
            del password
        if args.command == "demo":
            model_id = args.judge_model
            if args.judge:
                models = api.request("GET", "/models")
                if not models:
                    raise PlatformError("请先在平台的评审模型页面接入模型，或联系管理员配置平台模型。")
                for index, model in enumerate(models, 1):
                    billing = "平台额度" if model["billing_mode"] == "PLATFORM" else "自带API Key，由供应商计费"
                    print(f"{index}. {model['name']}（{billing}）")
                choice = 1 if len(models) == 1 else int(input("选择评审模型序号："))
                if not 1 <= choice <= len(models):
                    raise ValueError("无效的模型序号")
                model_id = models[choice - 1]["id"]
            result = run_demo(api, args.ca, args.output, args.timeout, model_id)
        else:
            result = args.output / ("report-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
            result.mkdir(parents=True, mode=0o700)
            download_report(api, args.experiment, result, args.timeout)
        print(f"报告和轨迹已保存：{result.resolve()}")
        return 0
    except (Exception, KeyboardInterrupt) as error:
        # No traceback/HTTP body: these can contain private account data.
        print(f"演示未完成（{type(error).__name__}）。请检查登录、证书和平台状态。"
              "已有资源请参考 .local/runs 中的 manifest.json；不要盲目重复执行。", file=sys.stderr)
        if isinstance(error, (PlatformError, TimeoutError)):
            print(str(error), file=sys.stderr)
        return 1
    finally:
        try:
            api.close()
        except Exception:
            api.client.close()


if __name__ == "__main__":
    raise SystemExit(main())
