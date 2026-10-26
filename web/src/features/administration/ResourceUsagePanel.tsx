import { useQuery } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import { ErrorNotice, Loading } from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";

export function formatBytes(value: number) {
  if (value >= 1024 ** 3)
    return `${Number((value / 1024 ** 3).toFixed(2))} GiB`;
  if (value >= 1024 ** 2)
    return `${Number((value / 1024 ** 2).toFixed(2))} MiB`;
  if (value >= 1024) return `${Number((value / 1024).toFixed(2))} KiB`;
  return `${value} B`;
}
export function ResourceUsagePanel() {
  const { workspace_id } = useWorkspace();
  const resources = useQuery({
    queryKey: [workspace_id, "resource-limits"],
    queryFn: ({ signal }) =>
      request(api.GET("/api/v1/resource-limits", { signal })),
    refetchInterval: 10000,
  });
  const value = resources.data;
  return (
    <section className="panel">
      <h2>执行与存储预算</h2>
      <p className="hint">
        执行预算与模型 credits
        分开计算。累计次数不会因删除实验而退回；存储占用在清理成功后释放。
      </p>
      <ErrorNotice error={resources.error} />
      {resources.isPending ? (
        <Loading />
      ) : (
        value && (
          <>
            <dl className="limits-definition">
              <div>
                <dt>累计任务尝试</dt>
                <dd>
                  {value.usage.trial_executions} /{" "}
                  {value.limits.max_trial_executions} 次
                </dd>
              </div>
              <div>
                <dt>累计评测作业</dt>
                <dd>
                  {value.usage.evaluation_jobs} /{" "}
                  {value.limits.max_evaluation_jobs} 次
                </dd>
              </div>
              <div>
                <dt>Python 执行预算</dt>
                <dd>
                  {value.usage.python_reserved_seconds} /{" "}
                  {value.limits.max_python_seconds} 秒
                </dd>
              </div>
              <div>
                <dt>附件与证据存储</dt>
                <dd>
                  {formatBytes(
                    value.usage.artifact_bytes + value.usage.evidence_bytes,
                  )}{" "}
                  / {formatBytes(value.limits.max_stored_bytes)}
                </dd>
              </div>
              <div>
                <dt>在线 Worker / 运行任务上限</dt>
                <dd>
                  {value.limits.max_online_workers} /{" "}
                  {value.limits.max_running_trials}
                </dd>
              </div>
              <div>
                <dt>实验 / 各类资源 / 已发布版本上限</dt>
                <dd>
                  {value.limits.max_experiments} /{" "}
                  {value.limits.max_catalog_resources} /{" "}
                  {value.limits.max_published_versions}
                </dd>
              </div>
            </dl>
            <p className="hint">
              每个 Python 作业按 30
              秒上界预留，同一作业恢复不重复预留；这不是实际耗时计费。存储预算包括附件预留、轨迹、固定证据和投影预留。资源不足时请联系管理员调整上限，或删除不再需要的历史实验释放存储。
            </p>
          </>
        )
      )}
    </section>
  );
}
export function resourceWaitMessage(reason: string) {
  const labels: Record<string, string> = {
    PYTHON_RESOURCE_LIMIT: "Python 执行预算不足",
    EVALUATION_JOB_LIMIT: "累计评测作业预算不足",
    STORAGE_LIMIT: "证据存储预算不足",
    EVIDENCE_BYTE_LIMIT: "单次任务证据过大，请减少采集内容或使用附件",
    WAITING_QUOTA: "模型评审 credits 不足",
  };
  return labels[reason] ?? reason;
}
