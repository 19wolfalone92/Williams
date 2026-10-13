# TRUSTED BASELINE MANIFEST — WILLIAMS BOT 2.1

**Prepared:** 2026-10-11  
**Purpose:** controlled integration inventory; not release approval.  
**Safety:** no live trading operations performed. `main` was not modified.

## 1. Confirmed base

- Repository: `19wolfalone92/Williams`
- Default branch observed from GitHub API: `main`
- Exact `main` HEAD observed: `04da5f9c09572e99ff310be3a1908179165e528c`
- Parent of `main`: `3fe008d85c06c90ed00d8035484b22aae657317b`
- `main` commit message: `Update README.md` (2026-10-09T17:30:49Z)
- Commit signature reported verified by GitHub.
- `main` protection: disabled in the API response; do not treat this as a safety guarantee.
- Integration branch was absent from the branch listing and was created from the exact main SHA above:
  `audit/williams-2.1-task-009-integration`.
- The integration branch currently contains this manifest and will receive the change map next. No changes were made to `main`.

## 2. Task branch heads observed

All SHAs below are exact GitHub branch HEADs observed during this inventory, not inferred from PR titles.

| Workstream | Branch | HEAD | Relationship / notes |
|---|---|---|---|
| TASK 001 architecture | `audit/williams-2.1-task-001-architecture` | `c599abaf4c4f345c6f113693d75dd732b282ab93` | 143 commits ahead of main; no behind commits in compare |
| TASK 002 release contract | `audit/williams-2.1-task-002-release-contract` | `7fbe11b4ea1eb9b114f5ca8758ee2830877b5f0d` | 22 ahead, 0 behind |
| TASK 002 device evidence | `audit/williams-2.1-task-002-16-device-evidence` | `658413dde1f021cd4a3c262ad465c363c460989a` | HEAD observed; CI not checked in this pass |
| TASK 003 execution safety | `audit/williams-2.1-task-003-execution-safety` | `cd22ddfe7931377fe168bdb5411a6b438e47a5b9` | 164 ahead, 0 behind |
| TASK 004 Williams strategy | `audit/williams-2.1-task-004-williams-strategy` | `9ec717ac7b3a1bf7735f57ab508ca85100af2f3b` | 164 ahead, 0 behind |
| TASK 005 risk management | `audit/williams-2.1-task-005-risk-management` | `799941744e1f0b822dc779a653441de828914bc3` | 79 ahead, 0 behind |
| TASK 006 Android runtime | `audit/williams-2.1-task-006-android-runtime` | `7c642e1f447e540819778ba0e3694b0d15377493` | 204 ahead, 0 behind |
| TASK 007 recovery / ledger | `audit/williams-2.1-task-007-recovery` | `e7ab3e72a0a7a85823ec898da28a1761476b84de` | 147 ahead, 0 behind |
| TASK 008 Spot Testnet | `audit/williams-2.1-task-008-spot-testnet` | `7c4514d22ebca19861de2183317f7c39f3263e0c` | 128 ahead, 0 behind |
| Futures safety | `audit/williams-2.1-futures-safety` | `40a82056dee88a9c79e799912b07a8ddb1a1859b` | PR #42 head; compare diverged: 728 ahead, 1 behind |
| PR #43 risk branch | `fix/task-007-risk-management` | `c4892b6ab08aafcf75485be2e548eb767cd571df` | Separate/older line; 12 ahead, 1 behind |
| PR #44 strategy/safety patch | `fix/williams-fractal-stop-provenance-20261010` | `dbe755a942276ac3b369afd47d424fff48bbb608` | PR #44 is based on Futures branch SHA `80fbafd8b7899a70c8039eac5045f3d4af25baa1`, not current main; compare to main diverged 950 ahead, 1 behind |

## 3. PR #42–#50 exact heads and state

