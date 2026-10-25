# Worker v1 控制协议

这些接口已实现并通过数据库和HTTP集成测试。Java/Python SDK尚未发布；本文件描述SDK将消费的接口。

## 凭证与注册

浏览器用户通过Session及CSRF调用`POST /api/v1/worker-credentials`，body为`{"agent_ids":["已创建的Agent UUID"]}`。返回`id`和仅展示一次的`secret`。服务端仅存secret的SHA256摘要。通过`DELETE /api/v1/worker-credentials/{id}`撤销。

Worker控制请求使用`Authorization: Bearer <secret>`，不能使用浏览器Session替代。凭证只允许指定Agent和工作空间。

`POST /api/v1/workers/register`接收：

```json
{
  "agent_id": "00000000-0000-0000-0000-000000000001",
  "agent_version": "v1",
  "instance_id": "my-worker-1",
  "protocol_version": "1.0",
  "sdk_language": "PYTHON",
  "sdk_version": "0.1.0",
  "max_concurrency": 1
}
```

返回`worker_id`、`heartbeat_seconds=15`、`lease_seconds=60`。同凭证、同instance_id重新登记返回同一个Worker。存在活跃执行时不能更换其Agent绑定、版本或并发配置。

## 领取与执行

`POST /api/v1/workers/{worker_id}/claim`：

```json
{
  "worker_id": "00000000-0000-0000-0000-000000000002",
  "agent_id": "00000000-0000-0000-0000-000000000001",
  "agent_version": "v1",
  "max_wait_seconds": 20
}
```

无任务时最多等待20秒后返回204；有任务时200返回`trial_id`、`trace_id`、`attempt_token`、`lease_expires_at`和`task`。task仅含`trial_id/instruction/input/deadline`，不含隐藏expected、评测器代码或平台模型凭证。

每个运行请求同时携带Bearer凭证和`X-Attempt-Token`：

| 请求 | 行为 |
| --- | --- |
| POST /api/v1/trials/{id}/started | 开始确认，重复确认幂等；204 |
| POST /api/v1/trials/{id}/heartbeat | 返回cancel_requested和lease_expires_at |
| POST /api/v1/trials/{id}/complete | 提交执行终态和结果，完全相同的重复提交幂等；204 |

complete body由共享`completion-request.json`约束。SDK必须先确认started再调用Agent；完成前flush事件，提交final_sequence/event_count/dropped_count/artifact_ids。

## 轨迹上传与查询

`POST /api/v1/trials/{id}/events:batch`使用相同Bearer及尝试令牌，body为`{"events":[共享trace-event模型]}`。批次最多100事件、实际请求不超过1MiB、单事件payload不超过64KiB。响应`acknowledged_event_ids`只在数据库事务提交成功后确认。

同event_id同内容可重试；不同内容或重复sequence冲突返回409，整批回滚。服务端来源固定为worker_reported，客户端不能声明成独立验证。输入/输出及异常以原始事件保存，调用树是可重建投影。

浏览器通过`GET /api/v1/traces/{trial_id}?limit=50&cursor=...`分页查询。游标绑定当前空间和Trial，并固定首个分页的上界；刷新查询可读取后续新节点。Worker凭证不能替代浏览器访问其他空间。

终态后默认30秒内可补收迟到证据，超出窗口拒绝。补收不会改变执行结果；附件与证据快照见[证据协议](evidence.md)，完整混合重评在后续任务中实现。

## 失联和取消

- 初始租约60秒，每15秒心跳；续租不能超过任务或实验期限。
- 失联且未到任务期限进入EXECUTION_UNKNOWN；执行期限已到进入TIMED_OUT。
- 默认不重新领取未知执行。重跑必须创建新Trial，防止有副作用的操作重复执行。
- 平台取消后心跳通知SDK停止，晚到成功不能把取消改成成功。
- 旧或错误attempt_token返回409；凭证失效返回401；越权返回404。

SUCCEEDED表示Agent执行正常返回，不代表任务质量通过；质量评测是后续独立流程。
