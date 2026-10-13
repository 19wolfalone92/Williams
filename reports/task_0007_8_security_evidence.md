# TASK 000.7.8 — Remaining Security Evidence
Date: 2026-10-12 UTC

## Observed
- Python workflow job token permissions were `Contents: read`, `Metadata: read` in runs 38232513469/38232513545.
- Checkout used `fetch-depth: 1`, `fetch-tags: false`; therefore it cannot prove full history secret absence.
- The workflows inspected pin third-party actions to full commit SHAs in the relevant jobs.
- Python pip-audit step completed successfully on HEAD `974596dbc72ea90d6bf1c08cc105151796440acf` for the manifests it processed, but the overall Python run failed.
- requirements declarations are not a verified hash-locked reproducible environment.
- Branch protection/ruleset administrative read previously returned HTTP 403; that means access denied, not that protection is absent.
- GitHub Actions Secrets/Environments APIs are not available through the current connector. No secret values were queried or printed.

## Open items
- Branch protection, required checks, rulesets, merge restrictions: BLOCKED — ACCESS REQUIRED.
- Secrets/Environments policy and privileged workflow protections: BLOCKED — ACCESS REQUIRED.
- Full all-ref history scan: INCOMPLETE — EVIDENCE MISSING.
- Python reproducible lock/hash installation: OPEN — FIX NOT VERIFIED.
- Full Python + Android/Gradle SCA: INCOMPLETE — EVIDENCE MISSING.
- No workflow dispatch operation is exposed in the connected GitHub actions. A rerun of historical runs would not validate the newer code SHA.

Status remains blocked/incomplete; no administrative controls were changed.
