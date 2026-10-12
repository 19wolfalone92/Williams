# TASK 001.2 — Baseline and source trace
**Status: PARTIAL — P0 OPEN**

## Baseline facts retrieved from GitHub
- Repository: `19wolfalone92/Williams`
- PR #66: open, Draft, not merged.
- Working branch: `night-shift-audit-20261012`
- Required starting HEAD: `8ff7ce99310082f617f1f7ec4f02595d32ce876f`; GitHub PR metadata returned this exact head before TASK 001.2 edits.
- Base branch: `fix/pytest-safe-collection`; base SHA: `0007e79b1afde86b8c736128e6d09bfdd370106f`.
- PR reported 85 changed files before TASK 001.2 edits; repository API did not expose a local working-tree status.
- Previous implementation/test SHA: `12f3e70eb58915d56c4bf66ebb5d00c7130a97b`.
- Baseline workflow runs on required starting HEAD: Campaign `38237958639` SUCCESS; Python backend `38237958541` SUCCESS; standalone Android APK `38237959962` startup_failure with no jobs returned.
- Campaign run jobs showed Python tests and Android build/static checks completed successfully on that SHA. This is not evidence for later commits.
- PR diff and changed-file listing were retrieved from GitHub. The full PR has extensive prior audit changes; no reset, force-push, merge, or main-branch write was performed.

## Source findings
- `StandaloneRuntime.signedRequest()` journals POST/DELETE before HTTP mutation and marks ambiguous exceptions UNKNOWN.
- `executionReady()` checks both `reconcileRequired` and `mutationLedger.hasUnresolved()`.
- `recover()` deliberately refuses to clear the barrier when unresolved intents exist.
- Before TASK 001.2, generated exchange client IDs could be random per invocation when callers omitted IDs; this could make a repeated ambiguous request appear to be a new operation.
- Full operation-specific Binance reconciliation and a complete source-to-sink bypass proof remain absent.
- The Android APK startup failure has no job records in the returned workflow evidence; root cause is NOT VERIFIED, not attributed to infrastructure or permissions without logs.

## Changes after baseline
TASK 001.2 edits were applied only to the existing PR branch. See the GitHub commit history for exact SHAs. Current final HEAD and CI must be rechecked after the last report commit.
