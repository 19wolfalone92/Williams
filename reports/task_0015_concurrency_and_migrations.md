# TASK 001.5 — Concurrency, SQLite Migrations and Recovery

- Audited executable-source SHA: `b2b1a78c9d43a30e077d936e65a9314ddddcfcdf`
- Status: PARTIAL; Android tests awaiting CI confirmation.

## Android changes

- SQLite ledger schema increased from version 2 to 3.
- Migration is additive and retains unresolved rows.
- Legacy `RECONCILED` rows without persisted evidence become `RECONCILING`, which remains in `unresolved()`.
- Transition updates remain transactional and compare the expected prior state.
- Added a Robolectric test that creates a v2 database with an unsupported terminal row and expects it to reopen unresolved.
- Existing tests cover conflicting prepare, duplicate prepare, and competing claims; the complete matrix across supported old versions is not yet proven.

## Python evidence

The current Python suite includes independent-connection claims, SQLite reopen, transaction rollback, and busy-timeout checks. The exact-SHA Python workflow completed successfully with 219 passed, 4 warnings.

## Remaining

- Execute Android schema migration and concurrent claim tests successfully on CI.
- Test every supported prior schema version, interrupted migration, corrupt/partial schema, and reopen after migration.
- Verify no migration can clear a barrier without evidence.
- Add repeated-recovery idempotency tests for each mutation kind.
