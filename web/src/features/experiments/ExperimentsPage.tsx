import { useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import type { Schema } from "../../api/types";
import {
  Badge,
  Dialog,
  Empty,
  ErrorNotice,
  Loading,
  PageHeading,
} from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";
import { useVersionOptions } from "../catalog/queries";
import { DeletionControl } from "./DeletionControl";
import { EstimateNotice, useExperimentEstimate } from "./estimate";

export function ExperimentsPage() {
  const { workspace_id } = useWorkspace();
  const cache = useQueryClient();
  const [offset, setOffset] = useState(0);
  const [requestedDeletion, setRequestedDeletion] = useState("");
  const experiments = useQuery({
    queryKey: [workspace_id, "experiments", offset],
    queryFn: () =>
      request(
        api.GET("/api/v1/experiment-summaries", {
          params: { query: { offset, limit: 25 } },
        }),
      ),
    refetchInterval: 10000,
  });
  const agents = useQuery({
    queryKey: [workspace_id, "agents"],
    queryFn: () => request(api.GET("/api/v1/agents")),
  });
  const datasets = useVersionOptions("DATASET");
  const suites = useVersionOptions("SUITE");
  const [open, setOpen] = useState(false);
  const [agentId, setAgentId] = useState("");
  const [dataset, setDataset] = useState("");
  const [suite, setSuite] = useState("");
  const [repeat, setRepeat] = useState(1);
  const [minutes, setMinutes] = useState(60);
  const [key, setKey] = useState("");
  const estimate = useExperimentEstimate(dataset, suite, repeat, open);
  const submission = useRef<{
    fingerprint: string;
    body: Schema<"StartExperiment">;
  } | null>(null);
  const [selected, setSelected] = useState("");
  const [cancelId, setCancelId] = useState("");
  const trials = useQuery({
    queryKey: [workspace_id, "trials", selected],
    enabled: !!selected,
    queryFn: () =>
      request(
        api.GET("/api/v1/experiments/{id}/trials", {
          params: { path: { id: selected } },
        }),
      ),
    refetchInterval: 10000,
  });
  const start = useMutation({
    mutationFn: () => {
      const agent = agents.data?.find((value) => value.id === agentId);
      if (!agent) throw new Error("请选择 Agent。");
      const fingerprint = JSON.stringify([
        agent.id,
        agent.version,
        dataset,
        suite,
        repeat,
        minutes,
        key,
      ]);
      if (submission.current?.fingerprint !== fingerprint) {
        submission.current = {
          fingerprint,
          body: {
            agent_id: agent.id,
            agent_version: agent.version,
            dataset_version_id: dataset,
            suite_version_id: suite,
            repeat_count: repeat,
            deadline: new Date(Date.now() + minutes * 60000).toISOString(),
            idempotency_key: crypto.randomUUID(),
          },
        };
      }
      return request(
        api.POST("/api/v1/experiments", {
          body: submission.current.body,
        }),
      );
    },
    onSuccess: async (value) => {
      setOpen(false);
      setSelected(value.id);
      await cache.invalidateQueries({
        queryKey: [workspace_id, "experiments"],
      });
    },
  });
  const cancel = useMutation({
    mutationFn: () =>
      request(
        api.POST("/api/v1/experiments/{id}/cancel", {
          params: { path: { id: cancelId } },
        }),
      ),
    onSuccess: async () => {
      setCancelId("");
      await cache.invalidateQueries({
        queryKey: [workspace_id, "experiments"],
      });
      await cache.invalidateQueries({ queryKey: [workspace_id, "trials"] });
    },
  });
  return (
    <>
      <PageHeading
        title="实验"
        description="用固定版本的任务集与评分规则集运行 Agent。平台排队，SDK Worker 执行并上报证据。"
      >
        <button
          className="primary"
          onClick={() => {
            setKey(crypto.randomUUID());
            start.reset();
            setOpen(true);
          }}
        >
          新建实验
        </button>
      </PageHeading>
      {requestedDeletion && (
        <p className="warning-notice">
          实验已停止访问，正在清理历史数据。
          <Link to={"/workspace/deletions/" + requestedDeletion}>
            查看删除进度
          </Link>
        </p>
      )}
      <ErrorNotice
        error={
          experiments.error ?? agents.error ?? datasets.error ?? suites.error
        }
      />
      {experiments.isPending ? (
        <Loading />
      ) : experiments.data?.length ? (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>实验 / Agent 版本</th>
                <th>执行状态</th>
                <th>重复次数</th>
                <th>截止时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {experiments.data.map((item) => (
                <tr key={item.id}>
                  <td>
                    <Link to={"/workspace/reports/" + item.id}>报告</Link>{" "}
                    <button
                      className="text-button"
                      onClick={() => setSelected(item.id)}
                    >
                      {item.id.slice(0, 8)}
                    </button>
                    <small>{item.agent_version}</small>
                  </td>
                  <td>
                    <Badge>{item.status}</Badge>
                  </td>
                  <td>{item.repeat_count}</td>
                  <td>{new Date(item.deadline).toLocaleString("zh-CN")}</td>
                  <td>
                    <button onClick={() => setSelected(item.id)}>
                      查看任务
                    </button>
                    <DeletionControl
                      experimentId={item.id}
                      onDelete={(key) =>
                        request(
                          api.DELETE("/api/v1/experiments/{id}", {
                            params: { path: { id: item.id } },
                            body: { idempotency_key: key },
                          }),
                        )
                      }
                      onRequested={(status) => {
                        setRequestedDeletion(status.experiment_id);
                        void cache.invalidateQueries({
                          queryKey: [workspace_id, "experiments"],
                        });
                      }}
                    />
                    {!["CANCELLED", "COMPLETED", "DONE", "DELETED"].includes(
                      item.status,
                    ) && (
                      <button
                        className="danger subtle"
                        onClick={() => setCancelId(item.id)}
                      >
                        取消
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty title="还没有实验">先发布任务集和套件，再创建实验。</Empty>
      )}
      <div className="pagination">
        <button
          disabled={!offset}
          onClick={() => setOffset(Math.max(0, offset - 25))}
        >
          上一页
        </button>
        <button
          disabled={experiments.data?.length !== 25}
          onClick={() => setOffset(offset + 25)}
        >
          下一页
        </button>
      </div>
      {selected && (
        <section className="panel">
          <h2>任务列表 · {selected.slice(0, 8)}</h2>
          <ErrorNotice error={trials.error} />
          {trials.isPending ? (
            <Loading />
          ) : (
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Trial ID</th>
                    <th>案例序号</th>
                    <th>执行状态</th>
                  </tr>
                </thead>
                <tbody>
                  {trials.data?.map((trial) => (
                    <tr key={trial.id}>
                      <td>
                        <code>{trial.id}</code>
                      </td>
                      <td>{trial.case_index + 1}</td>
                      <td>
                        <Badge>{trial.status}</Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
      <Dialog open={open} title="新建实验" onClose={() => setOpen(false)}>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            start.mutate();
          }}
        >
          <label>
            Agent
            <select
              required
              value={agentId}
              onChange={(event) => setAgentId(event.target.value)}
            >
              <option value="">选择 Agent</option>
              {agents.data?.map((agent) => (
                <option key={agent.id} value={agent.id}>
                  {agent.name} · {agent.version}
                </option>
              ))}
            </select>
          </label>
          <label>
            任务集版本
            <select
              required
              value={dataset}
              onChange={(event) => setDataset(event.target.value)}
            >
              <option value="">选择已发布版本</option>
              {datasets.data?.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.label}
                </option>
              ))}
            </select>
          </label>
          <label>
            评分规则集版本
            <select
              required
              value={suite}
              onChange={(event) => setSuite(event.target.value)}
            >
              <option value="">选择已发布版本</option>
              {suites.data?.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.label}
                </option>
              ))}
            </select>
          </label>
          <label>
            每个案例重复次数
            <input
              type="number"
              min={1}
              max={20}
              required
              value={repeat}
              onChange={(event) => setRepeat(Number(event.target.value))}
            />
          </label>
          <label>
            执行时限（分钟）
            <input
              type="number"
              min={1}
              max={10080}
              required
              value={minutes}
              onChange={(event) => setMinutes(Number(event.target.value))}
            />
          </label>
          <p className="hint">
            模型评审使用个人累计额度。没有在线 Worker 时，任务将等待领取。
          </p>
          <EstimateNotice value={estimate.data} error={estimate.error} />
          <ErrorNotice error={start.error} />
          <button
            className="primary"
            disabled={
              start.isPending ||
              !agentId ||
              !dataset ||
              !suite ||
              !estimate.data ||
              estimate.isFetching
            }
          >
            启动实验
          </button>
        </form>
      </Dialog>
      <Dialog
        open={!!cancelId}
        title="取消实验"
        onClose={() => setCancelId("")}
      >
        <p>排队和等待评测的任务会停止。已发送的模型请求按实际用量结算。</p>
        <ErrorNotice error={cancel.error} />
        <button
          className="danger"
          disabled={cancel.isPending}
          onClick={() => cancel.mutate()}
        >
          确认取消
        </button>
      </Dialog>
    </>
  );
}
