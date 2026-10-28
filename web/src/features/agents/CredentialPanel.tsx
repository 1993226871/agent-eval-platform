import { useState } from "react";
import type { Credential, Schema } from "../../api/types";
import { Dialog, ErrorNotice, Badge } from "../../components/ui";

type Props = {
  agentId: string;
  agentVersion: string;
  credentials: Credential[];
  onIssue: () => Promise<Schema<"IssuedCredential">>;
  onRevoke: (id: string) => Promise<unknown>;
};
export function CredentialPanel({
  agentId,
  agentVersion,
  credentials,
  onIssue,
  onRevoke,
}: Props) {
  const [issued, setIssued] = useState<Schema<"IssuedCredential">>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  async function issue() {
    setError(undefined);
    setBusy(true);
    try {
      setIssued(await onIssue());
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }
  const scoped = credentials.filter((credential) =>
    credential.agent_ids.includes(agentId),
  );
  const configuration = issued
    ? JSON.stringify(
        {
          platform_url: window.location.origin,
          agent_id: agentId,
          agent_version: agentVersion,
          api_key: issued.secret,
        },
        null,
        2,
      )
    : "";
  return (
    <section className="credential-panel">
      <div className="section-heading">
        <h2>接入凭证</h2>
        <button className="primary" disabled={busy} onClick={issue}>
          创建接入凭证
        </button>
      </div>
      <p className="hint">凭证仅授权当前 Agent，SDK 使用它连接平台。</p>
      <ErrorNotice error={error} />
      {scoped.length === 0 ? (
        <p>尚未创建接入凭证。</p>
      ) : (
        <ul className="credential-list">
          {scoped.map((credential) => (
            <li key={credential.id}>
              <span>
                <code>{credential.id}</code>
                <small>
                  {new Date(credential.created_at).toLocaleString("zh-CN")}
                </small>
              </span>
              <Badge tone={credential.revoked ? "neutral" : "success"}>
                {credential.revoked ? "已撤销" : "可用"}
              </Badge>
              {!credential.revoked && (
                <button
                  className="danger subtle"
                  disabled={busy}
                  onClick={async () => {
                    setBusy(true);
                    setError(undefined);
                    try {
                      await onRevoke(credential.id);
                    } catch (failure) {
                      setError(failure);
                    } finally {
                      setBusy(false);
                    }
                  }}
                >
                  撤销
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      <Dialog
        open={!!issued}
        title="保存接入密钥"
        onClose={() => setIssued(undefined)}
      >
        {issued && (
          <>
            <p>
              密钥只在本次创建后显示。请复制到自己的 SDK
              配置，关闭后无法再次查看。
            </p>
            <output className="secret-output" aria-label="本次接入密钥">
              {issued?.secret}
            </output>
            <h3>通过配置文件连接 Python Agent</h3>
            <p>
              将以下内容保存为自己 Agent 项目中的{" "}
              <code>agent-eval.local.json</code>，无需在终端设置环境变量。
            </p>
            <pre aria-label="Python SDK 配置文件">
              <code>{configuration}</code>
            </pre>
            <p>
              在 Agent 项目的 <code>.gitignore</code> 中加入{" "}
              <code>agent-eval.local.json</code> 和 <code>.agent-eval/</code>
              ，配置文件包含接入密钥，请只保存在自己的运行环境。
            </p>
            <p>
              配置文件接入需要 Python SDK 0.1.1 或更高版本。请在自己的 Agent
              Python 环境执行{" "}
              <code>python -m pip install --upgrade veyraeval-sdk==0.1.1</code>
              ， 再在 Agent 项目中使用：
            </p>
            <pre>
              <code>{`from threading import Event
from agent_eval import EvaluationWorker

worker = EvaluationWorker.from_config("agent-eval.local.json", handler=run_agent)
try:
    worker.start()
    Event().wait()
except KeyboardInterrupt:
    pass
finally:
    worker.stop()`}</code>
            </pre>
            <p>
              run_agent 是你的任务处理函数；Worker
              启动后需保持进程运行。本机自签名证书可在 JSON 中增加 ca_cert
              字段，填写平台 server.crt 的路径。远程平台填写 Agent
              运行环境能够访问的真实地址。
            </p>
            <div className="actions">
              <button
                onClick={async () => {
                  try {
                    await navigator.clipboard.writeText(configuration);
                  } catch {
                    setError(new Error("无法复制，请手动选择并保存配置。"));
                  }
                }}
              >
                复制配置
              </button>
              <button
                onClick={async () => {
                  try {
                    await navigator.clipboard.writeText(issued?.secret ?? "");
                  } catch {
                    setError(new Error("无法复制，请手动选择并保存密钥。"));
                  }
                }}
              >
                复制密钥
              </button>
              <button className="primary" onClick={() => setIssued(undefined)}>
                我已保存
              </button>
            </div>
          </>
        )}
      </Dialog>
    </section>
  );
}
