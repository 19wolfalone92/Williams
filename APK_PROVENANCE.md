# APK_PROVENANCE — TASK 006

**Status: BLOCKED — trusted source provenance and physical-device match not established**  
**Artifact retrieval:** successful from GitHub Actions workflow run `38143567123` (run #2117) on branch `audit/williams-2.1-task-006-android-runtime`, head SHA `dc634573eb26c491d125feecd158319f1f5daa79`.

## Retrieved artifact and independently computed hashes

- Artifact name: `williams-bot-release-unsigned-2117`
- GitHub artifact ID: `11703862596`
- GitHub-reported artifact digest: `sha256:394ff75cf04a4f4cbbf458b7df311f83fd3e760c255940a1a89395e377e781ad`
- Retrieved ZIP SHA-256 (independently computed): `394ff75cf04a4f4cbbf458b7df311f83fd3e760c255940a1a89395e377e781ad`
- ZIP member: `app-release-unsigned.apk`
- APK SHA-256 (independently computed): `08fdd472bcb6ab0fc88f3ca03593636582d76beea4ca2e981dd27bf8e88ccad1`
- Included checksum file matched the computed APK SHA-256.
- Artifact ZIP size: 14,192,065 bytes.
- APK size: 14,842,560 bytes.
- Artifact created: 2026-10-11 13:37 UTC (workflow metadata).

These hashes describe the retrieved workflow artifact and its APK. They do not establish that the APK is the previously registered APK or the one installed on any physical device.

## Previously registered reference

Historical reference SHA-256:

`4823d5a414afa3343be65e4bdb15c030ad86d29ffb487bcda3378fe270ebccd5`

The newly retrieved APK hash is different. The two artifacts must not be treated as identical. The origin of the earlier reference artifact remains unresolved.

## Verification matrix

| Item | Result | Evidence/limitation |
|---|---|---|
| Actual APK artifact acquired | PASS | Retrieved from workflow artifact ZIP |
| ZIP SHA-256 independently computed | PASS | Matches GitHub artifact digest |
| APK SHA-256 independently computed | PASS | Matches included checksum file |
| Workflow run/head SHA association | PARTIALLY VERIFIED | GitHub artifact metadata associates run 38143567123 with head SHA `dc634573eb26c491d125feecd158319f1f5daa79`; not an independently signed provenance attestation |
| applicationId | NOT RUN | Android manifest tooling unavailable in this runtime |
| versionName/versionCode | NOT RUN | Android manifest tooling unavailable in this runtime |
| Signature/certificate | NOT RUN | Artifact is explicitly named unsigned; no signed release certificate to verify |
| Build variant | PARTIALLY VERIFIED | Artifact name and path identify release unsigned APK; complete build configuration not independently verified |
| Exact trusted source provenance | BLOCKED | Trusted checkout/bundle and independent build provenance unavailable |
| Reproducible build | NOT RUN | Requires trusted source and controlled Android toolchain |
| Installed APK matches artifact | NOT RUN | No physical device/ADB evidence |

## Environment observations

The retrieved ZIP was inspected with `unzip -l`; the APK and checksum file were present. The environment had Git available, but `adb`, `aapt`, and `apksigner` were not found on PATH. No physical device was attached in the available environment.

## Required next steps

1. Obtain and validate trusted source bundle/checkout; record its provenance, SHA-256, refs, HEAD, parents, `git bundle verify` where applicable, `git fsck --full`, and `git status`.
2. Use a trusted Android SDK/build-tools environment to extract application ID, versionName/versionCode, SDK metadata, and inspect APK signing state.
3. Build from the trusted source SHA and compare the resulting artifact under a documented reproducibility policy.
4. On the physical device, capture package metadata and compare the installed artifact to the APK SHA above.
5. Preserve the workflow run metadata and artifact digest as supporting evidence, not as a substitute for source trust or physical-device evidence.

## Conclusion

Artifact retrieval and hashing are now complete for the unsigned APK produced by workflow run #2117. **APK provenance remains BLOCKED** because the trusted source/build linkage, package metadata/signature inspection, reproducible build comparison, and installed-device match have not been established. No physical-device test is claimed.
