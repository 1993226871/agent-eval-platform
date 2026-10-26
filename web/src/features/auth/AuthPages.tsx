import { useState } from "react";
import { Navigate } from "react-router-dom";
import { z } from "zod";
import { useAuth } from "./AuthProvider";
import { ErrorNotice, Loading } from "../../components/ui";

export function LoginPage() {
  const auth = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  if (auth.user === undefined) return <Loading />;
  if (auth.user)
    return (
      <Navigate
        to={
          auth.user.must_change_password
            ? "/change-password"
            : "/workspace/experiments"
        }
        replace
      />
    );
  return (
    <div className="auth-page">
      <div className="auth-intro">
        <span className="wordmark">AgentEval</span>
        <h2>
          让每次评测
          <br />
          都有据可查。
        </h2>
        <p>连接自己的 Agent，把任务、执行过程和质量判断放在同一处。</p>
      </div>
      <form
        className="auth-form"
        onSubmit={async (event) => {
          event.preventDefault();
          setError(undefined);
          setBusy(true);
          try {
            z.email("请填写有效邮箱。").parse(email);
            await auth.login(email, password);
            setPassword("");
          } catch (failure) {
            setError(failure);
          } finally {
            setBusy(false);
          }
        }}
      >
        <h1>登录 AgentEval</h1>
        <p>使用管理员为你创建的账户。</p>
        <label>
          邮箱
          <input
            autoComplete="username"
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            required
          />
        </label>
        <label>
          密码
          <input
            autoComplete="current-password"
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />
        </label>
        <ErrorNotice error={error} />
        <button className="primary" disabled={busy}>
          {busy ? "正在登录…" : "登录"}
        </button>
      </form>
    </div>
  );
}
export function PasswordPage() {
  const auth = useAuth();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  if (auth.user === undefined) return <Loading />;
  if (!auth.user) return <Navigate to="/login" replace />;
  if (!auth.user.must_change_password)
    return <Navigate to="/workspace/experiments" replace />;
  return (
    <div className="password-page">
      <form
        className="auth-form"
        onSubmit={async (event) => {
          event.preventDefault();
          setError(undefined);
          setBusy(true);
          try {
            if (next.length < 12) throw new Error("新密码至少需要 12 个字符。");
            if (next !== confirm) throw new Error("两次新密码不一致。");
            await auth.changePassword(
              next,
              auth.needsCurrentPassword ? current : undefined,
            );
            setCurrent("");
            setNext("");
            setConfirm("");
          } catch (failure) {
            setError(failure);
          } finally {
            setBusy(false);
          }
        }}
      >
        <span className="wordmark">AgentEval</span>
        <h1>设置新密码</h1>
        <p>首次使用请替换临时密码，至少 12 个字符。</p>
        {auth.needsCurrentPassword && (
          <label>
            当前密码
            <input
              type="password"
              autoComplete="current-password"
              value={current}
              onChange={(event) => setCurrent(event.target.value)}
              required
            />
          </label>
        )}
        <label>
          新密码
          <input
            type="password"
            autoComplete="new-password"
            value={next}
            onChange={(event) => setNext(event.target.value)}
            required
            minLength={12}
          />
        </label>
        <label>
          确认新密码
          <input
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(event) => setConfirm(event.target.value)}
            required
          />
        </label>
        <ErrorNotice error={error} />
        <button className="primary" disabled={busy}>
          {busy ? "正在保存…" : "保存新密码"}
        </button>
      </form>
    </div>
  );
}
