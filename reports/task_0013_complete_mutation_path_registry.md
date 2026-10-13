# TASK 001.3 — Mutation path registry

**Status: PARTIAL — P0 OPEN.** Current source SHA: `74f12c595181d0b4362fa02f2b2de436db9a751c`.

Inspected Android `StandaloneRuntime.kt`: `signedPost()`, `signedDelete()` and `signedCancelReplace()` route through `signedRequest()`; visible authenticated OkHttp POST/DELETE request builders are inside that sink. Entry, protection, cancellation, OCO, exit and emergency call sites were inspected. OCO creation now uses deterministic context-derived client IDs. The sink canonicalizes the request, persists the intent, acquires the durable submission claim and rejects missing identity.

The source audit is **not exhaustive**: no complete interprocedural call graph or automated source-to-sink checker was executed; indirect wrappers, every Python mutation path, all legacy/fallback paths and full callsite-to-test mapping remain **NOT VERIFIED**. A production-reachable bypass has not been conclusively ruled out. Gate D remains open.