| PR | Head SHA | Base ref / SHA recorded by GitHub | State | Integration implication |
|---|---|---|---|---|
| #42 Futures LONG/SHORT | `40a82056dee88a9c79e799912b07a8ddb1a1859b` | `main` / `3fe008d85c06c90ed00d8035484b22aae657317b` | Open, Draft, unmerged; mergeable=false | Older base; broad Futures/runtime/strategy/execution changes; not safe to merge as one block |
| #43 TASK 007 risk hardening | `c4892b6ab08aafcf75485be2e548eb767cd571df` | `main` / `3fe008d85c06c90ed00d8035484b22aae657317b` | Open, Draft, unmerged | Older branch and overlaps risk/ExecutionBarrier with #50 and newer TASK 007 |
| #44 Williams fractal/Alligator/stop provenance | `dbe755a942276ac3b369afd47d424fff48bbb608` | `audit/williams-2.1-futures-safety` / `80fbafd8b7899a70c8039eac5045f3d4af25baa1` | Open, non-draft, unmerged; mergeable=false | Based on a specific older Futures SHA; contains broad duplicated changes also found in #42/#49 |
| #45 version/release gate | `7fbe11b4ea1eb9b114f5ca8758ee2830877b5f0d` | `main` / `04da5f9c09572e99ff310be3a1908179165e528c` | Open, non-draft, unmerged | 22 commits ahead; isolated release/version contract candidate |
| #46 Android runtime | `7c642e1f447e540819778ba0e3694b0d15377493` | `main` / `04da5f9c09572e99ff310be3a1908179165e528c` | Open, Draft, unmerged | 204 commits ahead; overlaps shared runtime/DB/execution files |
| #47 architecture | `c599abaf4c4f345c6f113693d75dd732b282ab93` | `main` / `04da5f9c09572e99ff310be3a1908179165e528c` | Open, Draft, unmerged | 143 commits ahead; overlaps ExecutionBarrier, campaign execution, DB, Android runtime |
| #48 execution safety | `cd22ddfe7931377fe168bdb5411a6b438e47a5b9` | `main` / `04da5f9c09572e99ff310be3a1908179165e528c` | Open, Draft, unmerged | 164 commits ahead; broad changes to execution/DB/runtime |
| #49 strategy | `9ec717ac7b3a1bf7735f57ab508ca85100af2f3b` | `main` / `04da5f9c09572e99ff310be3a1908179165e528c` | Open, Draft, unmerged | 164 commits ahead; overlaps #44 and shared execution/risk/runtime paths |
| #50 risk management | `799941744e1f0b822dc779a653441de828914bc3` | `main` / `04da5f9c09572e99ff310be3a1908179165e528c` | Open, Draft, unmerged | 79 commits ahead; overlaps #43, #47–#49 and TASK 007 ledger |

No PR #42–#50 is merged according to the live PR metadata retrieved for this manifest. Therefore no change may be called “present in main because PR says so.” The current main tree remains the authority.

## 4. CI evidence observed for exact candidate SHAs

The workflow-runs connector returns PR-triggered runs for a commit and may return an empty list when it has no matching records; an empty list is **not** proof that no CI ever ran. Run IDs below were retrieved for the listed exact SHA.

| Candidate | SHA | Observed workflow results | Interpretation |
|---|---|---|---|
| PR #42 | `40a82056dee88a9c79e799912b07a8ddb1a1859b` | No runs returned | CI status unverified in this query |
| PR #43 | `c4892b6ab08aafcf75485be2e548eb767cd571df` | Python #37959101174 SUCCESS; Campaign #37959101112 FAILURE | Fails CI gate |
| PR #44 | `dbe755a942276ac3b369afd47d424fff48bbb608` | No runs returned | CI status unverified in this query |
| PR #45 | `7fbe11b4ea1eb9b114f5ca8758ee2830877b5f0d` | Python #38144490191 SUCCESS; Campaign #38144490200 SUCCESS; Android APK #38144490194 FAILURE | APK/release gate fails |
| PR #46 | `7c642e1f447e540819778ba0e3694b0d15377493` | Python #38144248125 SUCCESS; Campaign #38144248182 SUCCESS; Android APK #38144248176 SUCCESS | CI green for this SHA; does not prove device/recovery behavior |
| PR #47 | `c599abaf4c4f345c6f113693d75dd732b282ab93` | Python #38144602375 SUCCESS; Campaign #38144602412 SUCCESS; Android APK #38144602400 SUCCESS | CI green for this SHA; not integration acceptance |
| PR #48 | `cd22ddfe7931377fe168bdb5411a6b438e47a5b9` | Python #38133746146 SUCCESS; Campaign #38133746112 SUCCESS; Android APK #38133746122 SUCCESS | CI green for this SHA; not integration acceptance |
| PR #49 | `9ec717ac7b3a1bf7735f57ab508ca85100af2f3b` | Python #38144371672 SUCCESS; Campaign #38144371686 SUCCESS; Android APK #38144371730 SUCCESS | CI green for this SHA; does not prove causal strategy parity end-to-end |
| PR #50 | `799941744e1f0b822dc779a653441de828914bc3` | Python #38130451503 SUCCESS; Campaign #38130451509 SUCCESS; Android APK #38130451506 SUCCESS | CI green for this SHA; risk/recovery acceptance still blocked |
| TASK 007 current branch | `e7ab3e72a0a7a85823ec898da28a1761476b84de` | No runs returned | Exact-HEAD CI unverified |
| TASK 008 current branch | `7c4514d22ebca19861de2183317f7c39f3263e0c` | No runs returned | Exact-HEAD CI unverified |

Historical or prior-commit CI does not transfer to a newer SHA. The listed runs are not a substitute for a new integration-branch run.

## 5. Files / local environment limitations

This inventory used GitHub metadata, compare endpoints, PR metadata, and workflow-run records. No full local checkout was available in this execution, so `git status`, `git fsck --full`, bundle checks, local test execution, APK download/hash verification, and physical-device tests were not run. File lists in the change map are GitHub compare results against main, not claims that all changes are correct or mutually compatible.

## 6. Baseline decision

**Decision: TRUSTED BASELINE FOR CONTROLLED INTEGRATION = `04da5f9c09572e99ff310be3a1908179165e528c` (main snapshot).**

This is a source starting point, not a claim that production is safe or release-ready. Preserve all candidate branches. Integrate by minimal reviewed slices with tests; do not merge whole PRs mechanically. Keep Spot and Futures separate and retain Long/Short support in the shared Williams strategy architecture.
