# TASK 001.4 — Operation identity implementation note

Status: PARTIAL IMPLEMENTATION — P0 OPEN

## Revision evidence

- Starting checked revision: `0964de62ad49b87985e54106eb819c1825b382df`
- Baseline inventory report commit: `c2b28c7a5ae2339a34411b5b646668159aa2b345`
- Implementation revision at time of this note: `1580f51f77f26176acff74587ef0f0c6481a995f`
- PR #66 remains open, Draft, and unmerged.
- This is a bounded implementation change, not closure of TASK 001.4.

## Changes implemented

1. `MutationIdentity.generatedClientOrderId(method, path, symbol, unsignedParams)` no longer derives an exchange client ID from request economics alone. It now fails closed with `MUTATION_LOGICAL_OPERATION_ID_REQUIRED`. Callers must supply an explicit logical operation identity to the overload that accepts it.
2. Parameter canonicalization now rejects duplicate keys after URL decoding and case folding, so aliases such as `side` and `SIDE` cannot be silently treated as separate parameters.
3. Transport-only keys `timestamp` and `recvWindow` are excluded case-insensitively from the request fingerprint.
4. Tests were added/replaced for missing logical identity, distinct operation IDs for identical economics, duplicate case aliases, and case-insensitive transport metadata.

## Verification limits

- The code and test files were committed to the PR branch.
- The GitHub Campaign CI run for this revision was still pending at the last poll and exposed no jobs yet.
- The Python backend workflow was queued at the last poll.
- The Android APK workflow again ended in `startup_failure` with an empty jobs list. This is not a successful build and does not establish whether Kotlin compilation or Android tests pass.
- No local Android build, Robolectric run, FI-01–FI-12 suite, exchange reconciliation test, or full mutation-path proof is claimed here.

## Remaining P0 work

- Wire caller-created durable business operation IDs through every Android mutation call site; verify retry and restart stability at the call boundary.
- Implement operation-aware Binance Testnet reconciliation for orders, fills, cancel, cancel-replace and OCO parent/children.
- Integrate recovery with durable ledger and gate states; unresolved or incomplete exchange history must remain blocked.
- Execute FI-01–FI-12 against production mutation/recovery abstractions.
- Prove Android and Python mutation paths, BL-004 secret isolation, migration/concurrency behavior, and exact-HEAD regressions.
- Diagnose the Android workflow startup failure and produce verifiable build/APK evidence.

Do not classify TASK 001.4 as closed until the above P0 gates have test evidence on the exact final SHA.
