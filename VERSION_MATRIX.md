# TASK 002.1 — Version Matrix

**Status: BLOCKED — NOT ACCEPTED.** Values below distinguish repository configuration from values observed in CI logs.

| Layer | Repository configuration | Observed CI environment | Assessment |
|---|---|---|---|
| main backend version | `server.py` hardcodes `4.25.0` | Release gate prints `VERSION=4.25.0` on merge checkout | Same string observed, different source-of-truth mechanism |
| Working branch backend version | `VERSION=4.25.0`; `version_contract.py` reads it | Release gate prints `4.25.0` | Present and exercised in CI merge checkout |
| Android versionName | Build config reads VERSION; debug suffix `-debug` | Workflow has steps verifying APK version against canonical source | CI step success reported; metadata not independently extracted here |
| Android versionCode | `maxOf(10, GITHUB_RUN_NUMBER ?: 10)` | Run-specific CI value | Must record run number for reproducibility |
| Android Gradle Plugin | `8.13.2` | Android build successful | Config observed |
| Kotlin plugins | `2.3.21` | Android build successful | Config observed |
| Gradle wrapper | `8.14.6` in working branch | Workflow env provisions Gradle `8.14.5` | **Mismatch; resolve before PASS** |
| Android SDK | compile 36 / target 36 / min 26 | `platforms;android-36`, build-tools `36.0.0` | CI environment observed |
| JDK | Java/Kotlin target 17 | Temurin `17.0.20+1` | CI environment observed |
| Python backend job | No pinned interpreter in requirements | CPython `3.13.16` | Observed on backend workflow |
| Python Campaign job | No pinned interpreter in requirements | CPython `3.12.15` | **Different minor version from backend job** |
| Python dependencies | `requirements.txt` lower bounds, no lock confirmed | Example resolution: pandas 3.0.6; numpy 2.5.3; pytest 9.1.1; FastAPI 0.143.0; Pydantic 2.14.0; requests 2.34.2; cryptography 50.0.2 | Snapshot only; not locked/reproducible guarantee |
| Protocol compatibility | Not fully verified | No end-to-end protocol matrix evidence captured | OPEN |
| SQLite schema/migrations | Not fully verified | No migration test evidence captured | OPEN |
| APK metadata | Not independently extracted | Workflow steps report metadata inspection succeeded | PARTIAL; require artifact download and independent verification |

## Exact CI baseline evidence

The available CI jobs checked out merge SHA `89c866cfe63ac8a76f4ebb038d6166fafb745bd8`, not branch HEAD `bb6cd92ca7bb07369e26e77cab214b04229c3435` directly:
- Backend test job: `158 passed, 4 warnings in 4.32s`.
- Campaign test job: `169 passed, 4 warnings in 4.52s`.
- Android job: multiple Gradle tasks reported `BUILD SUCCESSFUL`; JDK Temurin 17.0.20+1, Gradle 8.14.5, Android platform 36, build-tools 36.0.0.

These results are CI evidence for the merge commit, not proof of exact-HEAD reproducibility. They do not prove device runtime.

## Remaining gate

- Full Git object verification and clean checkout: NOT RUN.
- Exact branch-head baseline: NOT RUN.
- Dependency lock and single Python version policy: OPEN.
- Gradle wrapper/workflow Gradle mismatch: OPEN.
- Protocol and SQLite schema migration contract: OPEN.
- APK SHA-256 and signature/package metadata independently checked: NOT RUN.
- Physical device test: NOT RUN.
