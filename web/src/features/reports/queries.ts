import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import { useWorkspace } from "../auth/AuthProvider";

export function useReportTrials(id: string) {
  const { workspace_id } = useWorkspace();
  return useInfiniteQuery({
    queryKey: [workspace_id, "report-trials", id],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      request(
        api.GET("/api/v1/reports/experiments/{id}/trials", {
          params: { path: { id }, query: { limit: 25, cursor: pageParam } },
          signal,
        }),
      ),
    getNextPageParam: (page) => page.next_cursor ?? undefined,
    refetchInterval: 10000,
  });
}

export function useReportDataset(id: string | undefined) {
  const { workspace_id } = useWorkspace();
  return useQuery({
    queryKey: [workspace_id, "report-dataset", id],
    enabled: !!id,
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/catalog/versions/{id}", {
          params: { path: { id: id! } },
          signal,
        }),
      ),
  });
}

export function useReportIdentity(id: string) {
  const { workspace_id } = useWorkspace();
  return useQuery({
    queryKey: [workspace_id, "experiment", id],
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/experiment-summaries/{id}", {
          params: { path: { id } },
          signal,
        }),
      ),
  });
}
