import createClient from "openapi-fetch";
import { z } from "zod";
import type { paths } from "./schema";

export const SESSION_EXPIRED = "agent-eval:session-expired";
const csrfSchema = z.object({
  header_name: z.string().regex(/^[A-Za-z0-9-]+$/),
  token: z.string().min(1).max(2048),
});
const problemSchema = z.object({
  code: z.string().optional(),
  line: z.number().int().positive().optional(),
});
const messages: Record<string, string> = {
  INVALID_CREDENTIALS: "邮箱或密码不正确。",
  INVALID_PASSWORD: "当前密码不正确，或新密码不符合要求。",
  INVALID_REQUEST: "请检查填写的内容。",
  CONFLICT: "内容已变化，请刷新后重试。",
  QUOTA_CONFLICT: "这次额度请求与之前的请求不一致。",
  INSUFFICIENT_QUOTA: "可用额度不足，或调减后不足以覆盖已消费和预留额度。",
  FORBIDDEN: "当前账户不能执行此操作。",
  NOT_FOUND: "当前资源不存在或不可访问。",
  CAPACITY_REACHED: "当前运行数量已达上限，请稍后重试。",
  MODEL_UNAVAILABLE: "模型服务尚未配置完成，请联系管理员。",
  STORAGE_UNAVAILABLE: "附件存储暂时不可用，请稍后重试。",
  PASSWORD_CHANGE_REQUIRED: "请先设置新密码。",
  EVIDENCE_PENDING: "任务证据仍在收集，请稍后重试。",
};
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  constructor(status: number, payload: unknown) {
    const parsed = problemSchema.safeParse(payload);
    const code = parsed.success
      ? (parsed.data.code ?? "REQUEST_FAILED")
      : "REQUEST_FAILED";
    const line = parsed.success ? parsed.data.line : undefined;
    super(
      code === "INVALID_JSONL" && line
        ? `JSONL line ${line} 格式不正确，请检查这一行。`
        : (messages[code] ??
            (status === 401
              ? "登录已过期，请重新登录。"
              : status === 413
                ? "内容超过大小限制，请缩小后重试。"
                : "操作未完成，请稍后重试。")),
    );
    this.status = status;
    this.code = code;
  }
}
export const api = createClient<paths>({
  baseUrl: window.location.origin,
  credentials: "same-origin",
  fetch: (request) => globalThis.fetch(request),
});
api.use({
  async onRequest({ request }) {
    if (!["GET", "HEAD"].includes(request.method)) {
      const response = await globalThis.fetch(
        new Request(new URL("/api/v1/auth/csrf", window.location.origin), {
          credentials: "same-origin",
          signal: request.signal,
        }),
      );
      if (!response.ok)
        throw new ApiError(
          response.status,
          await response.json().catch(() => null),
        );
      const csrf = csrfSchema.parse(await response.json());
      request.headers.set(csrf.header_name, csrf.token);
    }
    return request;
  },
  onResponse({ response }) {
    if (response.status === 401)
      window.dispatchEvent(new Event(SESSION_EXPIRED));
    return response;
  },
});
type Result<T> = { data?: T; error?: unknown; response: Response };
export async function request<T>(response: Promise<Result<T>>): Promise<T> {
  const result = await response;
  if (!result.response.ok)
    throw new ApiError(result.response.status, result.error);
  return result.data as T;
}
export function errorMessage(error: unknown): string {
  if (error instanceof z.ZodError)
    return error.issues
      .map((issue) => `${issue.path.join(".")}：${issue.message}`)
      .join("\n");
  return error instanceof Error ? error.message : "操作未完成，请稍后重试。";
}
