# TASK 001.3 — Repository security controls

**Status: PARTIAL / NOT VERIFIED.**

The inspected `.github/workflows/android-apk.yml` declares `permissions: contents: read`; third-party actions in that workflow use full commit-SHA pins. Persistent signing-key restoration is conditioned on trusted `main`/`master` refs, while pull-request debug assembly does not receive signing secrets. The workflow includes lint, unit tests, APK assembly, metadata/signature checks, checksums and artifact upload.

Standalone APK run `38247804640` failed with `startup_failure` before job creation; jobs and artifacts were absent. A prior retry attempt returned HTTP 403 (“This workflow run cannot be retried”). The root cause is not established; permissions were not broadened. The workflow supports `workflow_dispatch`, but the connected GitHub integration exposes no dispatch action; an authorized repository maintainer should use Actions → Build Android APK → Run workflow on the PR branch without changing permissions.

Branch-protection API returned 403; repository rulesets endpoint returned an empty list, which does not prove branch protection is absent. Required-check policy, secret scanning, retention policy and effective permissions across all workflows remain **NOT VERIFIED**. No live orders or real credentials were used.
