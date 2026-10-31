import { useId, useState } from "react";
import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { api, request } from "../../api/client";
import type { Schema } from "../../api/types";
import { Badge, ErrorNotice, JsonBlock, Loading } from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";
import { objectValue } from "../catalog/validation";
import { useVersionOptions } from "../catalog/queries";
import { TraceTree } from "./TraceTree";
import { HumanReviewPanel } from "./HumanReviewPanel";
import { resourceWaitMessage } from "../administration/ResourceUsagePanel";
import { CaseRecovery } from "./CaseRecovery";
import { diagnoseTrial } from "./diagnostics";

export function TrialEvidence({
  trial,
  experimentId,
}: {
  trial: Schema<"TrialReport">;
  experimentId: string;
}) {
  const { workspace_id } = useWorkspace();
  const cache = useQueryClient();
  const evidenceAnchor = useId();
  const [runOverride, setRunOverride] = useState("");
  const [reference, setReference] = useState("");
  const [suite, setSuite] = useState(trial.suite_version_id);
  const [includeLate, setIncludeLate] = useState(false);
  const [key, setKey] = useState(crypto.randomUUID());
  const runId = runOverride || trial.run_id || "";
  const versions = useVersionOptions("SUITE");
  const run = useQuery({
    queryKey: [workspace_id, "run", runId],
    enabled: !!runId,
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/evaluation-runs/{runId}", {
          params: { path: { runId } },
          signal,
        }),
      ),
    refetchInterval: (query) =>
      ["COMPLETED", "PARTIAL", "CANCELLED"].includes(
        query.state.data?.status ?? "",
      )
        ? false
        : 5000,
  });
  const snapshotId = run.data?.snapshot_id ?? trial.snapshot_id ?? "";
  const evidence = useQuery({
    queryKey: [workspace_id, "evidence", snapshotId],
    enabled: !!snapshotId,
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/evidence/snapshots/{id}", {
          params: { path: { id: snapshotId } },
          signal,
        }),
      ),
  });
  const trace = useInfiniteQuery({
    queryKey: [workspace_id, "trace", trial.trial_id],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      request(
        api.GET("/api/v1/traces/{id}", {
          params: {
            path: { id: trial.trial_id },
            query: { cursor: pageParam, limit: 50 },
          },
          signal,
        }),
      ),
    getNextPageParam: (page) => page.next_cursor ?? undefined,
  });
  const regrade = useMutation({
    mutationFn: () =>
      request(
        api.POST("/api/v1/trial-evaluations/{trialId}/reevaluations", {
          params: { path: { trialId: trial.trial_id } },
          body: {
            suite_version_id: suite,
            include_late: includeLate,
            idempotency_key: key,
          },
        }),
      ),
    onSuccess: async (value) => {
      setRunOverride(value.run_id);
      setKey(crypto.randomUUID());
      await cache.invalidateQueries({ queryKey: [workspace_id, "report"] });
      await cache.invalidateQueries({ queryKey: [workspace_id, "comparison"] });
    },
  });
  const manifest = objectValue(evidence.data?.manifest);
  const task = objectValue(manifest.task);
  const completion = objectValue(manifest.completion);
  const events = Array.isArray(manifest.events)
    ? manifest.events.map(objectValue)
    : [];
  const artifacts = Array.isArray(manifest.artifacts)
    ? manifest.artifacts.map(objectValue)
    : [];
  const selectedEvent = reference.startsWith("event:")
    ? events.find((event) => event.event_id === reference.slice(6))
    : undefined;
  const selectedArtifact = reference.startsWith("artifact:")
    ? artifacts.find((artifact) => artifact.artifact_id === reference.slice(9))
    : undefined;
  const [downloadError, setDownloadError] = useState<unknown>();
  async function download(id: string) {
    setDownloadError(undefined);
    try {
      const grant = await request(
        api.GET("/api/v1/artifacts/{id}/download", {
          params: { path: { id } },
        }),
      );
      const url = new URL(grant.url);
      if (!["https:", "http:"].includes(url.protocol))
        throw new Error("附件下载地址无效。");
      window.open(url.href, "_blank", "noopener,noreferrer");
    } catch (failure) {
      setDownloadError(failure);
    }
  }
  let evidenceValue: unknown = null;
  if (selectedEvent) evidenceValue = selectedEvent;
  else if (selectedArtifact) evidenceValue = selectedArtifact;
  else if (reference === "output")
    evidenceValue = objectValue(manifest.completion).output;
  else if (reference === "expected")
    evidenceValue = objectValue(manifest.task).expected;
  else if (reference === "task_input")
    evidenceValue = objectValue(manifest.task).input;
  else if (reference === "task_instruction")
    evidenceValue = objectValue(manifest.task).instruction;
  else if (reference.startsWith("snapshot:"))
    evidenceValue = {
      snapshot_id: snapshotId,
      hash: evidence.data?.content_hash,
      completeness: evidence.data?.completeness,
      issues: manifest.issues,
    };
  return (
    <section className="panel trial-evidence">
      <h2>
        任务证据 · 任务 {trial.case_index + 1} / 第 {trial.attempt} 次
      </h2>
      <details className="identity-details">
        <summary>查看任务执行编号</summary>
        <p>
          <code>{trial.trial_id}</code>
        </p>
      </details>
      {!runOverride && (
        <p className="diagnosis-heading">{diagnoseTrial(trial).title}</p>
      )}
      <ErrorNotice
        error={
          run.error ??
          evidence.error ??
          trace.error ??
          versions.error ??
          downloadError
        }
      />
      {evidence.data && (
        <section className="task-answer" aria-label="任务与答案">
          <h3>任务要求</h3>
          <p className="task-instruction">
            {String(task.instruction ?? "未提供任务要求")}
          </p>
          <h3>任务输入</h3>
          <EvidenceValue value={task.input} />
          <h3>参考答案</h3>
          {Object.hasOwn(task, "expected") ? (
            <EvidenceValue value={task.expected} />
          ) : (
            <p>未提供参考答案，评分依照所选规则和 Rubric。</p>
          )}
          <h3>Agent 实际回答</h3>
          {Object.hasOwn(completion, "output") ? (
            <EvidenceValue value={completion.output} />
          ) : (
            <p>没有已提交的最终回答，请检查执行状态。</p>
          )}
          {(trial.verdict === "FAIL" ||
            ["FAILED", "TIMED_OUT", "EXECUTION_UNKNOWN"].includes(
              trial.execution_status,
            )) &&
            typeof task.instruction === "string" &&
            Object.hasOwn(task, "input") && (
              <CaseRecovery
                key={snapshotId}
                task={task}
                source={{
                  experiment_id: experimentId,
                  trial_id: trial.trial_id,
                  snapshot_id: snapshotId,
                  content_hash: evidence.data.content_hash,
                }}
              />
            )}
        </section>
      )}
      {snapshotId ? (
        <details className="identity-details">
          <summary>查看评分版本与证据指纹</summary>
          <p className="hint">
            评分快照：<code>{snapshotId}</code>
            <br />
            内容哈希：<code>{evidence.data?.content_hash ?? "载入中"}</code> ·
            套件：
            <code>{run.data?.suite_version_id ?? trial.suite_version_id}</code>
          </p>
        </details>
      ) : (
        <p>
          {trial.evidence_wait_reason
            ? resourceWaitMessage(trial.evidence_wait_reason)
            : "尚未冻结证据，任务结束后等待收集完成。"}{" "}
          <Link to="/workspace/quota">查看额度与资源预算</Link>
        </p>
      )}
      {run.isPending && runId ? (
        <Loading />
      ) : (
        run.data && (
          <>
            <p>
              评测状态：<Badge>{evaluationLabel(run.data.status)}</Badge> ·{" "}
              {(
                {
                  INITIAL: "首次评分",
                  REEVALUATION: "重新评分",
                  PREVIEW: "预览评分",
                } as Record<string, string>
              )[run.data.purpose] ?? run.data.purpose}
            </p>
            {run.data.wait_reasons.length > 0 && (
              <div className="warning-notice">
                {run.data.wait_reasons.map((reason) => (
                  <p key={reason}>{resourceWaitMessage(reason)}</p>
                ))}
                <Link to="/workspace/quota">查看额度与资源预算</Link>
              </div>
            )}
            {run.data.status === "WAITING_QUOTA" && (
              <Link to="/workspace/quota">额度不足，查看个人额度</Link>
            )}
            <div className="criterion-list">
              {run.data.criteria.map((item) => (
                <article className="criterion" key={item.result_id}>
                  <div className="section-heading">
                    <h3>{item.result.criterion_id}</h3>
                    <Badge>{evaluationLabel(item.result.status)}</Badge>
                  </div>
                  <p>
                    {item.result.status === "SCORED"
                      ? `自动分数：${item.result.score} · ${item.result.passed ? "通过" : "未通过"}`
                      : "暂无有效自动分数"}
                  </p>
                  <p>{item.result.reason}</p>
                  <small>评分规则版本：{item.result.evaluator_version}</small>
                  <div className="actions evidence-links">
                    {item.result.evidence_refs.map((ref) => (
                      <button
                        key={ref}
                        onClick={() => {
                          setReference(ref);
                          document
                            .getElementById(evidenceAnchor)
                            ?.scrollIntoView?.({
                              behavior: "auto",
                              block: "center",
                            });
                        }}
                      >
                        {evidenceLabel(ref)}
                      </button>
                    ))}
                  </div>
                  {item.result.status !== "WAITING_QUOTA" && (
                    <HumanReviewPanel resultId={item.result_id} />
                  )}
                </article>
              ))}
            </div>
          </>
        )
      )}
      {reference && (
        <section id={evidenceAnchor} className="selected-evidence">
          <h3>固定快照中的证据：{reference}</h3>
          {evidenceValue === undefined || evidenceValue === null ? (
            <p>该引用在当前快照中无可展示内容。</p>
          ) : (
            <JsonBlock value={evidenceValue} />
          )}
          {selectedArtifact &&
            typeof selectedArtifact.artifact_id === "string" && (
              <button
                onClick={() => download(selectedArtifact.artifact_id as string)}
              >
                下载已授权附件
              </button>
            )}
        </section>
      )}
      {evidence.isPending && snapshotId ? (
        <Loading />
      ) : (
        <TraceTree
          spans={trace.data?.pages.flatMap((page) => page.spans) ?? []}
          completeness={evidence.data?.completeness ?? "NONE"}
          issues={
            Array.isArray(manifest.issues)
              ? manifest.issues.filter(
                  (value): value is string => typeof value === "string",
                )
              : []
          }
          highlightedSpan={
            typeof selectedEvent?.span_id === "string"
              ? selectedEvent.span_id
              : undefined
          }
        />
      )}
      {trace.hasNextPage && (
        <button
          disabled={trace.isFetchingNextPage}
          onClick={() => trace.fetchNextPage()}
        >
          加载更多轨迹
        </button>
      )}
      {trace.isFetching && (
        <button
          onClick={() =>
            cache.cancelQueries({
              queryKey: [workspace_id, "trace", trial.trial_id],
            })
          }
        >
          停止加载
        </button>
      )}
      <details className="preview-panel">
        <summary>创建新一轮评测</summary>
        <p>
          保留历史结果，按所选套件重新评分。默认复用原始证据；选择纳入迟到证据可能生成新快照。LLM
          会重新计费，Python 会再次占用沙箱资源。
        </p>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            regrade.mutate();
          }}
        >
          <label>
            重评评分规则集版本
            <select
              required
              value={suite}
              onChange={(event) => {
                setSuite(event.target.value);
                setKey(crypto.randomUUID());
              }}
            >
              <option value={trial.suite_version_id}>
                当前任务套件 · {trial.suite_version_id}
              </option>
              {versions.data
                ?.filter((item) => item.id !== trial.suite_version_id)
                .map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.label}
                  </option>
                ))}
            </select>
          </label>
          <label className="check-label">
            <input
              type="checkbox"
              checked={includeLate}
              onChange={(event) => {
                setIncludeLate(event.target.checked);
                setKey(crypto.randomUUID());
              }}
            />
            纳入迟到证据
          </label>
          <ErrorNotice error={regrade.error} />
          <button
            className="primary"
            disabled={
              regrade.isPending ||
              ["QUEUED", "LEASED", "RUNNING", "CANCELLED"].includes(
                trial.execution_status,
              )
            }
          >
            提交重评
          </button>
        </form>
      </details>
    </section>
  );
}

