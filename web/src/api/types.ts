import type { components } from "./schema";
export type Schema<T extends keyof components["schemas"]> =
  components["schemas"][T];
export type CurrentUser = Schema<"CurrentUser">;
export type CatalogResource = Schema<"CatalogResource">;
export type ResourceKind = CatalogResource["kind"];
export type PublishedVersion = Schema<"PublishedVersion">;
export type VersionSummary = Schema<"PublishedVersionSummary">;
export type Agent = Schema<"Agent">;
export type Credential = Schema<"Credential">;
export type ModelChoice = Schema<"ModelChoice">;
