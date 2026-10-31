import type { Schema } from "../../api/types";
type Trial = Schema<"TrialReport">;

export function diagnoseTrial(trial: Trial): {
  title: string;
  reasons: string[];
} {
  const failed = trial.criteria.filter(
    (c) => c.status === "SCORED" && c.passed === false,
  );
  const issues = trial.criteria.filter((c) => c.status !== "SCORED");
  const execution: Partial<Record<Trial["execution_status"], string>> = {
    FAILED: "Agent 执行失败",
    TIMED_OUT: "Agent 执行超时",
    EXECUTION_UNKNOWN: "Agent 执行结果未知",
    CANCELLED: "执行已取消",
    QUEUED: "等待 Agent 领取任务",
    LEASED: "Agent 已领取任务",
    RUNNING: "Agent 正在执行",
  };
  const title = execution[trial.execution_status];
  if (title)
    return { title, reasons: [...failed, ...issues].map((c) => c.reason) };
  if (trial.verdict === "PASS")
    return {
      title: failed.length
        ? "任务通过，部分评分项未通过"
        : issues.length
          ? "任务通过，部分评分项未完成"
          : "任务通过",
      reasons: [...failed, ...issues].map((c) => c.reason),
    };
  if (trial.verdict === "FAIL")
    return { title: "答案或过程未达标", reasons: failed.map((c) => c.reason) };
  if (issues.some((c) => c.status === "ERROR"))
    return { title: "评分器异常", reasons: issues.map((c) => c.reason) };
  if (issues.some((c) => c.status === "INSUFFICIENT_EVIDENCE"))
    return { title: "证据不足", reasons: issues.map((c) => c.reason) };
  if (trial.verdict === "PENDING")
    return { title: "等待评分完成", reasons: issues.map((c) => c.reason) };
  return { title: "暂时无法判断质量", reasons: issues.map((c) => c.reason) };
}

export const verdictLabel = (value: string) =>
  ({
    PASS: "通过",
    FAIL: "未通过",
    PENDING: "待评分",
    UNDETERMINED: "无法判定",
    CANCELLED: "已取消",
  })[value] ?? "无法判定";

export function pairTrials(left: Trial[], right: Trial[]) {
  const rows = new Map<
    string,
    { caseIndex: number; attempt: number; left?: Trial; right?: Trial }
  >();
  for (const [side, trials] of [
    ["left", left],
    ["right", right],
  ] as const) {
    for (const trial of trials) {
      const key = `${trial.case_index}:${trial.attempt}`;
      const row = rows.get(key) ?? {
        caseIndex: trial.case_index,
        attempt: trial.attempt,
      };
      row[side] = trial;
      rows.set(key, row);
    }
  }
  return [...rows.values()].sort(
    (a, b) => a.caseIndex - b.caseIndex || a.attempt - b.attempt,
  );
}

export function compareTrials(
  left: Trial | undefined,
  right: Trial | undefined,
  comparable: boolean,
) {
  if (!left || !right) return "尚未配对";
  if (!comparable || left.suite_version_id !== right.suite_version_id)
    return "不可直接比较";
  if (![left.verdict, right.verdict].every((v) => ["PASS", "FAIL"].includes(v)))
    return "尚无可判定变化";
  if (left.verdict === right.verdict) return "通过结论不变";
  return right.verdict === "PASS" ? "改善" : "退步";
}

export type CaseSource = {
  experiment_id: string;
  trial_id: string;
  snapshot_id: string;
  content_hash: string;
};
export function recoveryTask(
  task: Record<string, unknown>,
  source: CaseSource,
) {
  // Copy the task, never the Agent's answer. Existing expected:null is meaningful.
  return { ...task, source: { type: "evaluation_trial", ...source } };
}
