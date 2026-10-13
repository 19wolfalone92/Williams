# TASK 001.5 — Operation Identity

**Executable source SHA:** `cf35f2fb8d2f68770f191efaa272864e7debe04c`  
**Status:** PARTIALLY IMPLEMENTED.

## Implemented

- `operation_id` is the SQLite primary key and is distinct from request fingerprint, client order ID, exchange order ID, and attempt ID.
- A conflicting fingerprint for an existing operation ID is rejected.
- Client order ID generation requires explicit logical operation identity and no longer derives identity solely from economic request parameters.
- Canonicalization rejects normalized duplicate keys, including case aliases.
- `timestamp` and `recvWindow` are excluded case-insensitively; credential/signature-like parameters are rejected.
- Schema version 4 adds a partial unique index for new POST/OCO/cancel-replace client IDs per exchange and symbol. DELETE rows are excluded because they refer to an existing order's client ID.
- Stored mutation fields include kind, symbol, fingerprint, exchange client ID, exchange order ID, state, timestamps, exchange, campaign/signal IDs, attempt ID, and reconciliation evidence JSON.

## Current identity semantics

- New order: caller-supplied client ID is the logical key; absent logical identity fails closed.
- Cancel: identity includes the DELETE route and target order/client ID.
- OCO: list client ID identifies the list-level operation.
- Cancel-replace: generated client identity is bound to the logical target/action; changed request economics under the same operation are rejected by the ledger fingerprint.

## Guarantees not established

- `attempt_id` is a durable field, but there is no append-only record for every individual HTTP transmission.
- Cancel-replace's cancel and replacement outcomes are not independently reconciled end-to-end.
- Caller-to-ledger coverage is not proven for every path.
- The raw JSON evidence object is validated for required fields but is not a typed, cryptographically unforgeable exchange attestation.

Android unit tests on the audited SHA passed as part of Campaign CI. No exactly-once guarantee is claimed.
