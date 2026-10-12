# TASK 001.7 — Exact-HEAD CI Recovery

- PR #66: OPEN, Draft, unmerged; base `fix/pytest-safe-collection`.
- Required starting SHA `f9d96b98c639c5a7a6232f4ef9a5f15be3342cb4` matched the live PR head at task start.
- Code change SHA: `e5f82ae60b0b5c1e9512f9efe3bedc1d018ab132`. Mutation-boundary regression test SHA: `1ef8b13575bc5b728c8447b0cd92b54c24e89504`.
- Python backend run `38276014288`, tested SHA `e5f82ae...`: SUCCESS; job `backend-test` concluded success.
- Williams Campaign CI run `38276014293`, tested SHA `e5f82ae...`: Python tests job SUCCESS; Android build/static-check job was still in progress at last query. Do not treat the full workflow as passed until that job completes.
- Android APK run `38276015940`, tested SHA `e5f82ae...`: `startup_failure`; jobs endpoint returned no jobs and artifacts endpoint returned no artifacts. The same happened on runs `38274816706` and `38274869085` for prior SHAs.
- The available GitHub rerun endpoint returned HTTP 403: “This workflow run cannot be retried.” There is no failed job object to rerun.
- This is not a skipped job, dependency failure, Gradle build failure or lint/test failure: GitHub records a pull_request workflow run, but no job was created and no runner steps/logs/artifacts exist. The exposed API does not identify the deeper scheduler/platform cause; that remains BLOCKED BY EXTERNAL DEPENDENCY.
- `.github/workflows/android-apk.yml` contains PR path filters and configured Android unit tests, lint, debug/release APK existence checks, package metadata inspection, signature verification, SHA-256 checksums, source-SHA provenance and artifact upload. Configuration presence does not prove those steps ran.
- Exact-SHA Android tests/lint/APK/signature/checksum remain NOT VERIFIED. Prior-SHA green runs are not substituted.
