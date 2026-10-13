# TASK 000.7.9 — Full Regression
Date: 2026-10-12 UTC

## Exact-SHA results
- HEAD `974596dbc72ea90d6bf1c08cc105151796440acf`: Python backend 38232513469 FAILED, 1 failed / 218 passed / 4 warnings.
- Same HEAD: Campaign CI 38232513545 overall FAILED because Python 1 failed / 229 passed / 4 warnings; Android build/static checks succeeded and produced APK/lint artifacts.
- Android workflow 38232514514: startup_failure with no jobs.
- Commit `956dbfe2f01a02877c4f461992edf0f937b6e5d1` contains only a test-contract update that now asserts the public exception remains sanitized and detached from `__cause__`/`__context__`. No CI run is returned for this exact SHA.
- No local test runner/checkout is exposed through the connected GitHub tools. Therefore targeted tests, full regression, and Campaign CI have not been executed on final HEAD.

## Disposition
Do not claim regression passed. Request an exact-SHA CI run for `956dbfe2f01a02877c4f461992edf0f937b6e5d1` and remediate any remaining failures. PR remains OPEN/DRAFT/NOT MERGED.

Status: **OPEN — FIX NOT VERIFIED**.
