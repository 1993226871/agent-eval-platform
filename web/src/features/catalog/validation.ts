import { z } from "zod";
import type { ResourceKind } from "../../api/types";

const entry = z
  .object({
    evaluator_version: z.uuid().optional(),
    definition: z.record(z.string(), z.unknown()).optional(),
    name: z.string().optional(),
    dimension: z.enum(["outcome", "process"]),
    required_to_pass: z.boolean(),
    weight: z.number().finite().min(0).max(1000000),
  })
  .passthrough();
export function parseContent(
  source: string,
  kind: ResourceKind,
): Record<string, unknown> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(source);
  } catch {
    throw new Error("内容不是有效 JSON，请检查括号、引号和逗号。");
  }
  const object = z.record(z.string(), z.unknown()).parse(parsed);
  if (kind === "DATASET") {
    const cases = z
      .array(
        z
          .object({
            instruction: z.string().min(1, "案例需要任务说明。"),
            input: z.unknown(),
          })
          .passthrough(),
      )
      .min(1, "至少添加一个案例。")
      .max(1000)
      .parse(object.cases);
    if (cases.some((task) => !Object.hasOwn(task, "input")))
      throw new Error("每个案例都需要 input 字段。");
  } else if (kind === "SUITE") {
    const entries = z
      .array(entry)
      .min(1, "至少添加一条评分规则。")
      .max(100)
      .parse(object.entries);
    const criteria = new Set<string>();
    for (const item of entries) {
      if (!item.definition && !item.evaluator_version)
        throw new Error("请配置每一条评分规则。");
      if (item.definition) {
        parseContent(pretty(item.definition), "EVALUATOR");
        const id = String(item.definition.criterion_id);
        if (criteria.has(id))
          throw new Error("评分规则重复，请删除重复规则或新增独立规则。");
        criteria.add(id);
      }
    }
    for (const dimension of new Set(entries.map((item) => item.dimension)))
      if (
        entries
          .filter((item) => item.dimension === dimension)
          .reduce((sum, item) => sum + item.weight, 0) <= 0
      )
        throw new Error("每个评分维度至少需要一条权重大于零的规则。");
    if (!entries.some((item) => item.required_to_pass))
      throw new Error("至少设置一个必须通过的评分规则。");
  } else {
    const definition = z
      .object({
        type: z.enum(["RULE", "LLM", "PYTHON"]),
        criterion_id: z
          .string()
          .regex(
            /^[A-Za-z0-9_.-]{1,64}$/,
            "指标标识使用字母、数字、下划线、点或短横线。",
          ),
        threshold: z.number().finite().min(0).max(1),
        required_evidence: z.array(z.string()),
        config: z.record(z.string(), z.unknown()),
      })
      .passthrough()
      .parse(object);
    if (
      definition.type === "PYTHON" &&
      (typeof definition.config.code !== "string" ||
        !definition.config.code.trim())
    )
      throw new Error("请填写 Python evaluate(context) 函数。");
  }
  return object;
}
export function pretty(value: unknown) {
  return JSON.stringify(value, null, 2) ?? "null";
}
export function objectValue(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}
