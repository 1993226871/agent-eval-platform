import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { ModelConnectionForm } from "./ModelConnectionForm";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

it("connects a private key using only model, API address and key", async () => {
  const submitted: unknown[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const req = input instanceof Request ? input : new Request(input, init);
      if (req.url.endsWith("/auth/csrf"))
        return Response.json({ header_name: "X-CSRF-TOKEN", token: "csrf" });
      expect(req.url).toMatch(/\/api\/v1\/models\/connections$/);
      expect(req.headers.get("X-CSRF-TOKEN")).toBe("csrf");
      submitted.push(await req.json());
      return Response.json({ id: "private-model" });
    }),
  );
  const onSaved = vi.fn();
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { mutations: { retry: false } } })
      }
    >
      <ModelConnectionForm mode="BYOK" onSaved={onSaved} />
    </QueryClientProvider>,
  );
  expect(screen.queryByLabelText("输入 Token 上界")).not.toBeInTheDocument();
  expect(screen.queryByLabelText("输入单价分子")).not.toBeInTheDocument();
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("模型名称"), "deepseek-flash");
  await user.type(
    screen.getByLabelText("API 地址"),
    "https://api.deepseek.com",
  );
  await user.type(screen.getByLabelText("API Key"), "test-key-never-log");
  await user.click(screen.getByRole("button", { name: "保存我的模型" }));
  await waitFor(() => expect(onSaved).toHaveBeenCalledOnce());
  expect(submitted).toEqual([
    {
      model: "deepseek-flash",
      endpoint: "https://api.deepseek.com",
      api_key: "test-key-never-log",
    },
  ]);
  expect(screen.getByLabelText("API Key")).toHaveValue("");
});
