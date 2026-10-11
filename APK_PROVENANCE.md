# TASK 002.2 — APK Provenance

**Status: APK bytes and hashes verified; source provenance/signature acceptance BLOCKED**

## Source/build relation

- Target branch HEAD at audit start: `0189f85f3268dc96f4950f700628376d71cd57d4`.
- Main: `04da5f9c09572e99ff310be3a1908179165e528c`.
- Build checkout: merge SHA `0330466f75d17cec1a13cd931563f402e394dbb0`.
- Android workflow run: [38142664769](https://github.com/19wolfalone92/Williams/actions/runs/38142664769), job `114479536954`.
- Job log explicitly shows `git checkout refs/remotes/pull/45/merge`, then `HEAD is now at 0330466...`.

The artifacts are not proven to have been built from the isolated target-branch tree. The workflow's artifact metadata has head SHA `0189f85...`, but actual build checkout is the merge commit. Keep this distinction explicit.

## Downloaded artifacts and independent hashes

Artifact ZIP SHA-256 values independently computed in the audit container match GitHub artifact metadata digests. APK SHA-256 values independently computed over the extracted APK bytes also match the checksum files inside each ZIP.

| Variant | Artifact ID / name | ZIP size | ZIP SHA-256 | APK size | APK SHA-256 |
|---|---|---:|---|---:|---|
| Debug | `11703611504 / williams-bot-debug-apk-2114` | 20,638,760 | `482d9f353599be4a78452c0c2d60b5f470e0aa9ee35529c319f4768da3b55912` | 21,312,601 | `9f7c0ad5dba71fb8a2761727902b2ddeade99b09b19424760a956d8753e7ae55` |
| Release unsigned | `11703805990 / williams-bot-release-unsigned-2114` | 14,176,552 | `95eabc91cf7503ea0eaeb7ae9999a5bf57199a38f85ac06929ac6c25eb9aff2b` | 14,842,560 | `340a1eeccd3dae45685de0fde4a1191e3a483912e5e7996a35fcf318094e5a3e` |

Artifacts are associated by GitHub metadata with target branch head `0189f85...`; build content is from merge SHA `0330466...`.

## Package/version metadata (from CI aapt output)

| Variant | applicationId | versionName | versionCode | Metadata gate |
|---|---|---|---:|---|
| Debug | `com.williamsbot.debug` | `4.25.0-debug` | 2114 | PASS in CI |
| Release unsigned | `com.williamsbot` | `4.25.0` | 2114 | PASS in CI |

## Signing

- Debug APK: CI reports APK Signature Scheme v2 verification succeeded, one signer; workflow compares signer certificate SHA-256 with the certificate derived from the configured persistent debug keystore. The secret itself is not disclosed.
- The audit container does not have `apksigner` or Android SDK build-tools available, so signature verification was not independently repeated locally and the certificate fingerprint could not be independently extracted.
- Release artifact is explicitly `app-release-unsigned.apk`; it is **not a signed release candidate**. This is a release-blocking condition if a signed installable release is required.
- JAR-level `jarsigner` output is not a valid substitute for Android APK Signature Scheme v2 verification.

## Reproducibility conclusion

APK bytes are now available and independently hashed, but the provenance chain stops at the merge tree rather than the isolated target SHA. The old recorded APK hash `4823d5a4...` is not the SHA of the current debug APK; it belongs only to a previous record and must not be reused. Current APKs are not declared trusted or release-ready.
