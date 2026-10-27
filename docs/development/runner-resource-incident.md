# Runner 进程预算导致的运行时退出

2026-10-27追踪 main 合并后的 Linux 验收失败。原来的26项测试和若干重复检查可以通过，但重复过程仍可能出现`NO_RESULT`。不能用一次成功重跑证明问题已解决。

## 证据与根因

- [main失败运行40347496284](https://github.com/1993226871/veyraeval/actions/runs/40347496284)：26项通过，额外综合隔离检查第5次失败。
- [诊断运行40380839244](https://github.com/1993226871/veyraeval/actions/runs/40380839244)：容器退出码2、`OOMKilled=false`，Docker读取返回`WaitPID EOF`；受限panic分类记录明确出现`failed to create new OS thread (have 18 already; errno=11)`和`fatal error: newosproc`。
- 原实现把用户32进程预算直接设置成整个容器宿主cgroup的PID上限。该上限也包含gVisor Sentry线程和地址空间stub，进程压力可能先耗尽运行时自身资源。结果协议保持不变。

背景参阅[gVisor资源模型](https://gvisor.dev/docs/architecture_guide/resources/)：沙箱任务不与宿主进程一一对应，Sentry线程和stub也受宿主cgroup限制。

## 修复与验证标准

修复提交`257e7d6`：为用户real UID10001设置不可提升的`RLIMIT_NPROC`软硬上限（默认32，可请求4..32）；单个沙箱的宿主cgroup另外有256 PID上限。该用户任务预算包含进程、线程、guest入口及结果读取，不表示32个额外子进程。非root、drop ALL、no-new-privileges、整个沙箱256 MiB内存、1 CPU及Runner并发边界继续生效。

- 原触发场景先在真实runsc节点复现；控制命令测试在新策略实现前失败，修复后20项控制测试通过。
- 综合隔离检查读取真实guest软硬限制，断言无法提高硬上限；创建轻量子进程直到`EAGAIN`，确认可创建20..31个子进程并回收，然后必须正常返回SCORED结果。内存耗尽另有独立测试，不能把任意崩溃当作PID隔离通过。
- 每次Linux验收额外连续运行100次综合隔离检查；还必须通过26项Runner、8项完整Compose端到端场景及普通CI。

升级不会重放已缓存的失败或未知作业。需要重新获得评分时，用户显式创建重评轮次，保留原有副作用和幂等语义。

## 资源与测试边界

- 用户限制和运行时预算分开：保持用户4..32任务硬限制，同时给Sentry留出有界开销。代价是部署方要按每沙箱256宿主PID预算及并发做容量规划；内存与CPU约束仍作用于整个沙箱。
- 使用轻量子进程测试PID限额：排除启动大量Python解释器引入的内存压力，同时强化EAGAIN和正常返回断言。代价是解释器内存压力由单独内存测试覆盖。
- 保留100次真实检查和仅合成CI节点上的有界panic分类：十次抽样曾漏掉复发。代价是节点资格验收更长，生产节点仍须自行验收。

[Linux验收40381313430](https://github.com/1993226871/veyraeval/actions/runs/40381313430)在修复提交`257e7d66bb3c15b7ff8c63d4765bd8c40648aa25`上通过26项Runner、连续100次综合隔离检查、176项Java及8个完整Compose E2E。

[普通CI40383234543](https://github.com/1993226871/veyraeval/actions/runs/40383234543)在仅增加CI配置的`2194e59`上全部通过：前端18项/类型/构建/格式、Python SDK48项和wheel协议校验、Runner控制20项、Java176项及两个真实浏览器场景。普通Java/browser作业固定Ubuntu24.04，使用VM预装Chrome并输出版本，仍运行原有浏览器测试；替换了异常停留超过十分钟（历史正常21秒）的依赖下载步骤，并增加25分钟作业时限。原停滞运行40381313532明确取消，由上述完整成功运行替代。

这些记录针对不可变运行代码与具体CI结果；main同步后仍会触发相同的验收，不能用旧main的失败记录或之前偶然成功的重跑代替。
