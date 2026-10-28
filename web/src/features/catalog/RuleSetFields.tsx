import type { ModelChoice } from "../../api/types";
import { NumberField, ValueFields } from "./ValueFields";
import { objectValue } from "./validation";

const comparisons = {
  eq: "等于",
  ne: "不等于",
  gt: "大于",
  gte: "大于或等于",
  lt: "小于",
  lte: "小于或等于",
  between: "在范围内",
};
const operators = {
  ...comparisons,
  exists: "字段存在",
  tool_set: "工具选择",
  tool_arguments: "工具参数",
  count: "工具调用次数",
  duration_ms: "工具耗时",
  causal_before: "工具调用依赖",
  schema: "数据结构检查",
};
const levels = [
  "未满足要求或与事实矛盾",
  "仅满足少量要求",
  "部分满足，仍有关键遗漏",
  "基本满足要求，存在少量不足",
  "完整准确地满足要求",
];

export function defaultDefinition(
  type = "RULE",
  criterion = `criterion_${crypto.randomUUID().slice(0, 8)}`,
): Record<string, unknown> {
  const common = {
    type,
    criterion_id: criterion,
    threshold: type === "RULE" ? 1 : 0.75,
    required_evidence: ["output"],
  };
  if (type === "LLM")
    return {
      ...common,
      config: {
        model_profile_id: "",
        price_version_id: "",
        prompt_version: "rubric-v1",
        max_output_tokens: 500,
        max_input_bytes: 32768,
        rubric: {
          metric: "回答是否准确且完整地完成任务",
          levels: levels.map((description, grade) => ({ grade, description })),
        },
      },
    };
  if (type === "PYTHON")
    return {
      ...common,
      config: {
        code: 'def evaluate(context):\n    # 根据任务、输出和轨迹实现自定义评分\n    return {"status": "INSUFFICIENT_EVIDENCE", "reason": "请实现评分逻辑", "evidence_refs": []}\n',
      },
    };
  return {
    ...common,
    config: {
      operator: "eq",
      selector: "/output",
      expected_selector: "/expected",
    },
  };
}

