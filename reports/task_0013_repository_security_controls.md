# TASK 001.3 — Repository security controls

**Status: PARTIAL / NOT VERIFIED.**

Inspected `.github/workflows/android-apk.yml` at the starting SHA. It declares `permissions: contents: read`; third-party actions in the inspected workflow use full commit-SHA pins. Persistent signing-key restoration is conditioned on trusted `main`/ `master` refs, while pull-request debug assembly has no signing secret in its step environment. The workflow includes lint, Android unit tests, debug/release assembly, APK metadata/signature checks, checksums and artifact upload steps, but the standalone run at the starting SHA failed before job creation.

Not verified through the available integration: repository branch protection/ruleset administration, required-check policy, secret scanning settings, artifact retention policy outside visible workflow definitions, and all workflows' effective permissions. No workflow permissions were changed. No real credentials or live order mutations were used.
