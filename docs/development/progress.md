# 实施进度

实现分支：`codex/platform-implementation`。计划：[20项TDD实施计划](../superpowers/plans/2026-10-25-agent-evaluation-platform.md)。

## 任务1：协议与构建基线

- 已先编写并运行失败测试，再实现协议DTO、严格编解码、评分与运行消息校验。
- 已添加8份JSON Schema和23个共享合法/非法fixture，供Java/Python复用。
- 事件来源不允许用户自报为平台验证；执行输入不包含标准答案、评测代码或模型密钥。
- 已配置Java21构建、Maven wrapper和GitHub Actions验证入口；CI运行结果与本地结果分别记录。
- 后续阶段尚未实现，不将此进度当作可运行完整平台。

## 环境决定

- 在原main之外创建受管理工作区，使用独立实现分支。
- JDK21下载在工作区`.tools/`并校验SHA256，不修改系统Java17。
- Docker Desktop已启动，PostgreSQL17.11镜像可用；没有配置gVisor，自定义代码执行届时应禁用或由合格Runner承接。
- 各依赖与镜像在首次实际使用的任务锁定，避免为未实现模块生成无效锁文件。

详细RED/GREEN命令日志保存在此计划专属的本地`.superpowers/sdd/`目录；随任务完成追加摘要。

任务1本地验证：Maven wrapper verify通过，18项测试，0失败、0错误、0跳过；共享fixture23个。GitHub CI尚待远程执行。
