# 规则评测器 v1

规则仅对冻结的 `EvaluationContext` 求值，不调用网络、SQL、shell或任意表达式引擎。`Evaluator.evaluate(definition, context)` 返回共享 `CriterionResult`。规则断言分数为0或1，`passed`由固定发布版本中的threshold计算；非评分状态的score/passed均为null。规则结果由强类型协议模型构造，并验证引用确实属于当前快照。

## 结果和证据

- 已观测到的数值/结构不符合要求：SCORED，score=0。
- 字段没有提供、工具没有匹配、调用结果被截断/脱敏而无法建立断言：INSUFFICIENT_EVIDENCE。
- 配置不合法、数值或求值复杂度超限：ERROR，不作为Agent质量失败。
- 显式要求不存在时，只有相应证据完整才能根据“没看到”判断。已观测到禁止工具则可直接证明违反要求。

最终输出可独立于轨迹评测：即使轨迹为NONE，完整提供的output仍可用于金额或Schema断言。存在性规则的缺失判断较保守；要检查结构化输出必须包含字段，可使用Schema的required。

证据引用支持 `output`、`expected`、`task_input`、`event:<uuid>`、`artifact:<uuid>` 和 `snapshot:<uuid>`，不能引用其他执行。每个生成结果最多引用128个对象。用户上报的轨迹仍然是worker_reported，规则匹配不把它升级为独立环境证明。

## 配置

| operator | 主要配置 | 行为 |
| --- | --- | --- |
| eq / ne | selector；expected或expected_selector二选一 | 结构比较；对象键顺序无关，数组顺序有关，1与1.0数值等价 |
| gt / gte / lt / lte | 同上，期望值为有限数字 | 数值比较；不把数字字符串隐式转成数字 |
| between | selector；expected为包含min/max的对象 | 包含上下界的范围比较 |
| exists | selector；expected为true/false | 缺失默认未知，明确不存在断言需要完整证据 |
| schema | selector；schema | 校验下述安全子集 |
| tool_set | tools数组；mode为contains_all/exact/absent | 检查实际调用的工具名集合，重复调用不重复计入集合 |
| tool_arguments | tool；selector；comparison；期望值；aggregation | 对匹配工具的输入参数逐次比较 |
| count | tool；comparison；期望计数 | 按不同span_id计次；需要完整轨迹。空匹配默认未知，显式目标0允许作不存在断言 |
| duration_ms | tool；comparison；期望值；aggregation | 读取终态事件中SDK记录的本地duration_ms，不使用网络到达间隔 |
| causal_before | before/after工具名；relation；aggregation | 按明确因果关系检查，不能用sequence或occurred_at推断并发调用顺序 |

通用selector是JSON Pointer，根仅限 `/output`、`/expected`、`/task_input`；工具参数selector相对于started事件的payload.input。Pointer长度最多512、路径最多32段。阈值是评测器发布内容的字段，不从Agent输出接受覆盖。

```json
{"operator":"eq","selector":"/output/amount","expected_selector":"/expected/amount"}
```

tool_arguments和causal_before必须显式选择aggregation：any、all或count；count还需match_count。any观测到一次满足即可成立；all观测到一次违反即可失败。其余情况需要完整的匹配范围，避免把缺失的重试当作成功。工具参数的选中事件被脱敏/截断时采取保守未知处理。

duration_ms支持any/all/sum/min/max；sum/min/max要求完整证据及所有匹配调用都有终态耗时。计数目标必须为0到10000的整数。支持的comparison为eq/ne/gt/gte/lt/lte/between。

因果关系分两种：ancestor要求before是after的祖先Span，只证明开始嵌套关系；dependency要求after的started.payload.input.depends_on_span_id显式指向已经有终态记录的前序Span，可沿该字段追溯。显式链接还要与同次执行的事件序号一致：前序调用必须先开始，依赖模式还要求它在后续调用开始前结束。序号仅用于检查声明的一致性，不能单独推断因果关系。没有链接或声明矛盾时返回证据不足；明确反向链接证明顺序违反。因果链最多检查128层，总追溯预算4096步，复杂度超限返回ERROR。SDK不会自动补造这些业务依赖信息。

## Schema与计算上限

Schema首版支持type、properties、required、additionalProperties布尔值、items、minItems/maxItems、minLength/maxLength、minimum/maximum、enum、const。不支持$ref、正则、format插件或外部加载。需要更复杂语义时使用后续的自定义Python评测器；平台不会把不支持的Schema静默当成通过。

Schema定义最多8层、256节点、每个properties最多64项；求值工作预算4096步。普通结构相等比较限制深度32、节点4096；期望字面量限制深度16、节点1024。数字最多128位精度、scale绝对值不超过128。配置序列化文本最多65536个Java字符，未知操作符/字段在证据检查前拒绝。规则引擎不读取或要求隐藏思维过程。
