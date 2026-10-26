import { useQuery } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import type { ResourceKind } from "../../api/types";
import { useWorkspace } from "../auth/AuthProvider";

export function useCatalog(kind: ResourceKind, offset = 0) {
  const { workspace_id } = useWorkspace();
  return useQuery({
    queryKey: [workspace_id, "catalog", kind, offset],
    queryFn: () =>
      request(
        api.GET("/api/v1/catalog", {
          params: { query: { kind, offset, limit: 25 } },
        }),
      ),
  });
}
export function useVersionOptions(kind: ResourceKind) {
  const { workspace_id } = useWorkspace();
  return useQuery({
    queryKey: [workspace_id, "version-options", kind],
    queryFn: async () => {
      const resources = await request(
        api.GET("/api/v1/catalog", { params: { query: { kind, limit: 100 } } }),
      );
      const versions = await Promise.all(
        resources
          .filter((item) => !item.archived)
          .map(async (item) => {
            const values = await request(
              api.GET("/api/v1/catalog/{id}/versions", {
                params: { path: { id: item.id }, query: { limit: 100 } },
              }),
            );
            return values.map((version) => ({
              id: version.id,
              label: `${item.name} · v${version.number}`,
            }));
          }),
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
