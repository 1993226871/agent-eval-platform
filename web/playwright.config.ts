import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  workers: 1,
  retries: 0,
  timeout: 60000,
  reporter: [["list"]],
  outputDir: "../.tools/web-test-results",
  use: {
    baseURL: process.env.WEB_BASE_URL ?? "http://127.0.0.1:3000",
    viewport: { width: 1440, height: 1000 },
    trace: "off",
    screenshot: "off",
    video: "off",
  },
});
