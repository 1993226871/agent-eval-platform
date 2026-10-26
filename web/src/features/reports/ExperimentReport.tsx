import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { api, request } from "../../api/client";
import type { Schema } from "../../api/types";
import {
  Badge,
  Dialog,
  ErrorNotice,
  Loading,
  PageHeading,
} from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";
import { TrialEvidence } from "./TrialEvidence";
import { EstimateNotice, useExperimentEstimate } from "../experiments/estimate";

export function ExperimentReport() {
  const { id = "" } = useParams();
  return <ExperimentReportPage key={id} id={id} />;
}
function ExperimentReportPage({ id }: { id: string }) {
  const { workspace_id } = useWorkspace();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const [selected, setSelected] = useState<Schema<"TrialReport">>();
  const [compareId, setCompareId] = useState("");
  const [rerunBody, setRerunBody] = useState<Schema<"StartExperiment">>();
  const estimate = useExperimentEstimate(
    rerunBody?.dataset_version_id ?? "",
    rerunBody?.suite_version_id ?? "",
    rerunBody?.repeat_count ?? 1,
    !!rerunBody,
  );
  const report = useQuery({
    queryKey: [workspace_id, "report", id],
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/reports/experiments/{id}", {
          params: { path: { id } },
          signal,
        }),
      ),
    refetchInterval: 10000,
  });
  const experiment = useQuery({
    queryKey: [workspace_id, "experiment", id],
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/experiment-summaries/{id}", {
          params: { path: { id } },
          signal,
        }),
      ),
  });
  const others = useQuery({
    queryKey: [workspace_id, "comparison-options"],
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/experiment-summaries", {
          params: { query: { limit: 100 } },
          signal,
        }),
      ),
  });
  const trials = useInfiniteQuery({
    queryKey: [workspace_id, "report-trials", id],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      request(
        api.GET("/api/v1/reports/experiments/{id}/trials", {
          params: { path: { id }, query: { limit: 25, cursor: pageParam } },
          signal,
        }),
      ),
    getNextPageParam: (page) => page.next_cursor ?? undefined,
    refetchInterval: 10000,
  });
  const rerun = useMutation({
    mutationFn: () => {
      if (!rerunBody) throw new Error("请重新打开重跑确认。");
      return request(api.POST("/api/v1/experiments", { body: rerunBody }));
    },
    onSuccess: async (value) => {
      setRerunBody(undefined);
      await cache.invalidateQueries({
        queryKey: [workspace_id, "experiments"],
      });
      navigate("/workspace/reports/" + value.id);
    },
  });
  const download = useMutation({
    mutationFn: async () => {
      const value = await request(
        api.GET("/api/v1/reports/experiments/{id}/export", {
          params: { path: { id } },
        }),
      );
      const url = URL.createObjectURL(
        new Blob([JSON.stringify(value, null, 2)], {
          type: "application/json;charset=utf-8",
        }),
      );
      const link = document.createElement("a");
      link.href = url;
      link.download = `agenteval-${id}.json`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    },
  });
  const value = report.data;
  const currentTrial =
    trials.data?.pages
      .flatMap((page) => page.items)
      .find((item) => item.trial_id === selected?.trial_id) ?? selected;
  return (
    <>
      <PageHeading title="实验报告" description={id}>
        <button disabled={download.isPending} onClick={() => download.mutate()}>
          导出 JSON 与证据
        </button>
        <button
          disabled={!experiment.data}
          onClick={() => {
            const source = experiment.data;
            if (!source) return;
            rerun.reset();
            setRerunBody({
              agent_id: source.agent_id,
              agent_version: source.agent_version,
              dataset_version_id: source.dataset_version_id,
              suite_version_id: source.suite_version_id,
              repeat_count: source.repeat_count,
              source_experiment_id: id,
              deadline: new Date(Date.now() + 3600000).toISOString(),
              idempotency_key: crypto.randomUUID(),
            });
          }}
        >
          重跑 Agent
        </button>
      </PageHeading>
      {experiment.data?.source_experiment_id && (
        <p>
          重跑来源：
          <Link
            to={"/workspace/reports/" + experiment.data.source_experiment_id}
          >
            {experiment.data.source_experiment_id}
          </Link>
        </p>
      )}
      <ErrorNotice
        error={
          report.error ??
          experiment.error ??
          others.error ??
          trials.error ??
          download.error
        }
      />
      {report.isPending ? (
        <Loading />
      ) : (
        value && (
          <>
            <div className="report-progress">
              <p>
                执行结束：
                <strong>
                  {value.finished_attempts} / {value.planned_attempts}
                </strong>{" "}
                · 已开始 {value.started_attempts} · 实验状态{" "}
                <Badge>{value.experiment_status}</Badge>
              </p>
              <p>
                质量已判定：
                <strong>
                  {value.determinable_attempts} / {value.planned_attempts}
                </strong>{" "}
                · 待评 {value.pending_attempts} · 不确定{" "}
                {value.undetermined_attempts} · 取消 {value.cancelled_attempts}
              </p>
            </div>
            {(value.evaluation_suite_version_ids.length !== 1 ||
              value.evaluation_suite_version_ids[0] !==
                value.suite_version_id) && (
              <p className="warning-notice">
                当前报告包含重评配置或多个实际评分套件，请先核对版本范围，再进行实验对比。
              </p>
            )}
            <dl className="metrics">
              <div>
                <dt>通过 / 计划尝试</dt>
                <dd>
                  {rate(value.pass_rate_planned.value)}
                  <small>
                    {value.pass_rate_planned.numerator} /{" "}
                    {value.pass_rate_planned.denominator}
                  </small>
                </dd>
              </div>
              <div>
                <dt>通过 / 可判定尝试</dt>
                <dd>
                  {rate(value.pass_rate_determinable.value)}
                  <small>
                    {value.pass_rate_determinable.numerator} /{" "}
                    {value.pass_rate_determinable.denominator}
                  </small>
                </dd>
              </div>
              <div>
                <dt>结果质量</dt>
                <dd>
                  {value.dimensions.outcome?.score ?? "—"}
                  <small>
                    已评分 {value.dimensions.outcome?.scored_criteria ?? 0} /{" "}
                    {value.dimensions.outcome?.planned_criteria ?? 0}
                  </small>
                </dd>
              </div>
              <div>
                <dt>执行过程</dt>
                <dd>
                  {value.dimensions.process?.score ?? "—"}
                  <small>
                    已评分 {value.dimensions.process?.scored_criteria ?? 0} /{" "}
                    {value.dimensions.process?.planned_criteria ?? 0}
                  </small>
                </dd>
              </div>
            </dl>
            <div className="two-column">
              <section className="panel">
                <h2>覆盖率</h2>
                {Object.entries(value.dimensions).map(([dimension, score]) => (
                  <div className="coverage-row" key={dimension}>
                    <strong>
                      {dimension === "outcome" ? "结果质量" : "执行过程"}
                    </strong>
                    <p>
                      权重覆盖 {rate(score.coverage)} · {score.scored_weight} /{" "}
                      {score.total_weight}
                    </p>
                    <p>
                      指标覆盖 {score.scored_criteria} /{" "}
                      {score.planned_criteria}
                      {score.partial ? " · 部分评分" : ""}
                    </p>
                  </div>
                ))}
                <p className="hint">
                  缺失、ERROR
                  和额度等待不计为零分。可选项高分不能抵消必需项失败。
                </p>
              </section>
              <section className="panel">
                <h2>执行效率</h2>
                <p>
                  P50：
                  {value.efficiency.p50_duration_ms === null
                    ? "未知"
                    : `${value.efficiency.p50_duration_ms} ms`}
                </p>
                <p>
                  P95：
                  {value.efficiency.p95_duration_ms === null
                    ? "未知"
                    : `${value.efficiency.p95_duration_ms} ms`}
                </p>
                <p>可用耗时样本：{value.efficiency.observed_durations}</p>
                <p className="hint">
                  来源：{value.efficiency.source}。仅统计冻结证据中可验证的根
                  Span 耗时。
                </p>
              </section>
            </div>
            <details className="panel">
              <summary>固定版本与执行状态</summary>
              <p>Agent 版本：{value.agent_version}</p>
              <p>
                任务集：<code>{value.dataset_version_id}</code>
              </p>
              <p>
                原始套件：<code>{value.suite_version_id}</code>
              </p>
              <p>
                当前实际套件：
                <code>{value.evaluation_suite_version_ids.join(", ")}</code>
              </p>
              <p>
                {Object.entries(value.execution_counts)
                  .map(([status, count]) => `${status}: ${count}`)
                  .join(" · ")}
              </p>
            </details>
            <div className="comparison-picker">
              <label>
                对比最近 100 次实验
                <select
                  value={compareId}
                  onChange={(event) => setCompareId(event.target.value)}
                >
                  <option value="">选择另一次实验</option>
                  {others.data
                    ?.filter((item) => item.id !== id)
                    .map((item) => (
                      <option key={item.id} value={item.id}>
                        {item.agent_version} · {item.id.slice(0, 8)}
                      </option>
                    ))}
                </select>
              </label>
              {compareId && (
                <Link
                  className="button-link"
                  to={
                    "/workspace/comparisons?left=" +
                    encodeURIComponent(id) +
                    "&right=" +
                    encodeURIComponent(compareId)
                  }
                >
                  查看可比性与差异
                </Link>
              )}
            </div>
          </>
        )
      )}
      <section className="panel">
        <h2>逐任务结果</h2>
        <p className="hint">点击任务查看固定证据、指标解释和人工复核。</p>
        {trials.isPending ? (
          <Loading />
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>案例 / 尝试</th>
                  <th>执行</th>
                  <th>评测</th>
                  <th>质量结论</th>
                  <th>结果 / 过程</th>
                </tr>
              </thead>
              <tbody>
                {trials.data?.pages
                  .flatMap((page) => page.items)
                  .map((item) => (
                    <tr key={item.trial_id}>
                      <td>
                        <button
                          className="text-button"
                          onClick={() => setSelected(item)}
                        >
                          案例 {item.case_index + 1} · 第 {item.attempt} 次
                        </button>
                        <small>{item.trial_id}</small>
                      </td>
                      <td>
                        <Badge>{item.execution_status}</Badge>
                      </td>
                      <td>
                        <Badge>{item.evaluation_state}</Badge>
                      </td>
                      <td>
                        <Badge
                          tone={
                            item.verdict === "PASS"
                              ? "success"
                              : item.verdict === "FAIL"
                                ? "danger"
                                : "warning"
                          }
                        >
                          {item.verdict}
                        </Badge>
                      </td>
                      <td>
                        {item.dimensions.outcome?.score ?? "—"} /{" "}
                        {item.dimensions.process?.score ?? "—"}
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="actions">
          {trials.hasNextPage && (
            <button
              disabled={trials.isFetchingNextPage}
              onClick={() => trials.fetchNextPage()}
            >
              加载更多任务
            </button>
          )}
          {trials.isFetching && (
            <button
              onClick={() =>
                cache.cancelQueries({
                  queryKey: [workspace_id, "report-trials", id],
                })
              }
            >
              停止加载任务
            </button>
          )}
        </div>
      </section>
      {currentTrial && (
        <TrialEvidence key={id + currentTrial.trial_id} trial={currentTrial} />
      )}
      <Dialog
        open={!!rerunBody}
        title="重新执行 Agent"
        onClose={() => setRerunBody(undefined)}
      >
        <p>
          创建一个新实验，使用原始任务集、套件、Agent
          版本和重复次数。新的截止时间为一小时后。Agent
          的工具会再次执行，模型评测再次计费；请确认自己的 Agent
          使用适合重复运行的任务环境。
        </p>
        <ErrorNotice error={rerun.error} />
        <EstimateNotice value={estimate.data} error={estimate.error} />
        <button
          className="primary"
          disabled={rerun.isPending || !estimate.data || estimate.isFetching}
          onClick={() => rerun.mutate()}
        >
          确认创建新实验
        </button>
      </Dialog>
    </>
  );
}
function rate(value: number | null) {
  return value === null
    ? "—"
    : new Intl.NumberFormat("zh-CN", {
        style: "percent",
        maximumFractionDigits: 1,
      }).format(value);
}
