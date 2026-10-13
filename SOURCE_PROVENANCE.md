# TASK 002.2 — Source Provenance

**Status: BLOCKED — trusted-source chain incomplete**  
Audit timestamp: 2026-10-11 UTC. Repository refs and workflow evidence were queried through the GitHub connector; the APK bytes were downloaded from the GitHub Actions artifacts and inspected in the audit container.

## Ref snapshot

- Repository: `19wolfalone92/Williams`
- Target branch: `audit/williams-2.1-task-002-release-contract`
- Target branch HEAD at audit start: `0189f85f3268dc96f4950f700628376d71cd57d4`
- `main`: `04da5f9c09572e99ff310be3a1908179165e528c`
- PR: #45, open, base `main`, head SHA `0189f85f3268dc96f4950f700628376d71cd57d4`
- PR merge ref at audit: `0330466f75d17cec1a13cd931563f402e394dbb0`
- Merge commit message: `Merge 0189f85f3268dc96f4950f700628376d71cd57d4 into 04da5f9c09572e99ff310be3a1908179165e528c`

The commit relationship is supported by PR metadata and the checkout log. The three current successful workflow jobs checked out the merge ref `0330466...`, not the isolated branch commit `0189f85...`.

## Trust and Git integrity

| Check | Result | Evidence |
|---|---|---|
| GitHub repository/ref metadata | Retrieved | GitHub API, branch search, PR #45 |
| Commit SHA/parent relationship | Partially verified remotely | PR metadata and CI checkout log |
| Full local clone/worktree and clean status | NOT RUN | No local Git checkout of the repository was created |
| `git bundle verify` | NOT RUN | No trusted bundle supplied |
| `git fsck --full` | NOT RUN | No full local Git object database available |
| Source archive/bundle SHA-256 | NOT APPLICABLE / NOT CREATED | No source archive or bundle used |
| Independent source identity outside GitHub account | NOT ESTABLISHED | No signed tag, trusted commit signature, or independent source attestation verified |

A successful CI run or a Git object hash does not, by itself, prove that the repository origin is trusted. The current chain trusts the GitHub repository and its Actions metadata but lacks independent origin attestation.

## Exact-commit CI distinction

Runs `38142664759` (backend), `38142664806` (campaign plus Android static/build job), and `38142664769` (APK build) report PR head metadata `0189f85...`; their job logs explicitly checkout merge SHA `0330466...` with `fetch-depth: 1`. Test/build results are therefore evidence for that merge tree only. They must not be labelled tests of the isolated branch tree.

## Decision

Source provenance remains **BLOCKED** until the workflow builds/tests the intended exact source SHA and the release artifacts can be traced to that exact tree. The current audit did not modify `main`, merge the PR, or submit any Binance orders.
