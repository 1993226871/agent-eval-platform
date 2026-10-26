import { useQuery } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import type { ResourceKind } from "../../api/types";
import { useWorkspace } from "../auth/AuthProvider";
import { collectPages, mapConcurrent } from "./pagination";

export function useCatalog(kind: ResourceKind, offset = 0) {
  const { workspace_id } = useWorkspace();
  return useQuery({
    queryKey: [workspace_id, "catalog", kind, offset],
    queryFn: () =>
      request(
        api.GET("/api/v1/catalog-summaries", {
          params: { query: { kind, offset, limit: 25 } },
        }),
      ),
  });
}
export function useVersionOptions(kind: ResourceKind) {
  const { workspace_id } = useWorkspace();
  return useQuery({
    queryKey: [workspace_id, "version-options", kind],
    queryFn: async ({ signal }) => {
      const resources = await collectPages(
        (offset, limit) =>
          request(
            api.GET("/api/v1/catalog-summaries", {
              params: { query: { kind, offset, limit } },
              signal,
            }),
          ),
        signal,
      );
      const versions = await mapConcurrent(
        resources.filter((item) => !item.archived),
        async (item) => {
          const values = await collectPages(
            (offset, limit) =>
              request(
                api.GET("/api/v1/catalog/{id}/versions", {
                  params: { path: { id: item.id }, query: { offset, limit } },
                  signal,
                }),
              ),
            signal,
          );
          return values.map((version) => ({
            id: version.id,
            label: `${item.name} · v${version.number}`,
          }));
        },
      );
      return versions.flat();
    },
  });
}
export function useModels() {
  const { workspace_id } = useWorkspace();
  return useQuery({
    queryKey: [workspace_id, "models"],
    queryFn: () => request(api.GET("/api/v1/models")),
  });
}
