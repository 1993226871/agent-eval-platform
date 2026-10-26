import { describe, expect, it } from "vitest";
import { collectPages, mapConcurrent } from "./pagination";

describe("version selection beyond the first page", () => {
  it("collects versions on later pages", async () => {
    const values = Array.from({ length: 205 }, (_, index) => index);
    expect(
      await collectPages(async (offset, limit) =>
        values.slice(offset, offset + limit),
      ),
    ).toEqual(values);
  });
  it("bounds simultaneous resource reads and preserves order", async () => {
    let active = 0,
      maximum = 0;
    const values = await mapConcurrent(
      Array.from({ length: 11 }, (_, index) => index),
      async (value) => {
        active++;
        maximum = Math.max(maximum, active);
        await new Promise((resolve) => setTimeout(resolve, value % 2));
        active--;
        return value;
      },
    );
    expect(values).toEqual(Array.from({ length: 11 }, (_, index) => index));
    expect(maximum).toBe(4);
  });
  it("stops requesting further pages when navigation aborts", async () => {
    const controller = new AbortController();
    let calls = 0;
    await expect(
      collectPages(async () => {
        calls++;
        controller.abort();
        return Array(100).fill(1);
      }, controller.signal),
    ).rejects.toThrow();
    expect(calls).toBe(1);
  });
});
