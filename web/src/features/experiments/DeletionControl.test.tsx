import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { DeletionControl } from "./DeletionControl";

it("requires explicit confirmation and retries with the same deletion identity", async () => {
  const value = {
    experiment_id: "experiment",
    status: "DELETING",
    requested_at: "2026-10-26T00:00:00Z",
    next_cleanup_at: "2026-10-26T00:05:00Z",
    completed_at: null,
    failure_count: 0,
  };
  const remove = vi
    .fn()
    .mockRejectedValueOnce(new Error("Network interruption"))
    .mockResolvedValue(value);
  const requested = vi.fn();
  render(
    <DeletionControl
      experimentId="experiment"
      onDelete={remove}
      onRequested={requested}
    />,
  );
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "删除实验" }));
  expect(screen.getByRole("dialog")).toHaveTextContent("无法访问");
  expect(remove).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "取消删除" }));
  expect(remove).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "删除实验" }));
  await user.click(screen.getByRole("button", { name: "确认删除" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Network interruption",
  );
  await user.click(screen.getByRole("button", { name: "确认删除" }));
  await waitFor(() => expect(requested).toHaveBeenCalledWith(value));
  expect(remove).toHaveBeenCalledTimes(2);
  expect(remove.mock.calls[1]?.[0]).toBe(remove.mock.calls[0]?.[0]);
});
