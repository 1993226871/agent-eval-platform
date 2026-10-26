import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { api, request } from "../../api/client";
import { ErrorNotice, Loading, PageHeading } from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";

const actions: Record<string, string> = {
  ACCOUNT_CREATED: "账户建立",
  ACCOUNT_PROVISIONED: "管理员建号",
  CATALOG_CREATED: "创建资源",
  DRAFT_UPDATED: "更新草稿",
  VERSION_PUBLISHED: "发布版本",
  RESOURCE_ARCHIVED: "归档资源",
  AGENT_REGISTERED: "注册 Agent",
  CREDENTIAL_ISSUED: "签发凭证",
  CREDENTIAL_REVOKED: "撤销凭证",
  EXPERIMENT_STARTED: "启动实验",
  EXPERIMENT_CANCELLED: "取消实验",
  EXPERIMENT_DELETION_REQUESTED: "请求删除实验",
  EXPERIMENT_DELETED: "完成历史清理",
  QUOTA_ADJUSTED: "调整累计额度",
  MODEL_CREATED: "添加模型",
  MODEL_DISABLED: "停用模型",
  MODEL_RECONCILED: "模型费用对账",
  MODEL_MASTER_KEY_ROTATED: "模型主密钥轮换",
  RESOURCE_LIMITS_CONFIGURED: "调整资源限制",
};
export function AuditPage() {
  const { workspace_id } = useWorkspace();
  const [history, setHistory] = useState<(string | undefined)[]>([undefined]);
  const cursor = history[history.length - 1];
  const records = useQuery({
    queryKey: [workspace_id, "audit", cursor],
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/audit-events", {
          params: { query: { cursor, limit: 25 } },
          signal,
        }),
      ),
    gcTime: 0,
  });
  return (
    <>
      <PageHeading
        title="操作记录"
        description="仅记录动作、对象标识、操作者与时间，保留不可修改的操作轨迹。"
      />
      <ErrorNotice error={records.error} />
      {records.isPending ? (
        <Loading />
      ) : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>时间</th>
                <th>动作</th>
                <th>对象</th>
                <th>操作者 / 来源</th>
              </tr>
            </thead>
            <tbody>
              {records.data?.items.map((event) => (
                <tr key={event.id}>
                  <td>{new Date(event.created_at).toLocaleString("zh-CN")}</td>
                  <td>{actions[event.action] ?? event.action}</td>
                  <td>
                    {[
                      "EXPERIMENT_DELETION_REQUESTED",
                      "EXPERIMENT_DELETED",
                    ].includes(event.action) ? (
                      <Link to={"/workspace/deletions/" + event.entity_id}>
                        {event.entity_id}
                      </Link>
                    ) : (
                      <code>{event.entity_id}</code>
                    )}
                  </td>
                  <td>
                    <code>{event.actor_user_id ?? "系统"}</code>
                    <small>{event.source}</small>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="pagination">
        <button
          disabled={history.length < 2}
          onClick={() => setHistory(history.slice(0, -1))}
        >
          上一页
        </button>
        <button
          disabled={!records.data?.next_cursor}
          onClick={() =>
            setHistory([...history, records.data?.next_cursor ?? undefined])
          }
        >
          下一页
        </button>
      </div>
    </>
  );
}
