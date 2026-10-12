# TASK 001.3 — Operation identity contract

**Status: PARTIAL — P0 OPEN.** Implementation/test source SHA: `74f12c595181d0b4362fa02f2b2de436db9a751c`.

## Implemented
- `MutationIdentity.kt` defines `LogicalMutationIdentity`, separating logical operation ID from attempt ID and representing operation type, campaign/signal references, exchange, symbol, mutation type, canonical fingerprint, state, client IDs, timestamp and attempt ID.
- Unsigned form parameters are percent-decoded, sorted by exact parameter name and deterministically encoded. Symbol and enum values are normalized; supported decimal fields use `BigDecimal.stripTrailingZeros().toPlainString()` and reject non-positive or malformed values.
- Exact transport-only fields `timestamp` and `recvWindow` are excluded. Duplicate parameters and credential/signature/authorization fields are rejected.
- The sink uses canonical fingerprints and fails closed if a mutation has no stable client/target key instead of inventing a UUID fallback.
- OCO protection IDs are now deterministic from a stable position/entry/replacement context and the canonical economic request. `W4O_` IDs are 36 characters or fewer and distinct logical contexts produce distinct IDs.

## Tests
Identity tests cover ordering/encoding/decimal normalization, absent versus empty values, duplicate rejection, credential/signature rejection, distinct logical operations, stable retry identity, changed economics, invalid decimals and OCO identity. The full Android unit-test task completed successfully on source SHA `f51392ad42c440e52a5bfb1f92d84f0d7564ae4c`; that run then failed at lint. The subsequent ledger transaction refactor at `74f12c5` has **NOT YET BEEN VERIFIED** by CI.

## Remaining blockers
Campaign/signal fields are optional and not passed by every caller; multi-attempt history is not implemented. Gate A remains **PARTIAL**.
