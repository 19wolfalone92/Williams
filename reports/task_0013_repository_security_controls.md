# TASK 001.3 — Repository security controls

**Status: PARTIAL / NOT VERIFIED.**

The inspected `.github/workflows/android-apk.yml` declares `permissions: contents: read`; third-party actions in that workflow use full commit-SHA pins. Persistent signing-key restoration is conditioned on trusted `main`/`master` refs, while the pull-request debug build step does not receive signing secrets. Workflow source includes lint, unit tests, APK assembly, metadata/signature checks, checksums and artifact uploads.

Current standalone APK run `38244760292` failed with `startup_failure` before job creation; the jobs endpoint returned zero jobs and no artifact. A rerun attempt on prior run `38244633544` was denied with HTTP 403. Root cause is not established, and permissions were not broadened.

Not verified through the available integration: repository branch protection/ruleset administration, required-check policy, secret-scanning settings, and effective permissions across all workflows. No real credentials or live order mutations were used.
