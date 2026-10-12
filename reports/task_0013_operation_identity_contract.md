# TASK 001.3 — Operation identity contract

**Status: PARTIAL — P0 OPEN.** Source evidence SHA: `efe5d12272e3cdd01ef48daeac4a56af6f685040`.

## Implemented
- `MutationIdentity.kt` now exposes a typed `LogicalMutationIdentity` model separating logical operation ID from attempt ID and including operation type, campaign/signal references, exchange, symbol, mutation type, canonical fingerprint, state, and client IDs.
- Unsigned form parameters are parsed, percent-decoded, sorted by exact parameter name, and re-encoded deterministically.
- Symbol and enumerated values are normalized; known decimal fields use `BigDecimal.stripTrailingZeros().toPlainString()`.
- Timestamp/recvWindow are excluded as transport metadata. Duplicate parameters and signature/API-key/secret/authorization fields are rejected.
- `StandaloneRuntime.signedRequest()` uses canonical fingerprints and typed operation-ID construction. Mutations without a stable client/target key fail closed instead of receiving a fresh UUID fallback.
- Generated client IDs are 32 characters with the `W21_` prefix; generated IDs with an explicit logical operation ID distinguish otherwise identical economic requests.

## Tests added — not yet executed on this SHA
`DurableMutationLedgerTest.kt` adds tests for parameter-order/encoding/decimal normalization, absent versus empty parameters, duplicate rejection, credential/signature rejection, distinct logical IDs, restart-equivalent stability, changed quantity, and invalid decimals. These are **NOT RUN / NOT VERIFIED** until CI reports are retrieved for the relevant source SHA.

## Remaining identity blockers
The current runtime still has legacy callers that do not pass an explicit campaign/signal-based logical operation ID to the sink. The four-argument legacy client-ID helper remains deterministic from request identity and therefore identical independent operations can still collide if a caller omits its own client ID. The current SQLite row does not persist campaign ID, signal ID, or per-attempt history. Gate A is **PARTIAL**, not closed.
