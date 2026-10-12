# TASK 001.5 — Android CI and APK Verification

- Audited executable-source SHA: `b2b1a78c9d43a30e077d936e65a9314ddddcfcdf`
- APK status: NOT VERIFIED.

## Workflow diagnosis

- Standalone Android workflow run `38264563243` on this SHA ended in `startup_failure` and exposed zero jobs. This is a workflow startup failure, not evidence of a Kotlin compile result.
- The separate Campaign CI Android job was still running at the last recorded check.
- An earlier Campaign CI run on starting SHA `8a5ea866a6d7fa10960aca25b2123a34c11601c0` did execute Gradle and failed Kotlin compilation. Logs identified `DurableMutationLedgerTest.kt` references outside the class scope (`Unresolved reference 'ledger'` at line 581 and following). The misplaced class terminator was removed and the class closing brace restored.

## Required final checks

- Confirm Campaign CI result on the audited SHA.
- Obtain Gradle wrapper validation, Android lint, unit test and APK build results.
- Confirm artifact exists, expected variant, source SHA provenance, SHA-256 and signing information.
- The standalone `startup_failure` still requires infrastructure-level diagnosis; no jobs/logs are available from that run.
- No APK is claimed verified, and no APK hash/signature is claimed.
