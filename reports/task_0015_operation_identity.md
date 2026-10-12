# TASK 001.5 — Operation Identity

- Audited executable-source SHA: `b2b1a78c9d43a30e077d936e65a9314ddddcfcdf`
- Status: PARTIALLY IMPLEMENTED — contract still has gaps.

## Implemented

- `operation_id` is the SQLite primary key.
- Request fingerprint is stored separately and conflicting reuse of an operation ID is rejected by `prepare()`.
- Client order ID generation requires an explicit logical operation ID; request economics are validated but are not the sole source of client ID identity.
- Canonicalization rejects duplicate normalized parameter keys, including case aliases.
- `timestamp` and `recvWindow` are excluded case-insensitively; credential/signature-like fields are rejected.
- Ledger fields include mutation kind, symbol, fingerprint, client ID, exchange order ID, state, timestamps, exchange, campaign/signal IDs, attempt ID, and now reconciliation evidence JSON.
- SQLite schema is version 3. The new migration reopens legacy `RECONCILED` rows lacking evidence as `RECONCILING`.

## Identity rules observed in current code

- New order: caller-provided exchange client ID is used as the logical key; missing logical identity fails closed.
- Cancel: operation identity derives from the cancellation route and target identifier.
- OCO: list client ID is the logical identity where supplied.
- Cancel-replace: a generated client ID is tied to the logical target/action key and persisted request fingerprint rejects changed economics under the same operation.
- Fingerprint includes canonical method/path/symbol/unsigned parameters after documented transport-only exclusions.

## Remaining gaps

- The exchange-client-ID index is not a UNIQUE constraint. Collision protection relies on application identity generation and the operation primary key; database-level uniqueness for exchange client IDs is not proven.
- `attempt_id` is stored as a field but there is no append-only table for each actual HTTP transmission. This does not prove per-wire-attempt accounting.
- Cancel-replace's two outcomes must be reconciled independently; one local operation row does not itself prove both cancel and replacement legs.
- The contract has not been proven for every caller, restart path, OCO variant, or concurrent multi-process writer.

## Evidence

Android tests added for evidence-required closure, migration of unsupported legacy terminal state, and no automatic mutating timestamp retry. Those tests are not marked passed until the Campaign CI Android job completes successfully. Python suite on the audited source SHA: 219 passed, 4 warnings.
