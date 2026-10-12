# TASK 000.7.7 — Android Build and Signature Verification
Date: 2026-10-12 UTC

## Workflow evidence
- Run 38231995378: `startup_failure`, no jobs/steps.
- Run 38232066353: `startup_failure`, no jobs/steps.
- Run 38219693589: no jobs/steps exposed.
- Run 38232514514: `startup_failure`, no jobs exposed.
- Campaign run 38232513545 Android job succeeded on source HEAD `974596dbc72ea90d6bf1c08cc105151796440acf`: JDK/Gradle setup, debug APK build, Android unit tests/lint build step, APK upload and lint artifact upload all reported success. This is a distinct workflow from the startup-failing APK workflow.
- Artifacts: debug APK ZIP artifact ID `11739272090`, SHA-256 digest of ZIP `93a3efb9f6955250f13a8ec564d4275bfcbbfce0125e758f00e56e54c8e33f7d`; lint artifact ID `11739496929`, ZIP digest `eb8bc0bf10820cf71152a634c308de19b06be61d1b0bb1ecf911ea0eb01c7f63`.

Prior TASK 000.5 inspected a different APK artifact (ID `11734160498`): ZIP SHA-256 `6b342b8c4f097ec6e1a4119c555cbbc366985cbe920d9e23fd0dd4b9beaa559e`, embedded APK SHA-256 `320fb6787643ae227b4422bb0ec78414cbbf5fd8a9831a4ce0adc4646c6396d7`, certificate subject `CN=Android Debug, O=Android, C=US`. This is not a trusted release signature.

The exact startup_failure cause is unavailable because GitHub exposes no job records. Cryptographic verification of the newly produced artifact and comparison against an independently trusted release certificate have not been performed. Android/Gradle resolved dependency SCA remains incomplete.

Status: **BLOCKED — ACCESS REQUIRED** for startup failure diagnostics; **INCOMPLETE — EVIDENCE MISSING** for trusted signature and SCA.
