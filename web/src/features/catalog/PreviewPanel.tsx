import { useRef, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { z } from "zod";
import { api, request } from "../../api/client";
import type { CatalogResource } from "../../api/types";
import { ErrorNotice, JsonBlock } from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";
import { resourceWaitMessage } from "../administration/ResourceUsagePanel";

export function PreviewPanel({ resource }: { resource: CatalogResource }) {
  const { workspace_id } = useWorkspace();
  const [trial, setTrial] = useState("");
  const [runId, setRunId] = useState("");
  const pending = useRef<{ fingerprint: string; key: string } | null>(null);
  const preview = useMutation({
    mutationFn: async () => {
      const id = z.uuid().parse(trial.trim());
      const fingerprint = JSON.stringify([resource.id, resource.revision, id]);
      if (pending.current?.fingerprint !== fingerprint)
        pending.current = { fingerprint, key: crypto.randomUUID() };
      return request(
        api.POST("/api/v1/evaluators/{draftId}/previews", {
          params: { path: { draftId: resource.id } },
          body: {
            trial_id: id,
            revision: resource.revision,
            idempotency_key: pending.current.key,
          },
        }),
      );
    },
    onSuccess: (run) => {
      pending.current = null;
      setRunId(run.run_id);
    },
  });
  const run = useQuery({
    queryKey: [workspace_id, "preview", runId],
    enabled: !!runId,
    queryFn: () =>
      request(
        api.GET("/api/v1/evaluation-runs/{runId}", {
          params: { path: { runId } },
        }),
      ),
    refetchInterval: (query) =>
      ["COMPLETED", "PARTIAL", "CANCELLED"].includes(
        query.state.data?.status ?? "",
      )
        ? false
        : 3000,
  });
  return (
    <details className="preview-panel">
      <summary>用已有任务证据试运行</summary>
      <p>
        选择一个已结束任务的 Trial
        ID。试运行使用当前已保存草稿，不改变正式结果；模型评审会消耗个人额度。
      </p>
      <label>
        试运行 Trial ID
        <input
          value={trial}
          onChange={(event) => setTrial(event.target.value)}
        />
      </label>
      <button
        disabled={preview.isPending || !trial.trim()}
        onClick={() => preview.mutate()}
      >
        提交试运行
      </button>
      <ErrorNotice error={preview.error ?? run.error} />
      {run.data && (
        <>
          <p>
            状态：{run.data.status} · {run.data.purpose}
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
            <Link to="/workspace/quota">额度不足，查看账户额度</Link>
          )}
          <JsonBlock value={run.data.criteria} />
        </>
      )}
    </details>
  );
}
