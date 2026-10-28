import { Link, useSearchParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import {
  Badge,
  ErrorNotice,
  JsonBlock,
  Loading,
  PageHeading,
} from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";
import type { Schema } from "../../api/types";

const reasonLabels: Record<string, string> = {
  DATASET_VERSION_MISMATCH: "任务集版本不同",
  SUITE_VERSION_MISMATCH: "实际评分规则集版本不同",
  MIXED_EVALUATION_SUITES: "实验中混用了多个评分规则集",
  EVALUATOR_VERSION_MISMATCH: "评分规则版本不同",
  MODEL_PRICE_VERSION_MISMATCH: "模型或计价版本不同",
  EVIDENCE_SCOPE_MISMATCH: "证据完整性或原始/修订范围不同",
  ATTEMPT_PLAN_MISMATCH: "计划执行次数不同",
  EXECUTION_OR_EVALUATION_PENDING: "执行或评测仍在进行",
  NO_DETERMINABLE_DATA: "缺少可判定数据",
  COVERAGE_MISMATCH: "评分覆盖范围不同",
};
export function ComparisonView() {
  const { workspace_id } = useWorkspace();
  const [search] = useSearchParams();
  const left = search.get("left") ?? "";
  const right = search.get("right") ?? "";
  const comparison = useQuery({
    queryKey: [workspace_id, "comparison", left, right],
    enabled: !!left && !!right,
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/reports/comparison", {
          params: { query: { left, right } },
          signal,
        }),
      ),
  });
  return (
    <>
      <PageHeading
        title="实验对比"
        description="按任务、实际评分配置和证据采集范围判断可比性，按维度查看变化。"
      />
      <ErrorNotice error={comparison.error} />
      {!left || !right ? (
        <p>请从实验报告选择两次实验。</p>
      ) : comparison.isPending ? (
        <Loading />
      ) : (
        comparison.data && <ComparisonDetails comparison={comparison.data} />
      )}
    </>
  );
}
export function ComparisonDetails({
  comparison,
}: {
  comparison: Schema<"ComparisonReport">;
}) {
  const { left, right } = comparison;
  return (
    <>
      <div className="comparison-status">
        <Badge tone={comparison.directly_comparable ? "success" : "warning"}>
          {comparison.directly_comparable ? "可以直接比较" : "不能直接比较"}
        </Badge>
        {!comparison.directly_comparable && (
          <ul>
            {comparison.reasons.map((reason) => (
              <li key={reason}>{reasonLabels[reason] ?? reason}</li>
            ))}
          </ul>
        )}
      </div>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>范围 / 指标</th>
              <th>
                <Link to={"/workspace/reports/" + left.experiment_id}>
                  左侧 {left.agent_version}
                </Link>
              </th>
              <th>
                <Link to={"/workspace/reports/" + right.experiment_id}>
                  右侧 {right.agent_version}
                </Link>
              </th>
              <th>右 − 左</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <th>任务集版本</th>
              <td>
                <code>{left.dataset_version_id}</code>
              </td>
              <td>
                <code>{right.dataset_version_id}</code>
              </td>
              <td>—</td>
            </tr>
            <tr>
              <th>实际评分规则集版本</th>
              <td>{left.evaluation_suite_version_ids.join(", ")}</td>
              <td>{right.evaluation_suite_version_ids.join(", ")}</td>
              <td>—</td>
            </tr>
            <tr>
              <th>通过 / 计划</th>
              <td>
                {left.pass_rate_planned.numerator} /{" "}
                {left.pass_rate_planned.denominator}
              </td>
              <td>
                {right.pass_rate_planned.numerator} /{" "}
                {right.pass_rate_planned.denominator}
              </td>
              <td>—</td>
            </tr>
            <tr>
              <th>通过 / 可判定</th>
              <td>
                {left.pass_rate_determinable.numerator} /{" "}
                {left.pass_rate_determinable.denominator}
              </td>
              <td>
                {right.pass_rate_determinable.numerator} /{" "}
                {right.pass_rate_determinable.denominator}
              </td>
              <td>—</td>
            </tr>
            {[
              ...new Set([
                ...Object.keys(left.dimensions),
                ...Object.keys(right.dimensions),
              ]),
            ].map((dimension) => (
              <tr key={dimension}>
                <th>
                  {dimension === "outcome"
                    ? "结果质量"
                    : dimension === "process"
                      ? "执行过程"
                      : dimension}
                </th>
                <td>
                  {left.dimensions[dimension]?.score ?? "无评分"}
                  <small>
                    覆盖：{left.dimensions[dimension]?.scored_criteria ?? 0} /{" "}
                    {left.dimensions[dimension]?.planned_criteria ?? 0}
                  </small>
                </td>
                <td>
                  {right.dimensions[dimension]?.score ?? "无评分"}
                  <small>
                    覆盖：{right.dimensions[dimension]?.scored_criteria ?? 0} /{" "}
                    {right.dimensions[dimension]?.planned_criteria ?? 0}
                  </small>
                </td>
                <td>
                  {comparison.directly_comparable
                    ? (comparison.dimension_deltas[dimension] ?? "—")
                    : "不可直接比较"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="hint">
        证据哈希标识各次执行的实际内容。可比性核对的是同一任务版本、评分版本、原始/修订范围与完整性，执行内容无需相同。对比不代表因果结论。
      </p>
      <details className="panel">
        <summary>查看双方版本和证据来源</summary>
        <JsonBlock
          value={{
            left: comparison.left_provenance,
            right: comparison.right_provenance,
          }}
        />
      </details>
    </>
  );
}
