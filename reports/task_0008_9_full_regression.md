# TASK 000.8.9 — Full Regression
Date: 2026-10-12 UTC

## CI evidence
| SHA | Workflow | Result |
|---|---|---|
| `d038813e4c5e3e3b1d9797891e6d0ddbe3800266` | Python backend `38233695660` | FAILED: 1 failed, 218 passed, 4 warnings |
| same | Campaign `38233695652` | FAILED overall: Python 1 failed, 229 passed, 4 warnings; Android job succeeded |
| same | Android APK `38233696947` | startup_failure, no jobs |
| `a78a3f78a8a8be80d60fcc5b4e4ddf6ccaa627d0` | Python backend `38234529997` | SUCCESS: 219 passed, 4 warnings; dependency audit/release gate/compile/static audit succeeded |
| same | Campaign `38234529976` | Python job SUCCESS: 230 passed, 4 warnings; Android job in progress at this report update |
| same | Android APK `38234530951` | startup_failure, no jobs |

The original failure was the incorrect expected phrase. The corrected test preserves exception-chain and redaction assertions. Python backend and Campaign Python regression now pass on the exact test-fix SHA. The Campaign Android job must finish before Campaign is considered fully successful.

The latest report-only commits advance branch HEAD after the verified code SHA; no production/test logic changed in those documentation commits. Local execution was not available.

Status: **OPEN — FIX NOT VERIFIED** for full TASK 000.8 completion; targeted configured Python suites verified.
