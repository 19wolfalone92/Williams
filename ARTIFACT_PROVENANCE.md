# TASK 002 — Artifact Provenance

**Status: BLOCKED — APK-to-source linkage not independently established**

## Known reported value

- Prior TASK 002.16 report recorded APK SHA-256: `4823d5a414afa3343be65e4bdb15c030ad86d29ffb487bcda3378fe270ebccd5`.
- This is a carried-forward report value only. The APK file was not available in this audit session for an independent hash calculation.

## Required chain (currently incomplete)

| Link | Required evidence | Current status |
|---|---|---|
| Source | Trusted checkout, exact commit SHA, tree SHA, clean worktree | Missing |
| Build | Workflow/run ID, workflow file revision, job logs, toolchain versions | Not bound to APK |
| Dependencies | Gradle dependency lock/verification; Python lock or exact resolution | Missing/incomplete |
| APK bytes | Artifact file and independently calculated SHA-256 | Not available here |
| APK metadata | applicationId, versionName/versionCode, signing certificate fingerprint | Not extracted |
| Source mapping | Build manifest/SBOM or embedded provenance with source SHA | Not verified |
| CI artifact | Artifact ID/name, producing run, run head SHA, retention/download evidence | Not linked |
| Device installation | ADB package info, installed APK path/hash or package dump tied to artifact | Missing |
| Runtime | Logs, process/service state, SQLite/recovery/scanner/gate evidence | Missing; see prior TASK 002.16 blocker |

## Gate

A CI success or an APK file by itself does not establish source provenance or device readiness. Do not mark PASS until the complete chain is independently verified.
