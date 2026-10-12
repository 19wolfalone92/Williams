# TASK 001.1 — Durable boundary design
**Status: INCOMPLETE — P0 OPEN.** This report records implemented behavior and explicit gaps; it is not a closure claim.

## Baseline and implementation refs
- Requested baseline: `11746c202fd2b7eb2126292d3e9978a2f238169b`.
- Baseline was checked before implementation; PR #66 subsequently advanced through the task commits. Current code commit at report preparation: `05d26e34cb34af66472124621a95c118ec69a81a`.
- PR #66 remains open and Draft; no merge was performed.
- Files: `app/src/main/java/com/williamsbot/DurableMutationLedger.kt`; `app/src/main/java/com/williamsbot/StandaloneRuntime.kt`.

## Implemented
- SQLite intent journal states: PREPARED, SUBMITTING, UNKNOWN, ACKNOWLEDGED, RECONCILED, REJECTED.
- Unique operation ID and request fingerprint; conflicting reuse of an ID is rejected.
- `StandaloneRuntime.signedRequest` is the common signed REST request function. POST/DELETE requests now persist an intent and move it to SUBMITTING before network I/O, then ACKNOWLEDGED after a successful response.
- Generated Binance client IDs are attached to POST order and OCO requests when absent.
- Non-success/transport exceptions leave an unresolved UNKNOWN intent and set the reconciliation-required barrier. Duplicate/replay attempts against non-PREPARED intent states are rejected.
- HTTP 200 cancel-replace responses where either leg is not SUCCESS are treated as unresolved.

## Safety limits / not yet implemented
- SQLite is not atomic with Binance.
- Full startup reconciliation of every durable intent is not yet connected. In particular, cancel-replace requires verifying both old-order cancellation and replacement order; the current intent schema does not retain a structured request payload for this proof.
- No end-to-end fault-injection proof has been executed yet.
- BL-004 channel-complete isolation is not yet proven.
- Until unresolved-intent reconciliation and the required tests are implemented, do not treat the runtime as release-safe and do not close P0.