function evidenceLabel(reference: string) {
  if (reference === "output") return "查看实际回答";
  if (reference === "expected") return "查看参考答案";
  if (reference === "task_input") return "查看任务输入";
  if (reference === "task_instruction") return "查看任务要求";
  if (reference.startsWith("event:")) return "查看关联执行步骤";
  if (reference.startsWith("artifact:")) return "查看关联附件";
  if (reference.startsWith("snapshot:")) return "查看证据快照";
  return reference;
}

function evaluationLabel(value: string) {
  return (
    (
      {
        COMPLETED: "评分完成",
        PARTIAL: "部分评分",
        QUEUED: "等待评分",
        RUNNING: "评分中",
        CANCELLED: "已取消",
        SCORED: "已评分",
        ERROR: "评分器异常",
        INSUFFICIENT_EVIDENCE: "证据不足",
        SKIPPED: "已跳过",
        WAITING_QUOTA: "等待额度",
        WAITING_RESOURCES: "等待资源",
      } as Record<string, string>
    )[value] ?? value
  );
}

function EvidenceValue({ value }: { value: unknown }) {
  if (value === null) return <span>空值（null）</span>;
  if (value === undefined) return <span>未提供</span>;
  if (Array.isArray(value))
    return value.length ? (
      <ol>
        {value.map((item, index) => (
          <li key={index}>
            <EvidenceValue value={item} />
          </li>
        ))}
      </ol>
    ) : (
      <span>空列表</span>
    );
  if (typeof value === "object")
    return (
      <dl className="evidence-value">
        {Object.entries(value).map(([key, item]) => (
          <div key={key}>
            <dt>{key}</dt>
            <dd>
              <EvidenceValue value={item} />
            </dd>
          </div>
        ))}
      </dl>
    );
  return (
    <p className="task-instruction">
      {typeof value === "boolean"
        ? value
          ? "是（true）"
          : "否（false）"
        : String(value) || "空文本"}
    </p>
  );
}
