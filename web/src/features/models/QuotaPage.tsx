import { useQuery } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import { ErrorNotice, Loading, PageHeading } from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";
import { formatCredits } from "./credits";
export function QuotaPage() {
  const { workspace_id } = useWorkspace();
  const wallet = useQuery({
    queryKey: [workspace_id, "wallet"],
    queryFn: () => request(api.GET("/api/v1/quota")),
    refetchInterval: 10000,
  });
  const ledger = useQuery({
    queryKey: [workspace_id, "ledger"],
    queryFn: () =>
      request(
        api.GET("/api/v1/quota/ledger", { params: { query: { limit: 100 } } }),
      ),
    refetchInterval: 10000,
  });
  return (
    <>
      <PageHeading
        title="个人额度"
        description="额度由平台累计发放，消耗后不会按周期重置。所有数额以整数 credits 记账。"
      />
      <ErrorNotice error={wallet.error ?? ledger.error} />
      {wallet.isPending ? (
        <Loading />
      ) : (
        wallet.data && (
          <dl className="metrics">
            {(
              [
                ["可用", wallet.data.available],
                ["已消费", wallet.data.consumed],
                ["预留中", wallet.data.reserved],
                ["累计发放", wallet.data.granted],
              ] as const
            ).map(([label, value]) => (
              <div key={label}>
                <dt>{label}</dt>
                <dd>{formatCredits(value)}</dd>
              </div>
            ))}
          </dl>
        )
      )}
      <section className="panel">
        <h2>最近 100 条额度流水</h2>
        <p className="hint">
          预留用于覆盖模型调用上界，结算后释放差额；结果不确定的请求保留额度，待管理员对账。
        </p>
        {ledger.isPending ? (
          <Loading />
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>时间</th>
                  <th>类型</th>
                  <th>发放变化</th>
                  <th>消费变化</th>
                  <th>预留变化</th>
                  <th>原因</th>
                </tr>
              </thead>
              <tbody>
                {ledger.data?.map((entry) => (
                  <tr key={entry.id}>
                    <td>
                      {new Date(entry.created_at).toLocaleString("zh-CN")}
                    </td>
                    <td>{entry.type}</td>
                    <td>{formatCredits(entry.granted_delta)}</td>
                    <td>{formatCredits(entry.consumed_delta)}</td>
                    <td>{formatCredits(entry.reserved_delta)}</td>
                    <td>{entry.reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
