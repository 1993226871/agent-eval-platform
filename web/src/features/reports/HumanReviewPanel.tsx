import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import { ErrorNotice, Loading } from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";

export function HumanReviewPanel({ resultId }: { resultId: string }) {
  const { workspace_id } = useWorkspace();
  const cache = useQueryClient();
  const [score, setScore] = useState("");
  const [reason, setReason] = useState("");
  const key = [workspace_id, "human-reviews", resultId];
  const reviews = useQuery({
    queryKey: key,
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/results/{resultId}/reviews", {
          params: { path: { resultId }, query: { limit: 100 } },
          signal,
        }),
      ),
  });
  const add = useMutation({
    mutationFn: () => {
      const value = Number(score);
      if (!score || !Number.isFinite(value) || value < 0 || value > 1)
        throw new Error("复核分数需要在 0～1 之间。");
      return request(
        api.POST("/api/v1/results/{resultId}/reviews", {
          params: { path: { resultId } },
          body: { score: value, reason: reason.trim() },
        }),
      );
    },
    onSuccess: async () => {
      setScore("");
      setReason("");
      await cache.invalidateQueries({ queryKey: key });
    },
  });
  return (
    <details className="human-review">
      <summary>人工复核（保留原始自动评分）</summary>
      <ErrorNotice error={reviews.error ?? add.error} />
      {reviews.isPending ? (
        <Loading />
      ) : (
        <ul className="review-list">
          {reviews.data?.map((review) => (
            <li key={review.id}>
              <strong>{review.score}</strong>
              <span>{review.reason}</span>
              <small>
                {new Date(review.created_at).toLocaleString("zh-CN")} ·{" "}
                {review.reviewer_id}
              </small>
            </li>
          ))}
        </ul>
      )}
      <form
        onSubmit={(event) => {
          event.preventDefault();
          add.mutate();
        }}
      >
        <label>
          复核分数（0～1）
          <input
            required
            type="number"
            min={0}
            max={1}
            step={0.000001}
            value={score}
            onChange={(event) => setScore(event.target.value)}
          />
        </label>
        <label>
          复核依据
          <textarea
            required
            maxLength={2048}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        </label>
        <button disabled={add.isPending || !reason.trim()}>追加复核</button>
      </form>
    </details>
  );
}
