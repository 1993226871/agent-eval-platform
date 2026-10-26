import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import type { CurrentUser, Schema } from "../../api/types";
import { ErrorNotice, Loading } from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";

type Limits = Schema<"ResourceLimits">;
const fields: { key: keyof Limits; label: string; min: number; max: number }[] =
  [
    { key: "max_online_workers", label: "在线 Worker", min: 1, max: 50 },
    { key: "max_running_trials", label: "同时运行任务", min: 1, max: 20 },
    {
      key: "max_stored_bytes",
      label: "附件与证据预算（bytes）",
      min: 1,
      max: 107374182400,
    },
    { key: "max_experiments", label: "未删除实验数", min: 1, max: 10000 },
    {
      key: "max_catalog_resources",
      label: "每类元数据数量",
      min: 1,
      max: 10000,
    },
    {
      key: "max_published_versions",
      label: "已发布版本数",
      min: 1,
      max: 100000,
    },
    {
      key: "max_trial_executions",
      label: "累计任务尝试",
      min: 1,
      max: 1000000,
    },
    { key: "max_evaluation_jobs", label: "累计评测作业", min: 1, max: 1000000 },
    {
      key: "max_python_seconds",
      label: "累计 Python 预留秒数",
      min: 30,
      max: 2592000,
    },
  ];
export function ResourceAdminPanel({ accounts }: { accounts: CurrentUser[] }) {
  const { workspace_id } = useWorkspace();
  const cache = useQueryClient();
  const [target, setTarget] = useState("");
  const [limits, setLimits] = useState<Limits>();
  const [reason, setReason] = useState("");
  const current = useQuery({
    queryKey: [workspace_id, "resource-admin", target],
    enabled: !!target,
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/admin/workspaces/{workspaceId}/resource-limits", {
          params: { path: { workspaceId: target } },
          signal,
        }),
      ),
  });
  useEffect(() => {
    if (current.data) setLimits(current.data.limits);
  }, [current.data]);
  const save = useMutation({
    mutationFn: () => {
      if (!limits) throw new Error("请先选择用户。");
      return request(
        api.POST("/api/v1/admin/workspaces/{workspaceId}/resource-limits", {
          params: { path: { workspaceId: target } },
          body: { limits, reason },
        }),
      );
    },
    onSuccess: async () => {
      setReason("");
      await cache.invalidateQueries({
        queryKey: [workspace_id, "resource-admin", target],
      });
      await cache.invalidateQueries({
        queryKey: [workspace_id, "resource-limits"],
      });
    },
  });
  return (
    <section className="panel">
      <h2>资源上限</h2>
      <p className="hint">
        全平台仍受 50 个在线 Worker、20 个运行 Trial
        的上限约束。提高累计预算会恢复等待中的任务，不重置已使用的计数。
      </p>
      <label>
        选择用户空间
        <select
          value={target}
          disabled={save.isPending}
          onChange={(event) => {
            setLimits(undefined);
            setTarget(event.target.value);
            save.reset();
          }}
        >
          <option value="">选择当前账户列表中的用户</option>
          {accounts.map((account) => (
            <option key={account.workspace_id} value={account.workspace_id}>
              {account.email}
            </option>
          ))}
        </select>
      </label>
      <ErrorNotice error={current.error ?? save.error} />
      {current.isPending && target ? (
        <Loading />
      ) : (
        limits && (
          <form
            onSubmit={(event) => {
              event.preventDefault();
              save.mutate();
            }}
          >
            <fieldset
              disabled={save.isPending}
              className="model-form resource-fields"
            >
              <legend>空间配额</legend>
              {fields.map((field) => (
                <label key={field.key}>
                  {field.label}
                  <input
                    required
                    type="number"
                    step={1}
                    min={field.min}
                    max={field.max}
                    value={limits[field.key]}
                    onChange={(event) =>
                      setLimits({
                        ...limits,
                        [field.key]: Number(event.target.value),
                      })
                    }
                  />
                </label>
              ))}
            </fieldset>
            <label>
              调整说明
              <textarea
                required
                maxLength={500}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              />
            </label>
            {save.isSuccess && (
              <p role="status">资源限制已更新，等待任务可继续调度。</p>
            )}
            <button
              className="primary"
              disabled={save.isPending || !reason.trim()}
            >
              保存资源上限
            </button>
          </form>
        )
      )}
    </section>
  );
}
