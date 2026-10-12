# TASK 000.8.6 — Mutation-Path Registry
Date: 2026-10-12 UTC

## Inspected Python path
`ExecutionBarrier.execute` persists a PENDING intent and claims idempotency before invoking the submit callback. It runs validation/pre-submit checks, invokes the supplied submit function once, and on transport exception records a redacted ambiguity classification, attempts AMBIGUOUS persistence, marks reconciliation required, emits a sanitized event, and raises a public error outside the original handler. BinanceSpotClient distinguishes retry-safe GET from mutation POST/DELETE and does not blindly retry mutation transport failures.

## Remaining source-to-sink work
- Enumerate every Python POST/DELETE/cancelReplace/OCO/protection call site and prove each caller passes through the barrier.
- Trace background schedulers and recovery worker mutations.
- Trace all Android signed POST/DELETE sinks and caller chains through ExecutionGate.
- Prove persistence/reconciliation behavior for each path and bind tests to the exact final SHA.

The connector can fetch selected files but does not provide a complete repository tree/code-search operation sufficient to establish that no bypass exists. No direct-bypass-free conclusion is claimed.

Status: **INCOMPLETE — EVIDENCE MISSING**.
