import { useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import type { Schema } from "../../api/types";
import {
  Badge,
  ErrorNotice,
  JsonBlock,
  PageHeading,
} from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";
import { integerCredits } from "../models/credits";
import { ResourceAdminPanel } from "./ResourceAdminPanel";
import { ModelConnectionForm } from "../models/ModelConnectionForm";
import { formatCny, parseCny } from "../models/money";
import { ModelTestResult } from "./ModelTestResult";

function accountAmount(
  wallet: Schema<"Wallet">,
  field: "available" | "consumed" | "reserved" | "granted",
) {
  return formatCny(wallet[field]);
}

export function AdminPage() {
  const { workspace_id } = useWorkspace();
  const cache = useQueryClient();
  const [tab, setTab] = useState<
    "accounts" | "models" | "reconcile" | "resources"
  >("accounts");
  const [offset, setOffset] = useState(0);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [workspace, setWorkspace] = useState("");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [adjustKey, setAdjustKey] = useState(crypto.randomUUID());
  const adjustForm = useRef<HTMLFormElement>(null);
  const accounts = useQuery({
    queryKey: [workspace_id, "admin-accounts", offset],
    refetchInterval: 10000,
    queryFn: () =>
      request(
        api.GET("/api/v1/admin/users", {
          params: { query: { offset, limit: 25 } },
        }),
      ),
  });
  const models = useQuery({
    queryKey: [workspace_id, "admin-models"],
    enabled: tab === "models",
    queryFn: () => request(api.GET("/api/v1/admin/models")),
  });
  const pending = useQuery({
    queryKey: [workspace_id, "pending-calls"],
    enabled: tab === "reconcile",
    queryFn: () =>
      request(
        api.GET("/api/v1/admin/model-calls", {
          params: { query: { limit: 100 } },
        }),
      ),
  });
  const refresh = () => cache.invalidateQueries({ queryKey: [workspace_id] });
  const create = useMutation({
    mutationFn: () =>
      request(
        api.POST("/api/v1/admin/users", {
          body: { email, temporary_password: password },
        }),
      ),
    onSuccess: async () => {
      setEmail("");
      setPassword("");
      await refresh();
    },
  });
  const adjust = useMutation({
    mutationFn: () => {
      parseCny(amount);
      return request(
        api.POST("/api/v1/admin/workspaces/{workspaceId}/quota/adjustments", {
          params: { path: { workspaceId: workspace } },
          body: { amount, reason, idempotency_key: adjustKey },
        }),
      );
    },
    onSuccess: async () => {
      setAdjustKey(crypto.randomUUID());
      setAmount("");
      setReason("");
      await refresh();
    },
  });
  const [disableReason, setDisableReason] = useState("");
  const testCalls = useRef(new Map<string, string>());
  const [testResult, setTestResult] = useState<{
    modelName: string;
    billingMode: Schema<"ModelProfile">["billing_mode"];
    result: Schema<"ModelCallResolution">;
  }>();
  const testModel = async (profile: Schema<"ModelProfile">) => {
    const profileId = profile.id;
    setTestResult(undefined);
    let callId = testCalls.current.get(profileId);
    if (!callId) {
      callId = crypto.randomUUID();
      testCalls.current.set(profileId, callId);
    }
    const result = await request(
      api.POST("/api/v1/admin/models/{profileId}/test", {
        params: { path: { profileId } },
        body: { call_id: callId },
      }),
    );
    if (["SUCCEEDED", "FAILED_UNSENT", "RECONCILED"].includes(result.status))
      testCalls.current.delete(profileId);
    setTestResult({
      modelName: profile.name,
      billingMode: profile.billing_mode,
      result,
    });
    return result;
  };
  const modelAction = useMutation({
    mutationFn: (operation: () => Promise<unknown>) => operation(),
    onSuccess: refresh,
  });
  const [callId, setCallId] = useState("");
  const [input, setInput] = useState("");
  const [output, setOutput] = useState("");
  const [reconcileReason, setReconcileReason] = useState("");
  const [reconcileKey, setReconcileKey] = useState(crypto.randomUUID());
  const reconcile = useMutation({
    mutationFn: () => {
      if (integerCredits(input) < 0n || integerCredits(output) < 0n)
        throw new Error("Token 用量不能为负数。");
      return request(
        api.POST("/api/v1/admin/model-calls/{callId}/reconciliation", {
          params: { path: { callId } },
          body: {
            input_tokens: input,
            output_tokens: output,
            reason: reconcileReason,
            idempotency_key: reconcileKey,
          },
        }),
      );
    },
    onSuccess: async () => {
      setReconcileKey(crypto.randomUUID());
      setInput("");
      setOutput("");
      setReconcileReason("");
      await refresh();
    },
  });
  return (
    <>
      <PageHeading
        title="管理后台"
        description="管理账户、累计额度和平台评审模型。管理员操作保留审计记录。"
      />
      <nav className="tabs" aria-label="管理分类">
        {(
          [
            ["accounts", "账户与额度"],
            ["models", "模型配置"],
            ["reconcile", "待对账调用"],
            ["resources", "资源上限"],
          ] as const
        ).map(([value, label]) => (
          <button
            key={value}
            aria-pressed={tab === value}
            onClick={() => setTab(value)}
          >
            {label}
          </button>
        ))}
      </nav>
      {tab === "accounts" && (
        <>
          <ErrorNotice error={accounts.error} />
          <div className="two-column">
            <section className="panel">
              <h2>创建个人账户</h2>
              <form
                onSubmit={(event) => {
                  event.preventDefault();
                  create.mutate();
                }}
              >
                <label>
                  新账户邮箱
                  <input
                    type="email"
                    required
                    value={email}
                    onChange={(event) => setEmail(event.target.value)}
                  />
                </label>
                <label>
                  临时密码
                  <input
                    type="password"
                    autoComplete="new-password"
                    minLength={12}
                    required
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                  />
                </label>
                <p className="hint">
                  新账户首次登录必须修改密码，默认获得 ¥5.00 累计平台评审额度。
                </p>
                <ErrorNotice error={create.error} />
                {create.isSuccess && <p role="status">账户已创建。</p>}
                <button className="primary" disabled={create.isPending}>
                  创建账户
                </button>
              </form>
            </section>
            <section className="panel">
              <h2>调整累计额度</h2>
              <form
                ref={adjustForm}
                onSubmit={(event) => {
                  event.preventDefault();
                  adjust.mutate();
                }}
              >
                <label>
                  用户工作空间
                  <select
                    required
                    value={workspace}
                    onChange={(event) => {
                      setWorkspace(event.target.value);
                      setAdjustKey(crypto.randomUUID());
                    }}
                  >
                    <option value="">选择当前页的用户</option>
                    {accounts.data?.map((user) => (
                      <option key={user.workspace_id} value={user.workspace_id}>
                        {user.email}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  调整金额（元）
                  <input
                    required
                    inputMode="decimal"
                    pattern="-?[0-9]+(\.[0-9]{1,6})?"
                    value={amount}
                    onChange={(event) => {
                      setAmount(event.target.value);
                      setAdjustKey(crypto.randomUUID());
                    }}
                  />
                </label>
                <label>
                  调整原因
                  <textarea
                    required
                    maxLength={1000}
                    value={reason}
                    onChange={(event) => {
                      setReason(event.target.value);
                      setAdjustKey(crypto.randomUUID());
                    }}
                  />
                </label>
                <p className="hint">
                  正数发放，负数调减；不能调减已消费或预留的额度。
                </p>
                <ErrorNotice error={adjust.error} />
                {adjust.data && (
                  <p role="status">
                    调整后可用：{formatCny(adjust.data.available)}
                  </p>
                )}
                <button className="primary" disabled={adjust.isPending}>
                  提交额度调整
                </button>
              </form>
            </section>
          </div>
          <section className="panel">
            <h2>账户列表</h2>
            <p className="hint">
              按页查看所有用户的额度，每10秒刷新；调整额度后自动更新。金额统一以人民币展示。
            </p>
            <button
              onClick={() => accounts.refetch()}
              disabled={accounts.isFetching}
            >
              刷新额度
            </button>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>邮箱</th>
                    <th>账户状态</th>
                    <th>可用额度</th>
                    <th>已使用</th>
                    <th>预留中</th>
                    <th>累计发放</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {accounts.data?.map((user) => (
                    <tr key={user.user_id}>
                      <td>
                        <div>{user.email}</div>
                        <details>
                          <summary>工作空间</summary>
                          <code>{user.workspace_id}</code>
                        </details>
                      </td>
                      <td>
                        {user.admin ? "管理员" : "个人账户"}
                        {user.must_change_password && " · 待改密"}
                      </td>
                      {(
                        [
                          "available",
                          "consumed",
                          "reserved",
                          "granted",
                        ] as const
                      ).map((field) => (
                        <td key={field}>{accountAmount(user.quota, field)}</td>
                      ))}
                      <td>
                        <button
                          onClick={() => {
                            setWorkspace(user.workspace_id);
                            setAmount("");
                            setReason("");
                            setAdjustKey(crypto.randomUUID());
                            adjustForm.current?.scrollIntoView?.({
                              behavior: "smooth",
                              block: "center",
                            });
                          }}
                        >
                          调整额度
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="pagination">
              <button
                disabled={!offset}
                onClick={() => setOffset(Math.max(0, offset - 25))}
              >
                上一页
              </button>
              <button
                disabled={accounts.data?.length !== 25}
                onClick={() => setOffset(offset + 25)}
              >
                下一页
              </button>
            </div>
          </section>
        </>
      )}
      {tab === "models" && (
        <>
          <ErrorNotice error={models.error ?? modelAction.error} />
          <p className="hint">
            此处配置平台提供的模型，测试平台模型会使用管理员本人的平台额度；自带
            Key 由服务商计费。使用自己的 API Key 且不扣平台额度，请到
            <Link to="/workspace/models">评审模型</Link>接入。
          </p>
          <p className="hint">
            连通性测试失败或待对账时，再次点击会查询原调用。收到明确结果后，下次点击才创建新的收费测试。
          </p>
          {testResult && <ModelTestResult {...testResult} />}
          <section className="panel">
            <h2>已配置模型</h2>
            <label>
              停用原因
              <input
                value={disableReason}
                maxLength={1000}
                onChange={(event) => setDisableReason(event.target.value)}
              />
            </label>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>模型</th>
                    <th>计费方式</th>
                    <th>状态</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {models.data?.map((item) => (
                    <tr key={item.id}>
                      <td>
                        {item.name}
                        <small>{item.model}</small>
                      </td>
                      <td>
                        {item.billing_mode === "BYOK"
                          ? "本人 Key · 服务商计费"
                          : "平台人民币额度"}
                      </td>
                      <td>
                        <Badge>{item.enabled ? "可用" : "已停用"}</Badge>
                      </td>
                      <td>
                        <button
                          disabled={!item.enabled || modelAction.isPending}
                          onClick={() =>
                            modelAction.mutate(() => testModel(item))
                          }
                        >
                          {item.billing_mode === "BYOK"
                            ? "测试（服务商计费）"
                            : "测试（消耗本人额度）"}
                        </button>
                        <button
                          className="danger subtle"
                          disabled={
                            !item.enabled ||
                            !disableReason.trim() ||
                            modelAction.isPending
                          }
                          onClick={() =>
                            modelAction.mutate(() =>
                              request(
                                api.POST(
                                  "/api/v1/admin/models/{profileId}/disable",
                                  {
                                    params: { path: { profileId: item.id } },
                                    body: { reason: disableReason },
                                  },
                                ),
                              ),
                            )
                          }
                        >
                          停用
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {modelAction.isSuccess && <p role="status">操作已提交。</p>}
          </section>
          <section className="panel">
            <h2>添加平台模型</h2>
            <ModelConnectionForm mode="PLATFORM" onSaved={refresh} />
          </section>
        </>
      )}
      {tab === "reconcile" && (
        <>
          <ErrorNotice error={pending.error} />
          <section className="panel">
            <h2>待人工核验的模型调用</h2>
            <p>请先在服务商账单核实实际用量，再提交对账。未知用量保持预留。</p>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>调用</th>
                    <th>状态</th>
                    <th>预留金额</th>
                    <th>原因</th>
                  </tr>
                </thead>
                <tbody>
                  {pending.data?.map((call) => (
                    <tr key={call.call_id}>
                      <td>
                        <button
                          className="text-button"
                          onClick={() => {
                            setCallId(call.call_id);
                            setReconcileKey(crypto.randomUUID());
                          }}
                        >
                          {call.call_id}
                        </button>
                      </td>
                      <td>{call.status}</td>
                      <td>{formatCny(call.reserved_credits)}</td>
                      <td>{call.failure_code}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
          <section className="panel">
            <h2>提交对账</h2>
            <form
              onSubmit={(event) => {
                event.preventDefault();
                reconcile.mutate();
              }}
            >
              <label>
                调用 ID
                <input
                  required
                  value={callId}
                  onChange={(event) => {
                    setCallId(event.target.value);
                    setReconcileKey(crypto.randomUUID());
                  }}
                />
              </label>
              <label>
                已核实输入 Token
                <input
                  required
                  pattern="[0-9]+"
                  inputMode="numeric"
                  value={input}
                  onChange={(event) => {
                    setInput(event.target.value);
                    setReconcileKey(crypto.randomUUID());
                  }}
                />
              </label>
              <label>
                已核实输出 Token
                <input
                  required
                  pattern="[0-9]+"
                  inputMode="numeric"
                  value={output}
                  onChange={(event) => {
                    setOutput(event.target.value);
                    setReconcileKey(crypto.randomUUID());
                  }}
                />
              </label>
              <label>
                核验说明
                <textarea
                  required
                  value={reconcileReason}
                  onChange={(event) => {
                    setReconcileReason(event.target.value);
                    setReconcileKey(crypto.randomUUID());
                  }}
                />
              </label>
              <ErrorNotice error={reconcile.error} />
              {reconcile.data && <JsonBlock value={reconcile.data} />}
              <button className="primary" disabled={reconcile.isPending}>
                按已核实用量结算
              </button>
            </form>
          </section>
        </>
      )}
      {tab === "resources" && (
        <ResourceAdminPanel accounts={accounts.data ?? []} />
      )}
    </>
  );
}
