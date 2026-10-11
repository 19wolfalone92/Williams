# TASK 002 — Version Matrix (observed, not fully verified)

| Layer | main (`04da5f9c09572e99ff310be3a1908179165e528c`) | working branch (`bb6cd92ca7bb07369e26e77cab214b04229c3435`) | Assessment |
|---|---|---|---|
| Backend app/API version | `server.py`: hard-coded `4.25.0` | `server.py` imports `APP_VERSION`; `VERSION` file = `4.25.0` | Same observed string, different source of truth |
| Canonical version contract | Not confirmed in main `server.py` | `VERSION` + `version_contract.py` | Present in working branch |
| Android versionName | Read actual branch's `VERSION`; debug suffix `-debug` | `4.25.0` release / `4.25.0-debug` debug by Gradle config | Config only; not extracted from APK |
| Android versionCode | `maxOf(10, GITHUB_RUN_NUMBER ?: 10)` in inspected build file | Same observed rule | Non-reproducible unless CI run number is recorded |
| Android Gradle Plugin | `8.13.2` | `8.13.2` | Config observed |
| Kotlin plugins | `2.3.21` | `2.3.21` | Config observed |
| Gradle wrapper | `8.14.5` | `8.14.6` | **Mismatch** |
| Android compile/target/min SDK | compile 36 / target 36 / min 26 | compile 36 / target 36 / min 26 | Config observed |
| Java/Kotlin target | Java 17 / JVM target 17 | Java 17 / JVM target 17 | Config observed; installed JDK not verified |
| Python | No pinned interpreter version established | No pinned interpreter version established | **Unverified** |
| Python dependency resolution | `requirements.txt` lower bounds only | `requirements.txt` lower bounds only | **Not reproducible from requirements.txt alone** |
| Protocol version | Not established by this inspection | Not established by this inspection | **Open** |
| SQLite/schema migration version | Not established by this inspection | Not established by this inspection | **Open** |
| APK package/version/signature | Not independently extracted | Not independently extracted | **Open** |

## Evidence notes

- Files were read through GitHub API metadata/content, not from a trusted local checkout.
- Build configuration is not evidence of actual toolchain versions used by a particular artifact.
- Do not infer protocol or database schema compatibility from app/backend version equality.
- Release gate remains BLOCKED until lock files, runtime/build evidence, schema/protocol contracts, and artifact provenance are supplied.
