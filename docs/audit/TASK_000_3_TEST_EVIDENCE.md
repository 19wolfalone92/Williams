# TASK 000.3 — Test Evidence

**Source SHA assessed:** `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`  
**Local tests:** Not run; no local checkout/test environment was available.  
**Exact required SHA test run:** Not established. The available connected GitHub Actions runs were triggered by the audit PR and their checkouts are PR merge refs, not the immutable required source commit.

## Latest observed CI executions

### Enforced Mutation Registry — run 38164010385, job 114542670323
- `python -m py_compile mutation_registry_gate.py test_mutation_registry_gate.py` — exit 0.
- `python -m pytest -q test_mutation_registry_gate.py` — **14 passed in 0.05s**, exit 0.
- `python mutation_registry_gate.py` — exit 0.
- Gate output: 6 registered endpoint records; no unregistered raw Python HTTP mutation transport found; Android autonomous execution lock present; `PASS WITH BOUNDED MODEL — independent review still required`.

The 14 negative fixtures include direct `requests.post`, helper-hidden direct mutation, alias-based mutator, alternate HTTP client, raw `Session.request`, identity-bypass direct transport, dynamic `_request`, unknown endpoint, endpoint absent from registry, evidence-free registry entry, sink moved to another module, and dynamic endpoint.

### Python backend tests — run 38164010382, job 114542670292
- Observed: **148 passed, 4 warnings in 4.75s**, exit 0.
- Workflow command includes `test_production_architecture.py`, mutation/execution-adjacent tests, risk/scanner/database tests, and integration/release-gate tests.
- Full command is available in the workflow log for run `38164010382`.

### Williams Campaign CI — run 38164010377, job 114542704330
Exact command: `python -m pytest -q` with explicit campaign and execution tests, including:
`test_production_architecture.py`, `test_campaign_execution.py`, `test_task0091_execution_barrier_fail_closed.py`, `test_task0092_atomic_execution_persistence.py`, `test_task0093_exchange_backed_recovery.py`, `test_task0094_account_identity.py`, `test_task0094_mutation_containment.py`.
Observed: **208 passed, 4 warnings in 6.52s**, exit 0.

### Android workflows
- Build APK run `38164010386`: still in progress at last observation.
- Campaign Android build/static checks job `114542704611`: still in progress at last observation.
- No Android result is used as proof of Python mutation-path coverage.

## Earlier CI executions
- Mutation registry run `38156886920`: 14 passed; bounded gate passed.
- Python backend run `38156886908`: 148 passed, 4 warnings.
- Campaign run `38156886916`: 208 passed, 4 warnings.
- APK run `38156886910`: successful.

## Revision caveat
The earlier inspected job logs checked out PR merge ref `c24e28defc38e19baf20c777a069e80b87c7aecf`. The latest audit-PR runs above are also PR-triggered runs, and the evidence available here does not establish that their checkout SHA equals required source SHA `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`. Treat them as supporting PR evidence, not exact-head verification.

## Mandatory objectives

| Objective | Evidence status |
|---|---|
| Registry validation | Passed on latest PR CI merge checkout |
| Negative unregistered/bypass fixtures | 14 tests passed on latest PR CI merge checkout |
| Production architecture enforcement | Backend and Campaign CI passed on latest PR CI merge checkout |
| Identity rejection | `test_task0094_account_identity.py` included in Campaign CI; source assertions inspected |
| Persistence failure before mutation | `test_task0091_execution_barrier_fail_closed.py` included; source asserts submit is not called when intent persistence fails |
| Ambiguous outcomes | Barrier timeout/result-persistence and exchange recovery tests included; full cancelReplace two-leg fault matrix remains unproven |
| Duplicate submission/fill handling | Same-client-ID barrier and atomic fill-deduplication tests included; no exactly-once claim |

These are actual remote test results and source assertions inspected. No local execution is claimed. Exact-required-SHA execution remains an exit criterion.

## Follow-up CI check (2026-10-11)

Latest PR-triggered runs were inspected after the evidence package was committed:
- Mutation registry run `38164010385`, job `114542670323`: `python -m py_compile mutation_registry_gate.py test_mutation_registry_gate.py` exit 0; `python -m pytest -q test_mutation_registry_gate.py` — 14 passed in 0.05s; `python mutation_registry_gate.py` exit 0 with `PASS WITH BOUNDED MODEL`.
- Python backend run `38164010382`, job `114542670292`: 148 passed, 4 warnings in 4.75s.
- Campaign CI run `38164010377`, job `114542704330`: `python -m pytest -q` — 208 passed, 4 warnings in 6.52s; included the campaign, barrier, atomic persistence, recovery, account identity and mutation containment tests.
- Android build runs `38164010386` and job `114542704611` were still in progress at last check.

These are actual remote log results. They are PR-triggered merge-checkout runs; the available logs do not establish that they executed on exact source SHA `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`. Local tests were not run. Exact-SHA execution remains outstanding.


## Follow-up verification — current PR head (2026-10-11)

The current PR metadata reports head SHA `6a6e47c798e4a2cc6e2d2a5245911a22788170fe`, while the immutable task source SHA remains `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`. Current-head PR-triggered workflows were subsequently observed as completed successfully:

- Mutation registry run `38164276135`: completed success; source-to-sink gate and negative-fixture steps succeeded.
- Python backend run `38164276136`: completed success; release contract gate, Python compile, full static safety audit and offline tests succeeded.
- Williams Campaign CI run `38164276128`: Python tests and Android build/static-check jobs completed success.
- Build Android APK run `38164276047`: completed success.

