import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { App } from "../../App";

const account = {
  user_id: "user-1",
  workspace_id: "workspace-1",
  email: "user@example.com",
  admin: false,
  must_change_password: false,
};
function respond(body: unknown, status = 200) {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
function app(path = "/workspace/experiments") {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: { queries: { retry: false, gcTime: 0 } },
        })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("private session flow", () => {
  it("requires password change, preserves CSRF, and hides admin navigation", async () => {
    let authenticated = false;
    let mustChange = true;
    const requests: Request[] = [];
    const storage = vi.spyOn(Storage.prototype, "setItem");
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const request =
          input instanceof Request ? input : new Request(input, init);
        requests.push(request);
        const path = new URL(request.url).pathname;
        if (path.endsWith("/auth/csrf"))
          return respond({ header_name: "X-CSRF-TOKEN", token: "csrf-test" });
        if (path.endsWith("/auth/login")) {
          authenticated = true;
          return respond({ authenticated: true });
        }
        if (path.endsWith("/auth/password")) {
          mustChange = false;
          return respond(null, 204);
        }
        if (path.endsWith("/auth/me"))
          return authenticated
            ? respond({ ...account, must_change_password: mustChange })
            : respond({ code: "UNAUTHENTICATED" }, 401);
        return respond([]);
      }),
    );
    app();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("邮箱"), account.email);
    await user.type(screen.getByLabelText("密码"), "Temporary-Password-1");
    await user.click(screen.getByRole("button", { name: "登录" }));
    expect(
      await screen.findByRole("heading", { name: "设置新密码" }),
    ).toBeVisible();
    await user.type(screen.getByLabelText("新密码"), "Updated-Password-22");
    await user.type(screen.getByLabelText("确认新密码"), "Updated-Password-22");
    await user.click(screen.getByRole("button", { name: "保存新密码" }));
    expect(await screen.findByRole("heading", { name: "实验" })).toBeVisible();
    expect(
      screen.queryByRole("link", { name: "管理后台" }),
    ).not.toBeInTheDocument();
    expect(storage).not.toHaveBeenCalled();
    for (const request of requests.filter((item) => item.method === "POST")) {
      expect(request.credentials).toBe("same-origin");
      expect(request.headers.get("X-CSRF-TOKEN")).toBe("csrf-test");
    }
  });

  it("returns to login after a protected resource rejects the session", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const request =
          input instanceof Request ? input : new Request(input, init);
        if (new URL(request.url).pathname.endsWith("/auth/me"))
          return respond(account);
        return respond({ code: "UNAUTHENTICATED" }, 401);
      }),
    );
    app("/workspace/datasets");
    expect(
      await screen.findByRole("heading", { name: "登录 VeyraEval" }),
    ).toBeVisible();
  });
});
