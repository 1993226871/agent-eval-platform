import { useState } from "react";
import type { Credential, Schema } from "../../api/types";
import { Dialog, ErrorNotice, Badge } from "../../components/ui";

type Props = {
  agentId: string;
  credentials: Credential[];
  onIssue: () => Promise<Schema<"IssuedCredential">>;
  onRevoke: (id: string) => Promise<unknown>;
};
export function CredentialPanel({
  agentId,
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
        <p>
          密钥只在本次创建后显示。请复制到自己的 SDK 配置，关闭后无法再次查看。
        </p>
        <output className="secret-output" aria-label="本次接入密钥">
          {issued?.secret}
        </output>
        <div className="actions">
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
      </Dialog>
    </section>
  );
}
