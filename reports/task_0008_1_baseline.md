# TASK 000.8.1 — Baseline Verification
Date: 2026-10-12 UTC

- Expected starting HEAD: `d038813e4c5e3e3b1d9797891e6d0ddbe3800266`.
- Verified starting HEAD: exact match.
- Repository: `19wolfalone92/Williams`.
- PR #66: OPEN, DRAFT, NOT MERGED. Base branch: `fix/pytest-safe-collection`.
- PR is stacked on still-open PR #65 per PR description; no retargeting or merging performed.
- TASK 000.5–000.7 report files and exception-isolation code/test commits were present.
- HEAD at end of baseline read: `d038813e4c5e3e3b1d9797891e6d0ddbe3800266`.
- Working-tree status cannot be verified through GitHub Contents API; no local checkout is exposed.
- CI on this SHA: Python run `38233695660` failed; Campaign run `38233695652` failed overall (Python failed, Android job succeeded); Android APK workflow run `38233696947` startup_failure with no jobs.
- After reproducing the latest assertion mismatch in CI logs, committed a minimal test-only correction at `a78a3f78a8a8be80d60fcc5b4e4ddf6ccaa627d0`. It updates the expected safe wording to match the actual public message, without removing exception-chain assertions.
- PR remains OPEN / DRAFT / NOT MERGED. No main changes, force-push, real orders, or authenticated mutating Binance calls.

Status: **CLOSED — VERIFIED** for PR/ref identity and starting SHA; **INCOMPLETE — EVIDENCE MISSING** for local working-tree status.
