# Whole-branch review and fixes

Review scope: `045336d..17bcedc`, the complete 20-task implementation, followed by the single review fix pass. The same independent reviewer resumed after an account quota interruption and completed the remaining passes on 2026-10-26. Verdict: **With fixes**, no confirmed Critical defects, six Important findings and one Minor finding. This verdict requires the fixes and fresh full acceptance before integration.

| Finding | Resolution | Regression evidence |
| --- | --- | --- |
| Runner retains private responses and crash inputs after experiment deletion | Persist dispatch identities before HTTP; authenticated scoped cleanup; secure SQLite response deletion and replay tombstones; wait for active input/container cleanup; platform withholds DELETED until acknowledgment | Registry raw-byte scrub, late completion/duplicate races, API authorization, platform cleanup failure/retry and workspace isolation; additional actual runsc and Compose deletion tests |
| 200 storage-blocked trials starve every workspace's freeze queue | Durable next/last attempt timestamps, 30-second backoff and oldest-attempt ordering; quota changes wake waiting evidence | Original 200 blocked + one healthy scenario failed before the fix; healthy workspace and immediate budget recovery pass afterward |
| Exact tool set can pass with partial evidence | Exact success requires complete evidence; observed extra tools prove failure; positive contains-all observations remain useful | Partial exact equality, complete equality, observed extra tool and partial contains-all |
| Missing experiment-level launch estimate | Tenant-scoped estimate endpoint; sum per-call integer reservation ceilings across cases, repetitions and suite entries; immutable snapshot stores token/price assumptions; launch and rerun dialogs display estimate | Integer value beyond JavaScript safe-number range; frozen six-call estimate and UI display |
| Paid previews/connectivity retries receive new identities after uncertain responses | Retain preview request identity across errors for unchanged draft/revision/trial; connectivity identity retained per model through errors and pending reconciliation, rotated only after terminal response | Lost preview response retries exact body; connectivity lost/pending responses reuse call ID |
| Fatal/nonordinary SDK exits report success | Start with FAILED, set SUCCEEDED only after valid result; Java root records Error then propagates it; Python BaseException still propagates through failure completion | Java AssertionError and async Python CancelledError reproduced SUCCEEDED before correction |
| Minor: agent rerun loses origin | Optional source experiment in command/hash and immutable snapshot, scoped lookup, metadata link in report | Owned source persisted, foreign source rejected |

## Rulings

- Historical Python dispatch intent was not persisted. V020 conservatively backfills every Python job whose execution budget was reserved. An old unavailable response does not prove the Runner has no cached earlier response. Consequently, deleting historical Python experiments can wait for a reachable Runner even if a later capability check failed. New disabled Python evaluations create no Runner dispatch mapping.
- Cleanup batches contain at most three artifact pairs and two Runner identities. With 90-second external request limits this leaves margin within the 900-second cleanup lease. No external request runs under database locks. Durable identities are retained without result bodies for replay fencing and hourly cleanup retries.
- The estimate is a first-attempt reservation bound, not a prepaid experiment reservation or spending cap. Actual usage, malformed-output repair retries and explicit regrades are separately accounted. Invalid or unavailable model bindings produce `planned_credits: null` and an explicit warning, never a false zero estimate; their evaluation keeps the existing error/skipped semantics. The same immutable versions determine launch estimate and frozen assumptions.
- Rerun origin is immutable metadata inside the existing frozen configuration; the original experiment's UUID can remain after its private evidence is deleted. Following a deleted source link returns the normal scoped unavailable response.
- User authorized merging main after all fixes and successful review/verification. No additional merge menu is needed. No paid model request, package publication or public deployment is part of acceptance.

## Declined-to-judge decisions

Every behavior declined by the reviewer was checked against the approved scope:

- Production high availability, arbitrary cloud S3 compatibility and sustained peak capacity remain outside single-node acceptance. Existing measurements are bounded test observations.
- Paid-model grading accuracy needs real calibration data; the stub verifies protocol, accounting and lifecycle only.
- Forced termination/reset of arbitrary user Agents and truth of their declared version remain responsibilities of the user's execution environment.
- Replaying unknown business effects or automatically resending unknown model calls is prohibited; retaining uncertainty is intentional.
- Arbitrary Python dependency installation/network access, full JSON Schema support and optional Spring AOP remain documented first-version exclusions.
- A newly deployed production sandbox node must pass its own isolation qualification; GitHub VM results are not transferable certification.

Final test and integration evidence is recorded in [acceptance.md](acceptance.md). Until that final gate succeeds, this document records resolved code paths rather than merge approval.
