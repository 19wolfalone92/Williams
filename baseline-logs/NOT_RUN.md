# Baseline execution record — TASK 002.2

**Status: EXACT-HEAD RUN PENDING; local Git integrity NOT RUN.**

No local full checkout or source bundle was available in this audit container. Therefore no fabricated output is recorded for `git status`, `git fsck --full`, or `git bundle verify`.

## Remote CI records

- Previous successful backend/campaign/Android jobs checked out merge SHA `0330466f75d17cec1a13cd931563f402e394dbb0`, not the isolated target branch SHA `0189f85f3268dc96f4950f700628376d71cd57d4`.
- Android workflow was changed in commit `b109a3b1351e8e7862c42aab056f164bd1916570` to checkout `${{ github.event.pull_request.head.sha || github.sha }}`, use Gradle 8.14.6 and invoke `./gradlew`.
- New runs at last poll: Android `38144417385`, backend `38144417350`, campaign `38144417360`. State: QUEUED; no exit codes or test totals available yet.

## Commands/results

| Command/check | Exact source | Exit code | Result |
|---|---|---:|---|
| `git bundle verify` | N/A — no bundle | NOT RUN | BLOCKED |
| `git fsck --full` | N/A — no full object database | NOT RUN | BLOCKED |
| Clean worktree / refs | N/A — no local checkout | NOT RUN | UNKNOWN |
| Backend `python -m pytest -q` | merge SHA `0330466...` | CI success | 158 passed, 4 warnings; not exact-head evidence |
| Campaign safety/campaign tests | merge SHA `0330466...` | CI success | 169 passed, 4 warnings; not exact-head evidence |
| Exact-head APK workflow | Intended to checkout PR head | PENDING | Run `38144417385` queued at last poll |

Capture full logs, environment, stdout/stderr and exit codes when the new runs complete. Do not combine results from different source SHAs.
