import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import {
  Dialog,
  Empty,
  ErrorNotice,
  Loading,
  PageHeading,
} from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";
import { CredentialPanel } from "./CredentialPanel";

export function AgentsPage() {
  const { workspace_id } = useWorkspace();
  const cache = useQueryClient();
  const [selected, setSelected] = useState("");
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [version, setVersion] = useState("v1");
  const agents = useQuery({
    queryKey: [workspace_id, "agents"],
    queryFn: () => request(api.GET("/api/v1/agents")),
  });
  const [offset, setOffset] = useState(0);
  const credentials = useQuery({
    queryKey: [workspace_id, "credentials", offset],
    queryFn: () =>
      request(
        api.GET("/api/v1/worker-credentials", {
          params: { query: { offset, limit: 25 } },
        }),
      ),
  });
  const create = useMutation({
    mutationFn: () =>
      request(
        api.POST("/api/v1/agents", {
          body: { name: name.trim(), version: version.trim() },
        }),
      ),
    onSuccess: async (agent) => {
      setSelected(agent.id);
      setOpen(false);
      setName("");
      await cache.invalidateQueries({ queryKey: [workspace_id, "agents"] });
    },
  });
  const selectedAgent = agents.data?.find((item) => item.id === selected);
  return (
    <>
      <PageHeading
        title="Agent 接入"
        description="注册自己的 Agent，让 Java 或 Python SDK Worker 接收平台任务并上报执行证据。"
      >
        <button className="primary" onClick={() => setOpen(true)}>
          注册 Agent
        </button>
      </PageHeading>
      <ErrorNotice error={agents.error ?? credentials.error} />
      {agents.isPending ? (
        <Loading />
      ) : (
        <div className="catalog-layout">
          <section className="resource-list" aria-label="Agent 列表">
            {agents.data?.length ? (
              agents.data.map((agent) => (
                <button
                  key={agent.id}
                  className={
                    agent.id === selected ? "resource selected" : "resource"
                  }
                  onClick={() => setSelected(agent.id)}
                >
                  <strong>{agent.name}</strong>
                  <span>{agent.version}</span>
                </button>
              ))
            ) : (
              <Empty title="尚无 Agent">
                注册后创建凭证，再启动自己的 Worker。
              </Empty>
            )}
          </section>
          {selectedAgent ? (
            <section className="editor-pane">
              <h2>{selectedAgent.name}</h2>
              <p>版本：{selectedAgent.version}</p>
              <p>
                Agent ID：<code>{selectedAgent.id}</code>
              </p>
              <p className="hint">
                将平台地址、Agent ID 和接入密钥传给 SDK。Worker
                连接后会长轮询领取任务，请确保自己的运行环境可以访问平台。
              </p>
              <CredentialPanel
                key={selectedAgent.id}
                agentId={selectedAgent.id}
                agentVersion={selectedAgent.version}
                credentials={credentials.data ?? []}
                onIssue={async () => {
                  const issued = await request(
                    api.POST("/api/v1/worker-credentials", {
                      body: { agent_ids: [selectedAgent.id] },
                    }),
                  );
                  await cache.invalidateQueries({
                    queryKey: [workspace_id, "credentials"],
                  });
                  return issued;
                }}
                onRevoke={async (id) => {
                  await request(
                    api.DELETE("/api/v1/worker-credentials/{id}", {
                      params: { path: { id } },
                    }),
                  );
                  await cache.invalidateQueries({
                    queryKey: [workspace_id, "credentials"],
                  });
                }}
              />
              <div className="pagination">
                <button
                  disabled={!offset}
                  onClick={() => setOffset(Math.max(0, offset - 25))}
                >
                  上一页凭证
                </button>
                <button
                  disabled={credentials.data?.length !== 25}
                  onClick={() => setOffset(offset + 25)}
                >
                  下一页凭证
                </button>
              </div>
            </section>
          ) : (
            <Empty title="选择一个 Agent">查看接入信息和管理凭证。</Empty>
          )}
        </div>
      )}
      <Dialog open={open} title="注册 Agent" onClose={() => setOpen(false)}>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            create.mutate();
          }}
        >
          <label>
            Agent 名称
            <input
              required
              maxLength={200}
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </label>
          <label>
            Agent 版本
            <input
              required
              maxLength={100}
              value={version}
              onChange={(event) => setVersion(event.target.value)}
            />
          </label>
          <ErrorNotice error={create.error} />
          <button
            className="primary"
            disabled={create.isPending || !name.trim() || !version.trim()}
          >
            注册
          </button>
        </form>
      </Dialog>
    </>
  );
}
