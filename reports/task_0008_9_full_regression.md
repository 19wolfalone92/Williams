# TASK 000.8.9 — Full Regression
Date: 2026-10-12 UTC

| SHA | Workflow | Result |
|---|---|---|
| `d038813e4c5e3e3b1d9797891e6d0ddbe3800266` | Python backend `38233695660` | FAILED: 1 failed, 218 passed, 4 warnings |
| same | Campaign CI `38233695652` | FAILED overall: Python 1 failed, 229 passed, 4 warnings; Android job succeeded |
| same | Android APK `38233696947` | startup_failure, no jobs |
| `a78a3f78a8a8be80d60fcc5b4e4ddf6ccaa627d0` | Python/Campaign | no run observed as of report preparation |

The failure on `d038813` is precisely identified: assertion expected `mandatory before any retry`, while public safe error says `reconciliation must be completed before any retry`. The assertion was corrected without removing checks for exception chain isolation or synthetic error text.

No local tests were run because no checkout/test runner is available via the connected tools. No result is represented as passed on the corrected SHA. PR #66 remains OPEN/DRAFT/NOT MERGED.

Status: **OPEN — FIX NOT VERIFIED**.
