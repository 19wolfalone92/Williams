# APK_PROVENANCE — TASK 006

**Status: BLOCKED — APK artifact and trusted build provenance not available in this run**

## Reference record

Previously recorded reference SHA-256:

`4823d5a414afa3343be65e4bdb15c030ad86d29ffb487bcda3378fe270ebccd5`

This value is a historical reference only. It was not recomputed against a retrieved APK during this run.

## Verification matrix

| Item | Result | Evidence/limitation |
|---|---|---|
| Actual APK artifact acquired | NOT RUN | No APK file available in this runtime |
| SHA-256 independently computed | NOT RUN | Reference hash not independently verified |
| applicationId | NOT RUN | Requires APK manifest/tooling |
| versionName/versionCode | NOT RUN | Requires actual APK metadata |
| Signing certificate / signer digest | NOT RUN | Requires actual APK and signature inspection |
| Build variant and build configuration | NOT VERIFIED | Workflow/source metadata alone does not identify the installed APK |
| Git source SHA used to build artifact | BLOCKED | No verified provenance statement/artifact association |
| Reproducible build comparison | NOT RUN | Requires trusted checkout, toolchain and actual APK |
| Installed device APK matches inspected artifact | NOT RUN | No device evidence |

## Required provenance procedure

1. Obtain the APK as an actual file and record its acquisition source.
2. Compute `sha256sum <apk>` and preserve the command output/exit code.
3. Inspect package metadata (application ID, version name/code, min/target SDK).
4. Verify signer/certificate digest using Android build tools; record tool version and output.
5. Record the exact source commit, workflow run, build variant, Gradle/JDK/SDK versions and artifact digest in build provenance.
6. Rebuild from the trusted source in a controlled environment and compare the output. If byte-for-byte reproducibility is not expected, document the build-system reason and compare the signed/unsigned artifact metadata and content under a defined reproducibility policy.
7. On the physical device, record installed package/version and independently pull/inspect the installed APK where permitted; compare its digest with the tested artifact.
8. Never infer provenance from matching version strings or CI green status alone.

## Conclusion

The reference hash is not proof that the APK matches branch `audit/williams-2.1-task-006-android-runtime`, commit `36c22327774b2bd3c0a3df4130cb10cc0e483766`, or main. Provenance remains BLOCKED until the artifact and source/build linkage are independently verified.
