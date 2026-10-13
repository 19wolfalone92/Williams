# TASK 000.8.8 — Security Controls and Git History
Date: 2026-10-12 UTC

## Confirmed
- Workflow YAMLs inspected from branch `night-shift-audit-20261012`.
- Relevant CI jobs specify `permissions: contents: read`; previous run logs showed Contents: read and Metadata: read.
- Inspected third-party Actions references are pinned to full commit SHAs.
- CI checkout uses shallow history in the relevant workflows (`fetch-depth: 1` / default shallow behavior), so it does not prove all-ref secret absence.
- Previous branch protection/ruleset read returned HTTP 403 via the connector. This proves access was denied, not that protection is absent.
- Secrets/Environments administrative policy cannot be inspected through currently exposed connector operations.

## Unresolved
- Branch protection, required checks, rulesets, merge restrictions: BLOCKED — ACCESS REQUIRED.
- Secrets/Environments and privileged workflow policy: BLOCKED — ACCESS REQUIRED.
- Full mirror/all-ref secret scan, including deleted files and reachable refs: INCOMPLETE — EVIDENCE MISSING.
- Python hash-locked reproducible dependency install: OPEN — FIX NOT VERIFIED.
- Complete Python and Android/Gradle SCA: INCOMPLETE — EVIDENCE MISSING.

No security settings were changed, no secret values were queried or printed, and no history rewrite was performed.
