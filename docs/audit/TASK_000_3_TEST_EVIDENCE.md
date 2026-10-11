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