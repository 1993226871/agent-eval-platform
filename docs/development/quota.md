# 累计评审额度

每个工作空间唯一钱包，初始0，不按日/月重置。管理员通过带原因和UUID幂等键的调整发放或调减累计credits。迁移回填已有工作空间，新空间由数据库触发器在创建事务内初始化；初始化失败时用户/空间创建一起回滚。

`available = granted - consumed - reserved`。全部是非负long整数，`consumed + reserved <= granted`；检查使用减法避免求和溢出，新增额度使用精确加法。管理员不能将额度调低到已消费加已预留以下。超出long范围拒绝。

## HTTP

- `GET /api/v1/quota`：登录用户自己的累计发放/消费/预留/可用额度。
- `GET /api/v1/quota/ledger?limit=50`：本人最近账目，最多100条，最新优先。完整历史不删除，分页扩展在管理界面阶段完成。
- `POST /api/v1/admin/workspaces/{workspaceId}/quota/adjustments`：管理员Session+CSRF；JSON为`credits`（非零有符号整数）、`reason`、`idempotency_key`。同键同参数只调整一次，同键改参数409。普通用户不能发放额度。

用户查询不接受外部workspace归属参数，管理员对私人业务资源的访问规则不因发放额度而改变。HTTP返回long数值，前端接入须保留其整数精度。

## 内部状态与一致性

模型请求使用全局唯一call_id预留上界；同call_id不同空间/额度冲突。钱包行锁串行化并发扣减，预留状态及不可变账目在同一事务更新，账目写入失败时余额一起回滚。

```text
RESERVED -> SEND_INTENT -> RECONCILIATION
    |             |             |
    +---------- SETTLED <-------+
    |
    +-> RELEASED（仅明确未发送）
```

SETTLE只接受0到预留上界的实际费用；消费实际值并释放剩余预留。相同实际值重复结算不再扣费，不同实际值冲突。发送意图和待对账状态不能使用releaseUnsent释放；供应商是否受理未知时保留预留，不因本地超时、取消或进程重启推断免费。模型网关将在下一任务连接供应商调用和管理员对账。

账目含RESERVE、SETTLE、RELEASE、ADJUST及发送/对账状态标记，每条记录保存增量、原因、管理员身份（调整时）和时间。数据库禁止更新或删除账目；实验删除不能清除此审计记录。

已验证：PostgreSQL上20并发reserve100争用grant1000恰好10次成功；settle60释放40且重复结算一次消费；发送未知不能释放、调减与溢出拒绝、租户查询隔离、账目注入失败余额回滚、已有/新空间钱包初始化，以及管理员HTTP鉴权、CSRF与幂等。
