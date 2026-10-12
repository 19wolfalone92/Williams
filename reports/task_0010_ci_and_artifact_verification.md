# TASK 001.0 — CI and Artifact Verification
Date: 2026-10-12 UTC

## Exact baseline
- Required HEAD: `7c034dcb359a62cd8fedfa14f89f7072334c4900`; PR metadata confirmed this exact SHA.
- PR #66 OPEN/DRAFT/NOT MERGED; branch `night-shift-audit-20261012`; base `fix/pytest-safe-collection`.
- Campaign run `38235681955`, attempt 1, SHA `7c034dcb359a62cd8fedfa14f89f7072334c4900`: SUCCESS.
- Backend run `38235681761`, attempt 1, same SHA: SUCCESS.
- Standalone Android run `38235683357`, same SHA: `startup_failure`.

## Campaign run results
- Android job `114765650393`: SUCCESS. Steps Set up job, Checkout, JDK, Gradle, `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`, debug APK upload, lint report upload all succeeded. Log: `BUILD SUCCESSFUL in 3m 53s`.
- Python job `114765650676`: SUCCESS; 230 passed, 4 warnings (from the workflow's captured pytest output).
- Debug APK artifact ID `11740149967`; artifact ZIP SHA-256 `b1953f674cea0d8708d9d6939c374a256babf4f8813a22785a426d817bc0db80`.
- Extracted APK SHA-256 `ebcc15e6716ac4b3b861642d8b48f101276ab577856f215d70aa40edd071a709`.
- APK signer certificate parsed from the APK v2 signing block; certificate DER SHA-256 `04267e0692a265dbb985d010fcb35df601f347b2fdd235b92af602b7e3bbb832`. Subject/issuer: `C=US, O=Android, CN=Android Debug`. This identifies a debug cert only; independent `apksigner verify` could not be run in the available environment. No trusted release certificate fingerprint was supplied.
- Lint artifact ID `11740319681`; artifact ZIP SHA-256 `96bc1e3fb9540190418536c4038b033661d14238b3bb4914ea5bde4abe353d8f`.

## Backend run results
- Job `114765649674`: SUCCESS.
- Dependency audit: both configured Python requirements audit steps report `No known vulnerabilities found` at run time.
- Python compile/parse gate passed.
- Full configured Python suite: 219 passed, 4 warnings in 5.00s.
- This is evidence for Python dependencies only; it is not Android/Gradle SCA, and it does not establish hash-locked or reproducible Gradle dependency resolution.

## Standalone Android startup failure
- Run `38235683357`, workflow `.github/workflows/android-apk.yml`, SHA `7c034dcb359a62cd8fedfa14f89f7072334c4900`, event `pull_request`, conclusion `startup_failure`.
- No job records, runner steps, logs, or artifacts were exposed. Earlier runs `38234751836` and `38234530951` had the same failure shape. This supports only “failure before a job was created”; it does not distinguish runner allocation from workflow initialization/service-side failure.
- Owner action: GitHub Actions administrator must inspect service/runner diagnostics and trigger/rerun the standalone workflow on an exact SHA; do not change workflow permissions to force execution.

## Reproducibility and trust limitations
- Artifact is an installable **debug** APK, not a trusted release APK.
- No expected release certificate fingerprint/trust anchor; no independent APK signature verification.
- Android Gradle vulnerability/SCA evidence, dependency lock/integrity proof, and reproducible build proof remain absent.
- Current source SHA is the one built by Campaign CI. This report itself is documentation-only; it does not implement or test the proposed durable boundary.
