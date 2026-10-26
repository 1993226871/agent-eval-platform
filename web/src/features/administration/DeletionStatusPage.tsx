import { Link, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import { Badge, ErrorNotice, Loading, PageHeading } from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";

export function DeletionStatusPage() {
  const { id = "" } = useParams();
  const { workspace_id } = useWorkspace();
  const status = useQuery({
    queryKey: [workspace_id, "deletion", id],
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/experiment-deletions/{id}", {
          params: { path: { id } },
          signal,
        }),
      ),
    refetchInterval: (query) =>
      query.state.data?.status === "DELETED" ? false : 5000,
  });
  return (
    <>
      <PageHeading title="实验删除进度" description={id}>
        <Link to="/workspace/experiments">返回实验</Link>
      </PageHeading>
      <ErrorNotice error={status.error} />
      {status.isPending ? (
        <Loading />
      ) : (
        status.data && (
          <section className="panel">
            <Badge
              tone={status.data.status === "DELETED" ? "success" : "warning"}
            >
              {status.data.status === "DELETED" ? "历史数据已删除" : "正在清理"}
            </Badge>
            <p>此实验的数据已不可访问，已发生的模型费用和额度账本会保留。</p>
            <p>
              提交时间：
              {new Date(status.data.requested_at).toLocaleString("zh-CN")}
            </p>
            {status.data.completed_at ? (
              <p>
                完成时间：
                {new Date(status.data.completed_at).toLocaleString("zh-CN")}
              </p>
            ) : (
              <p>
                下一次清理检查：
                {new Date(status.data.next_cleanup_at).toLocaleString("zh-CN")}
              </p>
            )}
            {status.data.failure_count > 0 && (
              <p className="warning-notice">
                清理尚未完成，系统会继续重试。已记录 {status.data.failure_count}{" "}
                次未完成尝试。
              </p>
            )}
            <button
              onClick={() => status.refetch()}
              disabled={status.isFetching}
            >
              刷新进度
            </button>
          </section>
        )
      )}
    </>
  );
}
