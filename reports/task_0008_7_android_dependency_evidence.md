# TASK 000.8.7 — Android and Dependency Evidence
Date: 2026-10-12 UTC

- Android workflow run `38233696947` on starting SHA `d038813e4c5e3e3b1d9797891e6d0ddbe3800266`: `startup_failure`, zero job records returned.
- Earlier runs `38232514514`, `38231995378`, `38232066353`: also startup_failure/no job records.
- Campaign Android job in run `38233695652` succeeded: debug APK and lint report artifacts uploaded. Artifact ZIP ID `11739543288`, SHA-256 `282db932f92992efb7eb4ed048892bd5e1b11628a53705900704219f91345dea`; lint ZIP ID `11739293554`, SHA-256 `d311dd4a1dc99f9927ff12f30fd6cd60c554864017585647c04d7f535640e45d`. Artifact metadata ties them to source SHA `d038813e4c5e3e3b1d9797891e6d0ddbe3800266`.
- Prior inspected APK had certificate subject `CN=Android Debug, O=Android, C=US`; this is not a trusted release signature.
- The current connector can list/download artifact ZIPs, but no independent trusted release certificate fingerprint was supplied and no local apksigner/Gradle SCA environment is exposed.
- Python `pip-audit` passed in CI on the starting SHA for the manifests the workflow processed, but overall Python CI failed. Dependency lock/hash reproducibility is not established.
- The exact cause of Android workflow startup_failure is not exposed because no job/runner logs exist.

Status: **BLOCKED — ACCESS REQUIRED** for startup-failure diagnostics; **INCOMPLETE — EVIDENCE MISSING** for signature trust, Android SCA, and reproducibility.
