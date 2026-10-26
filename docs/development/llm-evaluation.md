# LLM评分与人工复核

LLM指标使用固定任务证据、指标版本、五档rubric和模型价格版本。执行必须具备持久评分job_id与工作空间身份，不能用临时随机调用ID代替评分作业。当前已实现评分内核和复核接口，后台混合调度在任务16启用。

## 指标配置

```json
{
  "type":"LLM", "criterion_id":"coverage", "required_evidence":["task_instruction","expected","output"], "threshold":0.75,
  "config":{
    "model_profile_id":"<已启用模型UUID>", "price_version_id":"<该模型价格UUID>",
    "prompt_version":"rubric-v1", "max_output_tokens":500, "max_input_bytes":32768,
    "rubric":{"metric":"覆盖任务关键要求的程度", "levels":[
      {"grade":0,"description":"全部关键内容错误或缺失"},
      {"grade":1,"description":"仅少量要求得到满足"},
      {"grade":2,"description":"部分关键要求满足，仍有明显遗漏"},
      {"grade":3,"description":"大部分满足，只有次要遗漏"},
      {"grade":4,"description":"全部准确完整满足"}
    ]}
  }
}
```

可选`config.evidence`明确选择task_instruction/task_input/expected/output/events/artifacts，不能排除声明必需的证据。默认传递四种任务/输出字段及声明需要的事件或附件元数据。`trace`要求COMPLETE，`events`要求至少有上报事件；二者不等价。附件只有元数据，没有文件内容，不能假称已检查附件内事实。

任务说明使用新字段`EvaluationContext.task_instruction`，不覆盖task_input；Java DTO、共享Schema、示例fixture和Python SDK/Runner打包契约同步更新。该服务端证据格式尚未对外发布，Worker任务协议没有新增隐藏参考答案。

## 提示与结果校验

系统消息只包含评分规则、rubric、输出JSON Schema和允许引用。用户消息包含JSON证据及范围：快照ID、完整性、worker_reported来源、主动排除的字段和附件内容未提供标记。任务/输出中的提示指令留在数据段。关键证据缺失或整体提示超过预算时返回INSUFFICIENT_EVIDENCE，不截掉关键内容后默认为完整。

结果必须遵循核心资源`judges/result-schema.json`：只允许status、grade、reason、evidence_refs；SCORED需要整数0～4及至少一项已提供引用，INSUFFICIENT_EVIDENCE需要null grade。重复JSON键、额外字段、越界分档、未提供引用和尾随内容均拒绝。grade除以4映射[0,1]，passed由服务端阈值计算。

模型仍可能误判或引用不充分。引用合法只证明指向本次提供的数据，不证明主张正确；提示词分段也不是对模型绝对服从的保证，需要人工校准。

结构/引用无效时最多追加一次请求，每次经过模型网关单独计费。两次call_id由job_id和尝试序号确定，重启继续查询相同调用，不因处理租约过期再随机扣费。WAITING_QUOTA保持待额度；并发等待、发送中、待对账抛出可恢复等待信号，不把它们当作无效JSON而创建新调用。管理员仅补齐费用、但丢失了供应商响应时，返回评分ERROR，不虚构评分。

## 模板与校准示例

`GET /api/v1/templates/judges`提供内容覆盖、证据支持、工具结果利用三类模板。

`POST /api/v1/templates/judges/{id}/copy`提交model_profile_id和price_version_id，复制为本人可编辑的评测器草稿，绑定已启用模型并按其限制设置默认预算；后续照常发布不可变版本。复制模板不调用模型。

`contracts/fixtures/v1/calibration/`含三份人工示例标注与模拟Judge响应，用于演示归一化和比较分歧。它们是构造样例，不是对真实模型准确率的测量。工具样例包含明确标为上报来源的模拟事件，不证明真实订单业务状态。

## 人工复核

`GET/POST /api/v1/results/{resultId}/reviews`，resultId是单项自动评分的job_id。POST为`{"score":0.75,"reason":"具体判断依据"}`，要求Session+CSRF。每次追加独立复核记录，保存本人身份和时间；原始自动分数、理由、引用、版本和模型账目均保留，不覆盖报告的自动质量结论。

查询限定本人空间，管理员没有私人数据旁路。数据库复合外键绑定结果与工作空间，禁止更新已有复核和自动结果。用户明确删除实验时，历史清理由后续删除流程按空间执行；额度审计单独保留。
