import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import { ErrorNotice, PageHeading } from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";
import { ModelConnectionForm } from "./ModelConnectionForm";
import { formatCny } from "./money";
import { integerCredits } from "./credits";

export function MyModelsPage() {
  const { workspace_id } = useWorkspace();
  const cache = useQueryClient();
  const refresh = () => cache.invalidateQueries({ queryKey: [workspace_id] });
  const models = useQuery({
    queryKey: [workspace_id, "models"],
    queryFn: () => request(api.GET("/api/v1/models")),
  });
  const disable = useMutation({
    mutationFn: (id: string) =>
      request(
        api.POST("/api/v1/models/connections/{id}/disable", {
          params: { path: { id } },
        }),
      ),
    onSuccess: refresh,
  });
  return (
    <>
      <PageHeading
        title="评审模型"
        description="选择平台模型，或接入自己的 API Key。自带 Key 不受平台金额额度限制，仍遵循请求大小、并发和超时限制。"
      />
      <section className="panel">
        <h2>接入我的模型</h2>
        <ModelConnectionForm mode="BYOK" onSaved={refresh} />
      </section>
      <section className="panel">
        <h2>可用模型</h2>
        <ErrorNotice error={models.error ?? disable.error} />
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>模型</th>
                <th>计费方式</th>
                <th>平台标准单价（每百万 Token）</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {models.data?.map((model) => (
                <tr key={model.id}>
                  <td>{model.name}</td>
                  <td>
                    {model.billing_mode === "BYOK"
                      ? "我的 API Key · 服务商计费"
                      : model.billing_mode === "PLATFORM"
                        ? "平台提供 · 扣平台人民币额度"
                        : "历史 credits 模型"}
                  </td>
                  <td>
                    {model.billing_mode === "PLATFORM"
                      ? `输入 ${formatCny((integerCredits(model.price.input_rate) * 1000000n) / integerCredits(model.price.denominator))} / 输出 ${formatCny((integerCredits(model.price.output_rate) * 1000000n) / integerCredits(model.price.denominator))}`
                      : "不适用"}
                  </td>
                  <td>
                    {model.billing_mode === "BYOK" && (
                      <button
                        disabled={disable.isPending}
                        onClick={() => disable.mutate(model.id)}
                      >
                        停用我的模型
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="hint">
          平台标准价采用已核验的官方峰时、缓存未命中人民币价格，固定到模型价格版本；缓存和闲时折扣未计入平台额度结算，与服务商最终账单可能不同。自带
          Key 完全按你与服务商的约定计费。
        </p>
      </section>
    </>
  );
}