function PointerField({
  label,
  value,
  onChange,
  roots = true,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  roots?: boolean;
}) {
  const slash = value.indexOf("/", 1);
  const root = slash < 0 ? value : value.slice(0, slash);
  return (
    <div className="field-pair">
      {roots && (
        <label>
          {label} 来源
          <select
            value={root}
            onChange={(event) =>
              onChange(
                event.target.value + (slash < 0 ? "" : value.slice(slash)),
              )
            }
          >
            <option value="/output">Agent 最终输出</option>
            <option value="/expected">任务参考答案</option>
            <option value="/task_input">任务输入</option>
          </select>
        </label>
      )}
      <label>
        {label} 字段路径
        <input
          aria-label={`${label} 字段路径`}
          placeholder="留空检查整体；子字段如 answer 或 order/amount"
          value={
            roots
              ? slash < 0
                ? ""
                : value.slice(slash + 1)
              : value.replace(/^\//, "")
          }
          onChange={(event) =>
            onChange(
              (roots ? root : "") +
                (event.target.value ? "/" + event.target.value : ""),
            )
          }
        />
      </label>
    </div>
  );
}

export function DefinitionFields({
  definition,
  index,
  models,
  onChange,
}: {
  definition: Record<string, unknown>;
  index: number;
  models: ModelChoice[];
  onChange: (value: Record<string, unknown>) => void;
}) {
  const config = objectValue(definition.config);
  const type = String(definition.type);
  const rubric = objectValue(config.rubric);
  const grades = Array.isArray(rubric.levels)
    ? rubric.levels.map(objectValue)
    : [];
  const evidence = Array.isArray(definition.required_evidence)
    ? definition.required_evidence.map(String)
    : [];
  function patch(value: Record<string, unknown>) {
    onChange({ ...definition, config: { ...config, ...value } });
  }
  const op = String(config.operator ?? "eq");
  const tools = ["tool_arguments", "count", "duration_ms"].includes(op);
  const expects = !["tool_set", "causal_before", "exists", "schema"].includes(
    op,
  );
  return (
    <div className="catalog-fields">
      <div className="field-pair">
        <label>
          评分方式 {index}
          <select
            aria-label={`评分方式 ${index}`}
            value={type}
            onChange={(event) =>
              onChange(
                defaultDefinition(
                  event.target.value,
                  String(definition.criterion_id),
                ),
              )
            }
          >
            <option value="RULE">规则检查</option>
            <option value="LLM">LLM 评审（Rubric）</option>
            <option value="PYTHON">自定义 Python</option>
          </select>
        </label>
        <NumberField
          label={`通过分数 ${index}`}
          value={Number(definition.threshold)}
          min={0}
          max={1}
          onChange={(threshold) => onChange({ ...definition, threshold })}
        />
      </div>
      <p className="hint">
        分数统一为 0–1；LLM 的 0–4 级评分会换算后判断是否通过。
      </p>
      {type === "RULE" && (
        <>
          <label>
            检查条件 {index}
            <select
              value={op}
              onChange={(event) => {
                const next = event.target.value;
                let value: Record<string, unknown> = {
                  operator: next,
                  selector: "/output",
                  expected_selector: "/expected",
                };
                if (next === "exists")
                  value = {
                    operator: next,
                    selector: "/output",
                    expected: true,
                  };
                if (next === "schema")
                  value = {
                    operator: next,
                    selector: "/output",
                    schema: { type: "object" },
                  };
                if (next === "tool_set")
                  value = {
                    operator: next,
                    tools: ["query_order"],
                    mode: "contains_all",
                  };
                if (["tool_arguments", "count", "duration_ms"].includes(next))
                  value = {
                    operator: next,
                    tool: "query_order",
                    comparison: "eq",
                    expected: next === "tool_arguments" ? "A" : 1,
                    ...(next === "tool_arguments"
                      ? { selector: "/kwargs/order_id", aggregation: "all" }
                      : next === "duration_ms"
                        ? { aggregation: "sum" }
                        : {}),
                  };
                if (next === "causal_before")
                  value = {
                    operator: next,
                    before: "query_order",
                    after: "generate_answer",
                    relation: "dependency",
                    aggregation: "all",
                  };
                onChange({
                  ...definition,
                  required_evidence:
                    next.startsWith("tool") ||
                    ["count", "duration_ms", "causal_before"].includes(next)
                      ? ["trace"]
                      : ["output"],
                  config: value,
                });
              }}
            >
              {Object.entries(operators).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          {!["tool_set", "causal_before", "count", "duration_ms"].includes(
            op,
          ) && (
            <PointerField
              label={`规则 ${index} 检查对象`}
              roots={op !== "tool_arguments"}
              value={String(config.selector ?? "")}
              onChange={(selector) => patch({ selector })}
            />
          )}
          {tools && (
            <label>
              工具名称 {index}
              <input
                required
                value={String(config.tool ?? "")}
                onChange={(event) => patch({ tool: event.target.value })}
              />
            </label>
          )}
          {tools && (
            <label>
              比较方式 {index}
              <select
                value={String(config.comparison)}
                onChange={(event) =>
                  patch({
                    comparison: event.target.value,
                    ...(event.target.value === "between"
                      ? { expected: { min: 0, max: 10 } }
                      : {
                          expected: tools && op !== "tool_arguments" ? 1 : "",
                        }),
                  })
                }
              >
                {Object.entries(comparisons).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
          )}
          {["tool_arguments", "duration_ms", "causal_before"].includes(op) && (
            <label>
              匹配范围 {index}
              <select
                value={String(config.aggregation)}
                onChange={(event) =>
                  patch({
                    aggregation: event.target.value,
                    ...(event.target.value === "count"
                      ? { match_count: 1 }
                      : {}),
                  })
                }
              >
                <option value="any">任意一次满足</option>
                <option value="all">每一次都满足</option>
                {op === "duration_ms" ? (
                  <>
                    <option value="sum">合计</option>
                    <option value="min">最短</option>
                    <option value="max">最长</option>
                  </>
                ) : (
                  <option value="count">满足指定次数</option>
                )}
              </select>
            </label>
          )}
          {config.aggregation === "count" && (
            <NumberField
              label={`匹配次数 ${index}`}
              min={0}
              max={10000}
              step={1}
              value={Number(config.match_count ?? 1)}
              onChange={(match_count) => patch({ match_count })}
            />
          )}
          {expects && (
            <>
              <label>
                期望值来源 {index}
                <select
                  value={
                    config.expected_selector !== undefined
                      ? "reference"
                      : "fixed"
                  }
                  onChange={(event) => {
                    const next = { ...config };
                    delete next.expected;
                    delete next.expected_selector;
                    if (event.target.value === "reference")
                      next.expected_selector = "/expected";
                    else
                      next.expected = [
                        "gt",
                        "gte",
                        "lt",
                        "lte",
                        "count",
                        "duration_ms",
                      ].includes(op)
                        ? 1
                        : op === "between"
                          ? { min: 0, max: 10 }
                          : "";
                    onChange({ ...definition, config: next });
                  }}
                >
                  <option value="reference">任务参考答案或输入字段</option>
                  <option value="fixed">固定值</option>
                </select>
              </label>
              {config.expected_selector !== undefined ? (
                <PointerField
                  label={`规则 ${index} 期望对象`}
                  value={String(config.expected_selector)}
                  onChange={(expected_selector) => patch({ expected_selector })}
                />
              ) : (
                <ValueFields
                  label={`规则 ${index} 期望值`}
                  value={config.expected}
                  onChange={(expected) => patch({ expected })}
                />
              )}
            </>
          )}
          {op === "exists" && (
            <label>
              存在要求
              <select
                value={String(config.expected)}
                onChange={(event) =>
                  patch({ expected: event.target.value === "true" })
                }
              >
                <option value="true">必须存在</option>
                <option value="false">必须不存在</option>
              </select>
            </label>
          )}
          {op === "schema" && (
            <ValueFields
              label={`规则 ${index} 数据结构`}
              value={config.schema}
              onChange={(schema) => patch({ schema })}
            />
          )}
          {op === "tool_set" && (
            <>
              <ValueFields
                label={`规则 ${index} 工具列表`}
                value={config.tools}
                onChange={(value) => patch({ tools: value })}
              />
              <label>
                工具选择要求
                <select
                  value={String(config.mode)}
                  onChange={(event) => patch({ mode: event.target.value })}
                >
                  <option value="contains_all">必须包含这些工具</option>
                  <option value="exact">只允许这些工具且全部使用</option>
                  <option value="absent">禁止使用这些工具</option>
                </select>
              </label>
            </>
          )}
          {op === "causal_before" && (
            <>
              <label>
                前置工具
                <input
                  required
                  value={String(config.before)}
                  onChange={(event) => patch({ before: event.target.value })}
                />
              </label>
              <label>
                后续工具
                <input
                  required
                  value={String(config.after)}
                  onChange={(event) => patch({ after: event.target.value })}
                />
              </label>
              <label>
                依赖关系
                <select
                  value={String(config.relation)}
                  onChange={(event) => patch({ relation: event.target.value })}
                >
                  <option value="dependency">依赖路径</option>
                  <option value="ancestor">父子调用关系</option>
                </select>
              </label>
            </>
          )}
        </>
      )}
      {type === "LLM" && (
        <>
          <label>
            评审模型 {index}
            <select
              aria-label={`评审模型 ${index}`}
              required
              value={String(config.model_profile_id ?? "")}
              onChange={(event) => {
                const model = models.find(
                  (item) => item.id === event.target.value,
                );
                if (model)
                  patch({
                    model_profile_id: model.id,
                    price_version_id: model.price.id,
                    max_output_tokens: Math.min(500, model.max_output_tokens),
                  });
              }}
            >
              <option value="">选择评审模型</option>
              {models.map((model) => (
                <option key={model.id} value={model.id}>
                  {model.name}
                </option>
              ))}
            </select>
          </label>
          {!models.length && (
            <p className="hint">
              请先在“评审模型”中接入自己的模型，或联系管理员提供平台模型。
            </p>
          )}
          <label>
            评分目标 {index}
            <textarea
              aria-label={`评分目标 ${index}`}
              required
              maxLength={256}
              value={String(rubric.metric ?? "")}
              onChange={(event) =>
                patch({ rubric: { ...rubric, metric: event.target.value } })
              }
            />
          </label>
          <fieldset className="rubric-fields">
            <legend>Rubric · 各等级如何评分</legend>
            {grades.map((level, position) => (
              <label key={Number(level.grade)}>
                规则 {index} · {String(level.grade)} 分标准
                <textarea
                  aria-label={`规则 ${index} · ${String(level.grade)} 分标准`}
                  required
                  maxLength={2048}
                  rows={2}
                  value={String(level.description)}
                  onChange={(event) =>
                    patch({
                      rubric: {
                        ...rubric,
                        levels: grades.map((item, at) =>
                          at === position
                            ? { ...item, description: event.target.value }
                            : item,
                        ),
                      },
                    })
                  }
                />
              </label>
            ))}
          </fieldset>
          <p className="hint">
            平台模型会消耗个人额度；使用自带 API Key 的模型由供应商计费。
          </p>
        </>
      )}
      {type === "PYTHON" && (
        <label>
          Python 评分代码 {index}
          <textarea
            className="code-editor"
            required
            rows={10}
            spellCheck={false}
            value={String(config.code ?? "")}
            onChange={(event) => patch({ code: event.target.value })}
          />
          <span className="hint">
            定义 evaluate(context)。代码仅在合格的隔离 Runner
            执行，未配置节点时无法运行。
          </span>
        </label>
      )}
      <details>
        <summary>所需证据</summary>
        <div className="evidence-options">
          {Object.entries({
            output: "最终输出",
            expected: "参考答案",
            task_input: "任务输入",
            ...(type !== "RULE" ? { task_instruction: "任务要求" } : {}),
            trace: "完整执行轨迹",
            events: "调用事件",
            artifacts: "附件",
          }).map(([key, label]) => (
            <label className="check-label" key={key}>
              <input
                type="checkbox"
                checked={evidence.includes(key)}
                onChange={(event) =>
                  onChange({
                    ...definition,
                    required_evidence: event.target.checked
                      ? [...evidence, key]
                      : evidence.filter((value) => value !== key),
                  })
                }
              />
              {label}
            </label>
          ))}
        </div>
      </details>
    </div>
  );
}

export function RuleSetFields({
  content,
  models,
  onChange,
}: {
  content: Record<string, unknown>;
  models: ModelChoice[];
  onChange: (value: Record<string, unknown>) => void;
}) {
  const entries = Array.isArray(content.entries)
    ? content.entries.map(objectValue)
    : [];
  function edit(index: number, patch: Record<string, unknown>) {
    onChange({
      ...content,
      entries: entries.map((entry, position) =>
        position === index ? { ...entry, ...patch } : entry,
      ),
    });
  }
  return (
    <section className="catalog-fields" aria-label="评分规则列表">
      <div className="section-heading">
        <h3>评分规则</h3>
        <span>{entries.length} 条规则</span>
      </div>
      <p className="hint">
        在这里添加和配置规则，整个规则集一起保存与发布。至少一条规则需设置为“必须通过”。
      </p>
      {entries.map((entry, index) => (
        <fieldset key={index} className="definition-fields">
          <legend>规则 {index + 1}</legend>
          <label>
            规则名称 {index + 1}
            <input
              required
              value={String(
                entry.name ?? objectValue(entry.definition).criterion_id ?? "",
              )}
              onChange={(event) => edit(index, { name: event.target.value })}
            />
          </label>
          <DefinitionFields
            index={index + 1}
            definition={objectValue(entry.definition)}
            models={models}
            onChange={(definition) => {
              const next: Record<string, unknown> = { ...entry, definition };
              delete next.evaluator_version;
              edit(index, next);
            }}
          />
          <div className="field-pair">
            <label>
              评分维度 {index + 1}
              <select
                value={String(entry.dimension ?? "outcome")}
                onChange={(event) =>
                  edit(index, { dimension: event.target.value })
                }
              >
                <option value="outcome">结果质量</option>
                <option value="process">执行过程</option>
              </select>
            </label>
            <NumberField
              label={`规则权重 ${index + 1}`}
              min={0}
              max={1000000}
              value={Number(entry.weight ?? 1)}
              onChange={(weight) => edit(index, { weight })}
            />
          </div>
          <label className="check-label">
            <input
              type="checkbox"
              checked={entry.required_to_pass === true}
              onChange={(event) =>
                edit(index, { required_to_pass: event.target.checked })
              }
            />
            必须通过
          </label>
          <button
            type="button"
            className="danger subtle"
            onClick={() =>
              onChange({
                ...content,
                entries: entries.filter((_, position) => position !== index),
              })
            }
          >
            删除规则 {index + 1}
          </button>
        </fieldset>
      ))}
      <button
        type="button"
        disabled={entries.length >= 100}
        onClick={() =>
          onChange({
            ...content,
            entries: [
              ...entries,
              {
                name: `评分规则 ${entries.length + 1}`,
                definition: defaultDefinition(),
                dimension: "outcome",
                required_to_pass: true,
                weight: 1,
              },
            ],
          })
        }
      >
        新增评分规则
      </button>
    </section>
  );
}
