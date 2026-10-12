# TASK 001.3 — Mutation path registry

**Status: PARTIAL — P0 OPEN.** Source SHA: `efe5d12272e3cdd01ef48daeac4a56af6f685040`.

## Android sink observed
The inspected `StandaloneRuntime.kt` routes `signedPost()`, `signedDelete()`, and `signedCancelReplace()` through `signedRequest()`. The visible authenticated OkHttp POST/DELETE request builders are inside `signedRequest()`. A source search identified multiple entry, protection, cancellation, OCO, exit and emergency call sites.

## Contract corrections
The sink now rejects mutation calls without a stable client/target key rather than inventing a fresh UUID operation identity. It canonicalizes request identity before persisting and transitions the intent to `SUBMITTING` before the request is built/sent.

## Not a complete proof
No whole-repository call graph or automated source-to-sink checker was executed. Indirect wrappers, all Python mutation paths, legacy/fallback branches, and runtime reachability of every call site remain **NOT VERIFIED**. The exact callsite registry with test mapping is incomplete. Gate D remains open.
