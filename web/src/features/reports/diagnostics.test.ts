import { describe, expect, it } from "vitest";
import type { Schema } from "../../api/types";
import {
  compareTrials,
  diagnoseTrial,
  pairTrials,
  recoveryTask,
} from "./diagnostics";

const criterion: Schema<"CriterionResult"> = {
  criterion_id: "amount",
  status: "SCORED",
  score: 0,
  passed: false,
  reason: "期望100，实际120",
  evidence_refs: ["output", "event:tool"],
  evaluator_version: "rule",
};
const trial: Schema<"TrialReport"> = {
  trial_id: "trial",
  case_index: 0,
  attempt: 1,
  execution_status: "SUCCEEDED",
  run_id: "run",
  snapshot_id: "snapshot",
  evaluation_state: "COMPLETED",
  verdict: "FAIL",
  dimensions: {},
  criteria: [criterion],
  reported_duration_ms: 10,
  suite_version_id: "suite",
  evidence_wait_reason: null,
};
describe("evidence based diagnosis", () => {
  it("explains failed checks using their actual reasons", () => {
    expect(diagnoseTrial(trial)).toEqual({
      title: "答案或过程未达标",
      reasons: [criterion.reason],
    });
  });
  it("does not call scorer errors, missing evidence or unfinished work wrong answers", () => {
    expect(
      diagnoseTrial({
        ...trial,
        verdict: "UNDETERMINED",
        criteria: [{ ...criterion, status: "ERROR", passed: null }],
      }).title,
    ).toBe("评分器异常");
    expect(
      diagnoseTrial({
        ...trial,
        verdict: "UNDETERMINED",
        criteria: [
          { ...criterion, status: "INSUFFICIENT_EVIDENCE", passed: null },
        ],
      }).title,
    ).toBe("证据不足");
    expect(
      diagnoseTrial({ ...trial, verdict: "PENDING", criteria: [] }).title,
    ).toBe("等待评分完成");
    expect(
      diagnoseTrial({ ...trial, execution_status: "TIMED_OUT" }).title,
    ).toBe("Agent 执行超时");
  });
  it("does not diagnose an optional failed criterion as a failed task", () => {
    expect(diagnoseTrial({ ...trial, verdict: "PASS" }).title).toBe(
      "任务通过，部分评分项未通过",
    );
  });
});
describe("paired tasks", () => {
  it("pairs by task and attempt, not returned position", () => {
    const second = { ...trial, trial_id: "second", case_index: 1 };
    const repeat = { ...trial, trial_id: "repeat", attempt: 2 };
    const rows = pairTrials(
      [second, trial, repeat],
      [{ ...trial, trial_id: "right", verdict: "PASS" }],
    );
    expect(rows.map((r) => [r.caseIndex, r.attempt])).toEqual([
      [0, 1],
      [0, 2],
      [1, 1],
    ]);
    expect(rows[0]!.right?.trial_id).toBe("right");
    expect(compareTrials(rows[0]!.left, rows[0]!.right, true)).toBe("改善");
    expect(compareTrials(rows[0]!.right, rows[0]!.left, true)).toBe("退步");
    expect(compareTrials(rows[0]!.left, rows[0]!.right, false)).toBe(
      "不可直接比较",
    );
    expect(
      compareTrials(
        trial,
        { ...trial, suite_version_id: "regraded-suite", verdict: "PASS" },
        true,
      ),
    ).toBe("不可直接比较");
    expect(compareTrials(trial, undefined, true)).toBe("尚未配对");
    expect(compareTrials({ ...trial, verdict: "PENDING" }, trial, true)).toBe(
      "尚无可判定变化",
    );
  });
});
it("copies source task and provenance without turning the wrong output into ground truth", () => {
  const source = {
    experiment_id: "exp",
    trial_id: "trial",
    snapshot_id: "snap",
    content_hash: "hash",
  };
  const original = {
    instruction: "查询订单",
    input: { id: "A" },
    expected: null,
    timeout_seconds: 30,
  };
  const result = recoveryTask(original, source);
  expect(result).toEqual({
    ...original,
    source: { type: "evaluation_trial", ...source },
  });
  expect(
    recoveryTask({ instruction: "提问", input: "hi" }, source),
  ).not.toHaveProperty("expected");
  expect(original).not.toHaveProperty("source");
});
