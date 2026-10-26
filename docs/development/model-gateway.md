# 平台评审模型网关

平台Judge统一经过网关，用户Agent自己的模型费用不计入此账本。当前已实现网关和管理API，LLM评分器与混合调度在后续任务连接。

## 配置与价格

管理员创建模型配置时固定endpoint、模型标识、输入Token上界、输出/字节/并发限制和上界依据，同时创建不可变价格版本。变更模型、价格或计量依据应创建新配置，历史调用继续引用原版本。

`credits = ceil((input_tokens * input_rate + output_tokens * output_rate) / denominator)`

使用BigInteger中间运算，结果超出long拒绝。首版按管理员依据供应商文档核实的强制输入Token上限，加本次max输出进行保守预留，不用字符数冒充精确tokenizer；未知安全上界的供应商不可配置。预留通常高于实际消费，可信usage结算后释放差额。不能仅从“OpenAI-compatible”名称推导安全Token上界。

调用保存profile/价格版本、请求摘要和内容、供应商模型标识、响应ID、结束原因、脱敏响应和用量。`reported_credits`是已报告用量对应的费用，`credits`是已结算费用；待对账时二者可能不同。

## 发送与恢复

先获取数据库持久并发槽，再预留额度：全局8、每空间2、每模型1到8。待对账请求继续占槽，避免未知在途请求无限叠加。余额不足返回WAITING_QUOTA且不发送；槽不足返回WAITING_CAPACITY。

```text
WAITING_QUOTA / WAITING_CAPACITY -> RESERVED -> SEND_INTENT -> SUCCEEDED
                                      |             |
                               FAILED_UNSENT   PENDING_RECONCILIATION -> RECONCILED
```

预留与调用记录同事务提交。准备HTTP失败且未记录发送意图，可释放预留；SEND_INTENT先落库，再发起一次非流式HTTP。断线、超时、坏响应、缺可信usage都保留预留，不自动重发。相同call_id并发或重试返回既有状态，供应商Idempotency-Key只是额外保护。

后台每5秒检查90秒分派期限：过期RESERVED释放并标记未发送失败，过期SEND_INTENT进入待对账。供应商响应后若结算事务失败，也保留发送意图和预留，重启不重发。评分JSON无效仍结算已发生模型费用；取消实验不退已发生费用。

用量或费用突破上界时停用模型配置、记录异常和报告用量，保留预留。管理员确认实际用量、原因和幂等键后结算；超额需补充预留，可用余额不足时整个对账回滚。补额ADJUST账目保存操作者并关联对账和call_id。相同确认不重复消费，改变参数或跨调用复用键冲突。配置不自动重新启用，应核实依据并创建新配置。

## 密钥与部署

部署注入`MODEL_ENCRYPTION_KEY`（Base64编码的32字节随机密钥）及`MODEL_ENDPOINT_ORIGINS`（逗号分隔精确origin），默认不配置。AES-256-GCM使用随机nonce和profile UUID作为AAD，数据库只存密文，前端不返回明文或密文。数据库备份需配套保护部署密钥。

endpoint仅管理员控制，必须命中部署白名单；禁止URL凭证、query、fragment和重定向。生产使用TLS和出口网络策略。用户只选择profile_id，不能覆盖baseURL。

HTTP响应上限1MiB，总期限60秒，无流式和业务工具。已知供应商密钥在响应字段名和值中脱敏；无法安全解析的供应商响应只保存捕获标记和SHA256。管理员待对账列表只查询操作元数据，不加载私人提示词/生成内容。

## API

- `GET /api/v1/models`：用户查看启用模型、限制和价格，不返回endpoint或密钥。
- `GET/POST /api/v1/admin/models`：管理员查看/创建配置，字段为ModelProfileCommand的snake_case。
- `POST /api/v1/admin/models/{id}/disable`：`reason`。
- `POST /api/v1/admin/models/{id}/test`：固定`call_id`；测试也经过网关并使用管理员自己的钱包。
- `GET /api/v1/admin/model-calls?limit=50`：待对账列表，最多100条。
- `POST /api/v1/admin/model-calls/{callId}/reconciliation`：`input_tokens`、`output_tokens`、`reason`、`idempotency_key`。

所有修改需要Session和CSRF。价格/余额使用long，前端需保留整数精度。

兼容字段依据[官方Chat Completions接口](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)：`max_completion_tokens`包含可见输出和推理Token，usage使用`prompt_tokens`、`completion_tokens`。当前固定JSON object响应、temperature=0、n=1；需选择支持这些参数和明确计量的模型。测试采用本地HTTP供应商和真实PostgreSQL，未进行真实付费供应商验证。
