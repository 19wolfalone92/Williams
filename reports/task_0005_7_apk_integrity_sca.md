# TASK 000.5.7 — APK integrity and Android SCA

## Previously inspected artifact
- Workflow run: `38219693589` was reported as `startup_failure`; no job records were exposed.
- Campaign CI artifact ID: `11734160498`.
- ZIP SHA-256: `6b342b8c4f097ec6e1a4119c555cbbc366985cbe920d9e23fd0dd4b9beaa559e`.
- Embedded `app-debug.apk` SHA-256: `320fb6787643ae227b4422bb0ec78414cbbf5fd8a9831a4ce0adc4646c6396d7`.
- Extracted signer certificate fingerprint previously recorded as `6A:81:08:7D:A5:8D:1D:8D:72:40:7E:B9:AE:C3:09:09:07:03:B6:0C:D1:5B:6F:EA:00:E6:6B:E5:38:70:77:37`, subject `CN=Android Debug, O=Android, C=US`.
- `apksigner` was unavailable in the prior environment; cryptographic signature verification and comparison against an independently trusted release certificate were not completed. A debug certificate is not a trusted release-signing baseline.
- Previous APK string heuristic found no matches for selected common patterns, which is not a complete binary secret scan.
- Prior report notes `app/build.gradle.kts` disables GradleDependency lint; lint is not dependency SCA evidence.

## Remaining checks
APK provenance to an independently verified workflow/commit, `apksigner verify --verbose --print-certs`, trusted certificate comparison, Gradle dependency graph and vulnerability scanning remain unverified. No new APK was built or signed during this task.

## Status
**INCOMPLETE — EVIDENCE MISSING**.