# TASK 001.3 — Mutation path registry

**Status: PARTIAL — P0 OPEN.** Implementation/test source SHA: `8415ab5e6b74e082b865e2cf2ddbcbde29fdb366`.

## Android sink evidence
Inspected `StandaloneRuntime.kt`: `signedPost()`, `signedDelete()`, and `signedCancelReplace()` route through `signedRequest()`; the visible authenticated OkHttp POST/DELETE request builders are inside that sink. The source scan identified entry, protection, cancellation, OCO, exit and emergency call sites. The sink now canonicalizes the unsigned request before ledger preparation, derives operation IDs from stable client/target keys, and rejects missing identity rather than using a fresh UUID fallback.

## Limits
No complete interprocedural call graph or automated source-to-sink checker was executed. Indirect wrappers, all Python mutation paths, all legacy/fallback branches, and runtime reachability of every call site remain **NOT VERIFIED**. There is no exhaustive path-to-test registry. Gate D remains open.
