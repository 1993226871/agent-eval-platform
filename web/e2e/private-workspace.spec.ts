import { test, expect } from "@playwright/test";
import path from "node:path";

test("real session, CSRF, immutable publication, and one-time worker credential", async ({
  page,
}) => {
  const email = process.env.WEB_TEST_EMAIL;
  const password = process.env.WEB_TEST_PASSWORD;
  const next = process.env.WEB_TEST_NEW_PASSWORD;
  if (!email || !password || !next)
    throw new Error(
      "Run scripts/verify_phase_b.py --web-smoke to create disposable test services and credentials.",
    );
  const failures: string[] = [];
  page.on("pageerror", (error) => failures.push(error.message));
  const requests: { method: string; csrf: boolean }[] = [];
  page.on("request", (request) => {
    if (
      new URL(request.url()).pathname.startsWith("/api/") &&
      !["GET", "HEAD"].includes(request.method())
    )
      requests.push({
        method: request.method(),
        csrf: !!request.headers()["x-csrf-token"],
      });
  });
  await page.goto("/workspace/datasets");
  await page.getByLabel("邮箱", { exact: true }).fill(email);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.getByRole("heading", { name: "设置新密码" })).toBeVisible();
  await page.getByLabel("新密码", { exact: true }).fill(next);
  await page.getByLabel("确认新密码").fill(next);
  await page.getByRole("button", { name: "保存新密码" }).click();
  await expect(
    page.getByRole("heading", { name: "实验", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: "管理后台" })).toHaveCount(0);
  await page.getByRole("link", { name: "任务集", exact: true }).click();
  await page.getByRole("button", { name: "新建任务集" }).click();
  await page.getByLabel("名称", { exact: true }).fill("浏览器集成案例");
  await page.getByRole("button", { name: "创建草稿" }).click();
  await expect(page.getByLabel("草稿内容")).toBeVisible();
  await page.getByRole("button", { name: "发布版本", exact: true }).click();
  await expect(
    page.getByRole("combobox", { name: "查看版本" }).getByRole("option"),
  ).toHaveCount(2);
  await page
    .getByRole("combobox", { name: "查看版本" })
    .selectOption({ label: "v1" });
  await expect(page.getByLabel("已发布版本")).toBeDisabled();
  await page.screenshot({
    path: path.resolve("../.tools/web-dataset-desktop.png"),
  });
  await page.getByRole("link", { name: "Agent 接入" }).click();
  await page.getByRole("button", { name: "注册 Agent" }).click();
  await page.getByLabel("Agent 名称", { exact: true }).fill("浏览器 Agent");
  await page.getByRole("button", { name: "注册", exact: true }).click();
  await page.getByRole("button", { name: "创建接入凭证" }).click();
  const secret = page.getByLabel("本次接入密钥");
  await expect(secret).not.toBeEmpty();
  await page.getByRole("button", { name: "我已保存" }).click();
  await expect(secret).not.toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Agent 接入", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "浏览器 Agent v1" }).click();
  await expect(page.getByLabel("本次接入密钥")).not.toBeVisible();
  await page.getByRole("button", { name: "撤销", exact: true }).click();
  await expect(page.getByText("已撤销", { exact: true })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("link", { name: "任务集", exact: true }).click();
  await page.getByRole("button", { name: /浏览器集成案例/ }).click();
  await page.screenshot({
    path: path.resolve("../.tools/web-dataset-mobile.png"),
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  expect(
    await page.evaluate(() => ({
      local: localStorage.length,
      session: sessionStorage.length,
    })),
  ).toEqual({ local: 0, session: 0 });
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(
    page.getByRole("heading", { name: "登录 VeyraEval" }),
  ).toBeVisible();
  expect(requests.length).toBeGreaterThan(5);
  expect(requests.every((request) => request.csrf)).toBe(true);
  expect(failures).toEqual([]);
});
