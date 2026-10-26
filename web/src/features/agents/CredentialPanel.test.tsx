import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { CredentialPanel } from "./CredentialPanel";

it("shows a Worker key only at issuance and keeps it out of persistent browser storage", async () => {
  const user = userEvent.setup();
  const secret = "ae_fixture_once_only_key";
  const storage = vi.spyOn(Storage.prototype, "setItem");
  const props = {
    agentId: "agent-1",
    credentials: [],
    onIssue: vi.fn().mockResolvedValue({ id: "credential-1", secret }),
    onRevoke: vi.fn().mockResolvedValue(undefined),
  };
  const view = render(<CredentialPanel {...props} />);
  await user.click(screen.getByRole("button", { name: "创建接入凭证" }));
  expect(await screen.findByLabelText("本次接入密钥")).toHaveTextContent(
    secret,
  );
  await user.click(screen.getByRole("button", { name: "我已保存" }));
  view.rerender(
    <CredentialPanel
      {...props}
      credentials={[
        {
          id: "credential-1",
          agent_ids: ["agent-1"],
          revoked: false,
          created_at: "2026-10-26T00:00:00Z",
        },
      ]}
    />,
  );
  expect(screen.queryByText(secret)).not.toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "查看密钥" }),
  ).not.toBeInTheDocument();
  expect(storage).not.toHaveBeenCalled();
});
