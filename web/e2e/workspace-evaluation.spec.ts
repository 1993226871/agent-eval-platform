import { test, expect } from "@playwright/test";
import { spawn } from "node:child_process";
import { writeFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import path from "node:path";

test("HTTPS administrator provisions CNY and a config-file Agent evaluates, compares and deletes", async ({
  page,
}) => {
  test.setTimeout(180000);
  page.setDefaultTimeout(15000);
  const admin = process.env.WEB_ACCEPTANCE_ADMIN;
  const password = process.env.WEB_ACCEPTANCE_PASSWORD;
  const root = process.env.WEB_ACCEPTANCE_ROOT;
  const python = process.env.WEB_ACCEPTANCE_PYTHON;
  const ca = process.env.WEB_ACCEPTANCE_CA;
  if (!admin || !password || !root || !python || !ca)
    throw new Error(
      "Run the owned Compose e2e fixture for HTTPS browser acceptance",
    );
  const email = `browser-${randomUUID()}@example.test`;
  const temporary = randomUUID() + "-Temporary-1";
  const next = randomUUID() + "-Changed-1";
  await page.goto("/login");
  await page.getByLabel("邮箱", { exact: true }).fill(admin);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.getByRole("link", { name: "管理后台", exact: true }).click();
  await page.getByLabel("新账户邮箱").fill(email);
  await page.getByLabel("临时密码", { exact: true }).fill(temporary);
  await page.getByRole("button", { name: "创建账户", exact: true }).click();
  await expect(page.getByText("账户已创建。", { exact: true })).toBeVisible();
  await page
    .getByRole("combobox", { name: "用户工作空间", exact: true })
    .selectOption({ label: email });
  await page.getByLabel("调整金额（元）", { exact: true }).fill("1");
  await page
    .getByLabel("调整原因", { exact: true })
    .fill("Browser acceptance budget");
  await page.getByRole("button", { name: "提交额度调整", exact: true }).click();
  await expect(
    page.getByText("调整后可用：¥6.00", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await page.getByLabel("邮箱", { exact: true }).fill(email);
  await page.getByLabel("密码", { exact: true }).fill(temporary);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.getByLabel("新密码", { exact: true }).fill(next);
  await page.getByLabel("确认新密码").fill(next);
  await page.getByRole("button", { name: "保存新密码", exact: true }).click();
  await expect(
    page.getByRole("link", { name: "管理后台", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("link", { name: "任务集", exact: true }).click();
  await page.getByText("从平台示例开始", { exact: true }).click();
  const copied = page.waitForResponse(
    (response) =>
      response.url().endsWith("/templates/starter/copy") &&
      response.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: "复制完整入门示例", exact: true })
    .click();
  expect((await copied).status()).toBe(200);
  await page.getByRole("link", { name: "Agent 接入", exact: true }).click();
  await page.getByRole("button", { name: "注册 Agent", exact: true }).click();
  await page
    .getByLabel("Agent 名称", { exact: true })
    .fill("Browser order agent");
  const created = page.waitForResponse(
    (response) =>
      response.url().endsWith("/agents") &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "注册", exact: true }).click();
  const agent = (await (await created).json()) as { id: string };
  await page.getByRole("button", { name: "创建接入凭证", exact: true }).click();
  await expect(page.getByLabel("本次接入密钥")).toBeVisible();
  await expect(page.getByLabel("本次接入密钥")).not.toBeEmpty();
  const secret = (await page.getByLabel("本次接入密钥").textContent())?.trim();
  if (!secret) throw new Error("One-time fixture credential missing");
  const configuration = JSON.parse(
    (await page.getByLabel("Python SDK 配置文件").textContent())!,
  ) as Record<string, string>;
  expect(configuration.agent_id).toBe(agent.id);
  expect(configuration.api_key).toBe(secret);
  expect(configuration.agent_version).toBe("v1");
  configuration.ca_cert = ca;
  configuration.spool_path = path.join(root, "browser-sdk.sqlite");
  const configurationPath = path.join(root, "browser-agent.local.json");
  await writeFile(configurationPath, JSON.stringify(configuration), {
    mode: 0o600,
  });
  await page.getByRole("button", { name: "我已保存", exact: true }).click();
  const worker = spawn(
    python,
    [
      path.resolve("../examples/python-order-agent/main.py"),
      "--config",
      configurationPath,
    ],
    {
      cwd: path.resolve(".."),
      stdio: "ignore",
      env: {
        ...process.env,
        PYTHONPATH: path.resolve("../sdks/python/src"),
      },
      windowsHide: true,
    },
  );
  try {
    await page.getByRole("link", { name: "实验", exact: true }).click();
    await page.getByRole("button", { name: "新建实验", exact: true }).click();
    await page
      .getByRole("combobox", { name: "Agent", exact: true })
      .selectOption(agent.id);
    await page
      .getByRole("combobox", { name: "任务集版本", exact: true })
      .selectOption({ index: 1 });
    await page
      .getByRole("combobox", { name: "评分规则集版本", exact: true })
      .selectOption({ index: 1 });
    const started = page.waitForResponse(
      (response) =>
        response.url().endsWith("/experiments") &&
        response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "启动实验", exact: true }).click();
    const first = ((await (await started).json()) as { id: string }).id;
    const waitPassed = async (id: string) => {
      await expect
        .poll(
          async () =>
            (
              (await (
                await page.request.get("/api/v1/reports/experiments/" + id)
              ).json()) as { passed_attempts: number }
            ).passed_attempts,
          { timeout: 70000, intervals: [1000] },
        )
        .toBe(1);
    };
    await waitPassed(first);
    await page.goto("/workspace/reports/" + first);
    await page
      .getByRole("button", { name: "案例 1 · 第 1 次", exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: "执行轨迹", exact: true }),
    ).toBeVisible();
    await expect(page.getByText(/树结构来自 Worker/)).toBeVisible();
    await page.getByRole("button", { name: "重跑 Agent", exact: true }).click();
    const rerun = page.waitForResponse(
      (response) =>
        response.url().endsWith("/experiments") &&
        response.request().method() === "POST",
    );
    await page
      .getByRole("button", { name: "确认创建新实验", exact: true })
      .click();
    const second = ((await (await rerun).json()) as { id: string }).id;
    await waitPassed(second);
    await page.reload();
    await page.getByLabel("对比最近 100 次实验").selectOption(first);
    await page
      .getByRole("link", { name: "查看可比性与差异", exact: true })
      .click();
    await expect(page.getByText("可以直接比较", { exact: true })).toBeVisible();
    await page.getByRole("link", { name: "实验", exact: true }).click();
    await page
      .getByRole("row")
      .filter({ hasText: first.slice(0, 8) })
      .getByRole("button", { name: "删除实验", exact: true })
      .click();
    await page.getByRole("button", { name: "确认删除", exact: true }).click();
    await page.getByRole("link", { name: "查看删除进度", exact: true }).click();
    await expect(
      page.getByRole("heading", { name: "实验删除进度", exact: true }),
    ).toBeVisible();
    expect(
      (await page.request.get("/api/v1/reports/experiments/" + first)).status(),
    ).toBe(404);
  } finally {
    worker.kill();
    await new Promise<void>((resolve) => {
      if (worker.exitCode !== null) resolve();
      else worker.once("exit", () => resolve());
    });
  }
});
