import { useState } from "react";
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

export function TrialEvidence({ trial }: { trial: Schema<"TrialReport"> }) {
  const { workspace_id } = useWorkspace();
  const cache = useQueryClient();
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
    },
  });
  const manifest = objectValue(evidence.data?.manifest);
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
        任务证据 · 案例 {trial.case_index + 1} / 第 {trial.attempt} 次
      </h2>
      <p>
        <code>{trial.trial_id}</code>
      </p>
      <ErrorNotice
        error={
          run.error ??
          evidence.error ??
          trace.error ??
          versions.error ??
          downloadError
        }
      />
      {snapshotId ? (
        <p className="hint">
          评分快照：<code>{snapshotId}</code>
          <br />
          内容哈希：<code>{evidence.data?.content_hash ?? "载入中"}</code> ·
          套件：
          <code>{run.data?.suite_version_id ?? trial.suite_version_id}</code>
        </p>
      ) : (
        <p>尚未冻结证据，任务结束后等待收集完成。</p>
      )}
      {run.isPending && runId ? (
        <Loading />
      ) : (
        run.data && (
          <>
            <p>
              评测状态：<Badge>{run.data.status}</Badge> · {run.data.purpose}
            </p>
            {run.data.status === "WAITING_QUOTA" && (
              <Link to="/workspace/quota">额度不足，查看个人额度</Link>
            )}
            <div className="criterion-list">
              {run.data.criteria.map((item) => (
                <article className="criterion" key={item.result_id}>
                  <div className="section-heading">
                    <h3>{item.result.criterion_id}</h3>
                    <Badge>{item.result.status}</Badge>
                  </div>
                  <p>
                    {item.result.status === "SCORED"
                      ? `自动分数：${item.result.score} · ${item.result.passed ? "通过" : "未通过"}`
                      : "暂无有效自动分数"}
                  </p>
                  <p>{item.result.reason}</p>
                  <small>评测器版本：{item.result.evaluator_version}</small>
                  <div className="actions evidence-links">
                    {item.result.evidence_refs.map((ref) => (
                      <button
                        key={ref}
                        onClick={() => {
                          setReference(ref);
                          document
                            .getElementById("selected-evidence")
                            ?.scrollIntoView?.({
                              behavior: "auto",
                              block: "center",
                            });
                        }}
                      >
                        {ref}
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
        <section id="selected-evidence" className="selected-evidence">
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
            重评套件版本
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