These newer results improve evidence for the current PR head, but they do not replace the required immutable-source test requirement. For example, the inspected backend and campaign logs for earlier run IDs `38156886908` and `38156886916` explicitly checked out merge ref `c24e28defc38e19baf20c777a069e80b87c7aecf` (merge of source `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44` into base `04da5f9c09572e99ff310be3a1908179165e528c`). Therefore those test results are merge-ref evidence, not an isolated checkout of the immutable source SHA. No local test run is claimed.

**Disposition unchanged:** exact-source validation remains unproven; cancelReplace two-leg recovery and complete mutation-caller reachability remain blockers.


## Follow-up verification — PR head 2518a69 (2026-10-11)

The current PR metadata reports head `2518a69f96a41f74a18445017c666855b420b4f1`; required immutable source SHA remains `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`. PR #51 is open, Draft, and unmerged.

Latest observed PR-triggered runs:
- Mutation Registry run `38170850953`, job `114562958921`: **14 passed in 0.04s**; gate output reports six registered endpoint records, no unregistered raw Python HTTP mutation transport, Android autonomous execution lock present, and `PASS WITH BOUNDED MODEL — independent review still required`.
- Python backend run `38170851141`, job `114562959875`: **148 passed, 4 warnings in 5.10s**.
- Williams Campaign CI run `38170850975`, Python job `114562958576`: **208 passed, 4 warnings in 6.66s**. At the time of this check, its Android build/static-check job `114562958893` was still in progress.
- Build Android APK run `38170851029`, job `114562959022`: still in progress at the time of this check; lint was running and APK/provenance steps had not completed.

**Checkout SHA verified from job logs:** the mutation-registry, backend and campaign Python jobs checked out merge commit `e5bf8466760c4583e272d339036f59a4dfae03ef`, with log text: `Merge 2518a69f96a41f74a18445017c666855b420b4f1 into 04da5f9c09572e99ff310be3a1908179165e528c`. Therefore these successful tests are evidence for the PR merge checkout, not an isolated test of required SHA `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`. The exact-source test requirement remains unmet. No local tests were run.

**Disposition remains BLOCKED:** green CI does not resolve cancelReplace two-leg recovery or prove complete production mutation-caller reachability. Do not merge PR #51.


## TASK 000.4 follow-up — immutable source inventory and evidence boundary

- Required audit source SHA: `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`.
- Immutable Git tree query returned **123 Python files**. Each path and blob SHA is now recorded in `TASK_000_4_PYTHON_FILE_INVENTORY.csv`; the per-file status explicitly distinguishes targeted inspection from not reviewed line-by-line.
- Targeted source review and limitations are documented in `TASK_000_4_EXECUTION_SAFETY_AUDIT.md`.
- No local Python tests were executed in this environment. No isolated test run on the literal required SHA has been verified. Previously observed successful PR CI jobs checked out merge commits, not the immutable source SHA; those runs are not exact-source evidence.
- The audit branch received documentation-only commits for the inventory and report. Its CI must be checked against the resulting head and the job logs must be inspected for the actual checkout SHA. That evidence is separate from exact-source validation.
- **Decision remains BLOCKED:** P1-1 cancelReplace two-leg recovery and P1-2 whole-repository barrier reachability remain open. No real exchange orders, Testnet trading, or trading-strategy changes were used/performed.


## TASK 000.5 revalidation — 2026-10-11

Issue #52 is open. The immutable source remains `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`; the branch HEAD before this documentation update was `d0c3a4fe71025f69e3356fa0d397b5544898d240`.

Latest observed PR workflows all completed successfully:
- Backend run [38171251079](https://github.com/19wolfalone92/Williams/actions/runs/38171251079): 148 passed, 4 warnings.
- Campaign CI run [38171251089](https://github.com/19wolfalone92/Williams/actions/runs/38171251089): 208 passed, 4 warnings; Android build/static-check job passed.
- Mutation registry run [38171251083](https://github.com/19wolfalone92/Williams/actions/runs/38171251083): 14 passed; gate reports `PASS WITH BOUNDED MODEL`.
- Android APK run [38171251094](https://github.com/19wolfalone92/Williams/actions/runs/38171251094): build, lint, unit tests, APK checks and provenance steps passed.

**Checkout SHA verified from logs:** these latest jobs checked out merge commit `bc8d2a88da81dece541cb36bb2cd00646ae21150`, merging branch HEAD `d0c3a4...` into base `04da5f9c09572e99ff310be3a1908179165e528c`.

The PR workflows associated with baseline SHA `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44` (runs [38156886908](https://github.com/19wolfalone92/Williams/actions/runs/38156886908), [38156886916](https://github.com/19wolfalone92/Williams/actions/runs/38156886916), [38156886920](https://github.com/19wolfalone92/Williams/actions/runs/38156886920), and [38156886910](https://github.com/19wolfalone92/Williams/actions/runs/38156886910)) checked out merge commit `c24e28defc38e19baf20c777a069e80b87c7aecf`, not the literal source SHA. No isolated baseline test run is proven. No local tests were run, and no new cancelReplace fault-injection or complete bypass suite was run.

The 123 baseline Python files were fetched and keyword/source-pattern scanned during TASK 000.5. This is broad static triage, not a complete line-by-line semantic review. See `TASK_000_5_EXECUTION_SAFETY_REMEDIATION.md`.

**Disposition remains BLOCKED:** cancelReplace two-leg recovery, global mutation-barrier enforcement, exhaustive negative tests, and isolated baseline SHA testing remain unproven.
