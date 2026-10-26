import { useState } from "react";
import type { Schema } from "../../api/types";
import { Dialog, ErrorNotice } from "../../components/ui";

type Props = {
  experimentId: string;
  onDelete: (key: string) => Promise<Schema<"DeletionStatus">>;
  onRequested: (status: Schema<"DeletionStatus">) => void;
};
export function DeletionControl({
  experimentId,
  onDelete,
  onRequested,
}: Props) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const [key] = useState(crypto.randomUUID());
  return (
    <>
      <button className="danger subtle" onClick={() => setOpen(true)}>
        删除实验
      </button>
      <Dialog
        open={open}
        title="删除实验与历史证据"
        onClose={() => {
          if (!busy) setOpen(false);
        }}
      >
        <p>
          删除后将无法访问此实验的任务、报告、复核和执行证据。运行中的任务会停止，附件随后清理。已发生的模型费用和额度账本会保留。
        </p>
        <p>
          <code>{experimentId}</code>
        </p>
        <ErrorNotice error={error} />
        <div className="actions">
          <button disabled={busy} onClick={() => setOpen(false)}>
            取消删除
          </button>
          <button
            className="danger"
            disabled={busy}
            onClick={async () => {
              setBusy(true);
              setError(undefined);
              try {
                const status = await onDelete(key);
                setOpen(false);
                onRequested(status);
              } catch (failure) {
                setError(failure);
              } finally {
                setBusy(false);
              }
            }}
          >
            {busy ? "正在提交…" : "确认删除"}
          </button>
        </div>
      </Dialog>
    </>
  );
}
