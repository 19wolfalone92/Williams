# TASK 001.5 — Android CI and APK Verification

**Executable source SHA:** `cf35f2fb8d2f68770f191efaa272864e7debe04c`  
**Campaign CI run:** `38268195164` — SUCCESS  
**Standalone Android workflow run:** `38268196664` — `startup_failure`, zero jobs.

## Diagnosed wrapper issue

The checked-in `gradle/wrapper/gradle-wrapper.jar` failed the official wrapper validator (checked-in SHA-256 observed earlier: `8607d05f0a36b0a10a8e0ce28b2f`, not the official Gradle 8.14.6 wrapper SHA-256). The CI now uses the pinned Gradle 8.14.6 distribution to regenerate the wrapper JAR, verifies the generated JAR against `ad16bca46cb71bb5887c546860d93992aeee35c1fdaf4a242b0a612375d937e0`, then executes `./gradlew`. The Gradle distribution SHA-256 is pinned in `gradle-wrapper.properties` as `7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24`.

The checked-in binary itself has not been replaced; CI regenerates it in the ephemeral runner before execution. Local use of the repository's original wrapper JAR remains a risk until the binary is replaced.

## Exact-SHA build result

- Exact source checkout: `cf35f2fb8d2f68770f191efaa272864e7debe04c`; APK provenance file confirms `source_sha=cf35f2fb8d2f68770f191efaa272864e7debe04c`.
- `./gradlew --version` after verified wrapper regeneration: PASS.
- `:app:testDebugUnitTest`: PASS (exact test count was not surfaced in the workflow log/artifact).
- `:app:lintDebug`: PASS.
- `:app:assembleDebug`: PASS.
- APK exists at debug variant path and was uploaded in artifact `11756315887`.
- APK SHA-256: `23e8cf4684ba81117516961f152eb09446aaa6930ac676f1e4c0a6143bc73bc7`. The artifact's checksum file matched this value.
- Signing: debug certificate; APK Signature Scheme v2 verified. Certificate SHA-256 digest: `88f93f81219769a22f081072955f1b4937a0e78c4572b11be18f6cf03442ed3d`.
- Lint report artifact: `11755463715`.
- Verified generated wrapper artifact: `11755682525`.

## Remaining

The separate `Build Android APK` workflow still ends in `startup_failure` before jobs are created. It provides no logs or independent APK result; Campaign CI is the successful exact-SHA build evidence. No real credentials or live exchange mutations were used.
