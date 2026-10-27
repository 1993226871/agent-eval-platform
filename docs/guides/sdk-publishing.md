# SDK 发布与依赖引入

可以发布。Python 使用 PyPI（用户通过 pip 安装），Java 使用 Maven Central（用户通过 Maven/Gradle 引入）。**本仓库目前是发布准备状态，没有执行注册表上传，也没有声称下面坐标已经可安装。** 项目仓库、分支推送和 SDK 公开发布是不同操作，均应按各自授权执行。

## 当前名称与真正的发布前提

| 项目 | 当前源码标识 | 发布前必须确认 |
| --- | --- | --- |
| Python 分发名 | `agent-eval-sdk`，版本 `0.1.0` | PyPI 名称是否可用、是否属于你的账号，以及是否使用该名称。Python 导入名仍是 `agent_eval`。 |
| Java SDK | `io.agenteval:agent-eval-sdk:0.1.0` | `io.agenteval` namespace 的发布权。不能仅凭代码中的 groupId 声称拥有该域名。 |
| Java 协议 | `io.agenteval:protocol:0.1.0` | 与 SDK 一起提供，避免消费者找不到共享 DTO/Jackson 协议依赖。 |
| Java 父 POM | `io.agenteval:agent-eval-platform:0.1.0`，类型 `pom` | 一并发布以解析版本/BOM/许可证等继承元数据。无需发布 API、Worker、core 或 infrastructure jar。 |

若没有 `agenteval.io` 的 namespace 权限，可在账号验证后考虑 `io.github.1993226871`，但这不是已批准或已取得的坐标。更换 groupId 必须同步父 POM、模块父引用、内部依赖和文档；Java 包名 `io.agenteval.*` 不必随 Maven groupId 一起改变。

PyPI 与 Central 的公开 SDK 包会暴露其代码；私有 GitHub 仓库不会让公开注册表中的源码包变成私有。发布前也应确认公开范围、账号适用规则及上游依赖许可证。

## 已准备的构建能力

- Python `pyproject.toml` 包含 README、仓库 URL、SPDX `Apache-2.0` 和 SDK 自带 LICENSE；wheel 包含10份规范 schema 副本。
- Maven 父 POM包含项目、许可证、维护者及 SCM 元数据；SDK/protocol 二进制 jar附带 LICENSE。
- `sdk-distribution` profile生成 SDK/protocol 的 binary、sources 和 Javadoc jar。
- `.github/workflows/sdk-distributions.yml` 仅在手工触发时构建、检查并保存产物。没有注册表写凭证、没有 OIDC 发布权限、没有上传注册表的命令。

本地准备：

```bash
./mvnw -P sdk-distribution -pl sdks/java -am verify
python scripts/verify_java_sdk_distribution.py
python -m pip install build==1.3.0 twine==6.2.0
python -m build sdks/python
python -m twine check sdks/python/dist/*
python scripts/verify_sdk_wheel.py sdks/python/dist/agent_eval_sdk-0.1.0-py3-none-any.whl
```

Python 默认先构建 sdist，再从 sdist构建 wheel，验证源码分发包本身能独立打包。Java 不能只把 SDK 的单个 jar传上去：缺失 protocol 或父 POM 时，下游依赖解析会失败。

## Python：真正发布时的步骤

1. 确认公开名称、版本和 PyPI 所有权；创建/配置对应项目。若已有同名其他维护者项目，不得直接使用该名字。
2. 建议先在 TestPyPI 验证包元数据、安装及导入。源码中的 `AGENT_EVAL_API_KEY` 指平台接入密钥，与 PyPI 发布账号无关。
3. 配置 PyPI Trusted Publisher，绑定准确的 GitHub owner、repo、发布 workflow 和受保护 environment；给该 environment 配置人工批准。
4. 后续单独增加发布作业：只下载本次构建的 SDK 产物，使用 `pypa/gh-action-pypi-publish`，将 OIDC `id-token: write` 限定在发布作业。该作业尚未加入本仓库。
5. 你审核具体产物/版本并明确批准后才公开发布，再从干净环境按注册表依赖安装验证。已发布的同版本不能随意覆盖。

发布成功后，用户才可执行（名称仍需以最终批准值为准）：

```bash
python -m pip install agent-eval-sdk==0.1.0
```

```python
from agent_eval import AgentResult, EvaluationWorker, observe
```

## Java：真正发布时的步骤

1. 在 Sonatype Central Portal 注册并验证 namespace，确认最终 groupId、artifactId 和版本。
2. 确认源码、Javadoc、POM 元数据、许可证和共享依赖齐全；给发布文件生成所需的签名与校验和。
3. 按 Central 要求创建发布 Token 和 GPG 签名配置，存放在专用账号/受保护 environment 中；不得写入仓库或本文件。
4. 后续在显式发布 profile中配置官方 `central-publishing-maven-plugin` 或按 Portal手工上传 bundle；只选择父 POM、protocol、SDK，不从根 reactor误发布服务端模块。当前 `sdk-distribution` 只打包，不配置 deploy或外部上传。
5. 你批准具体 bundle后才上传/发布；在干净的外部消费者项目中验证 Maven Central 可以解析完整依赖链。

真正发布成功后，用户可使用最终坐标，例如当前候选：

```xml
<dependency>
  <groupId>io.agenteval</groupId>
  <artifactId>agent-eval-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

```kotlin
implementation("io.agenteval:agent-eval-sdk:0.1.0")
```

普通用户无需下载仓库、执行 `mvn install` 或配置平台服务端模块。若尚未公开发布，继续使用[Java源码接入](java-sdk.md)或[Python源码接入](python-sdk.md)，不要把候选坐标写成已上线依赖。

## 官方参考

- [PyPI发布说明](https://docs.pypi.org/publishing/publishing-to-pypi/)、[Trusted Publishing](https://docs.pypi.org/trusted-publishers/using-a-publisher/)。
- [Sonatype namespace验证](https://central.sonatype.org/register/namespace/)、[发布必备元数据/签名/源码要求](https://central.sonatype.org/publish/requirements/)、[官方Maven发布插件](https://central.sonatype.org/publish/publish-portal-maven/)。
