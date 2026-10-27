# Python Agent 完整评测示例

从正式 PyPI 安装 SDK，用同一个任务集比较正确版和故意算错金额的 Agent，查看最终答案、工具调用、中间结果及平台评分。

完整操作、流程图、评分解释和一次实际运行的匿名结果见 [使用教程](../../docs/guides/python-evaluation-walkthrough.md)。本目录可以复制到自己的项目目录，独立运行，不依赖平台 Java/Python 源码。

## 安装

需要 Python 3.11+ 和已启动的 VeyraEval 平台。进入本目录后：

```bash
python -m venv .venv
# macOS / Linux
source .venv/bin/activate
# Windows PowerShell 则执行：.\.venv\Scripts\Activate.ps1
python -m pip install -e .
python -m unittest discover -s tests -v
```

不想激活虚拟环境时，Windows 可用 `.\.venv\Scripts\python.exe` 替代后续命令中的 `python`。

## 运行

先检查本地业务输出，不连接平台：

```bash
python -m order_agent.cli local
python -m order_agent.cli local --variant buggy
```

连接平台，使用**自己的登录邮箱**；密码由终端提示输入，不放在命令中：

```bash
python -m order_agent.cli demo --url https://your-platform.example --email user@example.com
```

本机开发 HTTPS 需要额外指定公开证书。若当前目录仍在仓库内，使用：

```bash
python -m order_agent.cli demo --url https://localhost:8443 --email user@example.com --ca ../../.tools/local/tls/server.crt
```

证书实际路径由部署者提供。复制本目录单独使用时，请修改 `--ca` 路径。公共 CA 证书的平台不需要这个参数；不要关闭 TLS 校验。

程序自动创建私人任务集、规则、套件和两个 Agent，启动 SDK Worker，执行两个实验，输出本人可访问的报告链接，并下载证据至 `.local/runs/本轮ID/`。每次运行创建新资源；临时 Worker 凭据运行结束会撤销，其他示例资源保留。若撤销失败会提示手动检查。

默认最多等待每个实验 180 秒，可用 `--timeout 300` 调整。超时不代表 Agent 得 0 分，也不会自动重新执行任务。

只下载既有实验报告，不重新运行：

```bash
python -m order_agent.cli report YOUR_EXPERIMENT_UUID --url https://your-platform.example --email user@example.com
```

开发 HTTPS 同样需要 `--ca`。`report` 命令的实验 ID 必须来自当前账号的工作空间。

## 文件职责

```text
python-evaluation-demo/
├── README.md                 # 安装、运行和教程入口
├── pyproject.toml            # 项目元数据、PyPI SDK 依赖和 order-demo 命令
├── .gitignore               # 忽略环境、缓存、凭据配置和本地证据
├── src/order_agent/
│   ├── __init__.py          # Python 包入口
│   ├── agent.py             # Agent 业务逻辑和 query_order 工具打点
│   ├── catalog.py           # 自动初始化使用的任务、标准答案和评分配置
│   ├── platform.py          # Session/CSRF 登录、目录发布等 API 客户端
│   ├── demo.py              # 创建实验、管理 SDK Worker、下载证据、撤销凭据
│   └── cli.py               # local / demo / report 命令与终端密码输入
└── tests/
    ├── test_agent.py        # 正误版本、取消、数据隔离与评分参考源
    ├── test_platform.py     # CSRF/Session、地址约束与错误信息脱敏
    └── test_evidence.py     # SDK 工具轨迹和等待完整评分的行为测试
```

这里是确定性的模拟订单 Agent，没有自然语言推理和模型调用，不需要模型额度或 Python Runner。密码不保存；Worker 凭据 secret 仅保存在进程内存。`.local/` 包含本人的报告和 SDK 持久化队列，请保留访问限制，不要提交到 Git。
