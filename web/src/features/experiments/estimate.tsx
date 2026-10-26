import { useQuery } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import type { Schema } from "../../api/types";
import { ErrorNotice } from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";
import { formatCredits } from "../models/credits";

export function useExperimentEstimate(
  dataset: string,
  suite: string,
  repeats: number,
  enabled: boolean,
) {
  const { workspace_id } = useWorkspace();
  return useQuery({
    queryKey: [workspace_id, "experiment-estimate", dataset, suite, repeats],
    enabled:
      enabled &&
      !!dataset &&
      !!suite &&
      Number.isInteger(repeats) &&
      repeats >= 1 &&
      repeats <= 20,
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/experiments/estimate", {
          params: {
            query: {
              datasetVersionId: dataset,
              suiteVersionId: suite,
              repeatCount: repeats,
            },
          },
          signal,
        }),
      ),
  });
}
export function EstimateNotice({
  value,
  error,
}: {
  value?: Schema<"ExperimentEstimate">;
  error: unknown;
}) {
  return (
    <>
      <ErrorNotice error={error} />
      {value && value.planned_credits !== null && (
        <p className="hint">
          首次评测预留上界：{formatCredits(value.planned_credits)} credits（
          {value.trial_count} 次任务、{value.planned_calls}{" "}
          次模型调用）。按所选版本、固定模型价格及 Token
          上限计算；实际按用量结算，格式修复重试和重评另计，此值不是消费硬上限。
        </p>
      )}
      {value && value.planned_credits === null && (
        <p className="warning-notice">
          无法给出完整费用预估：部分 LLM 评测器缺少有效模型、价格或 Token
          配置。请检查所选套件；无效配置不会获得评分。重试和重评另计。
        </p>
      )}
    </>
  );
}
