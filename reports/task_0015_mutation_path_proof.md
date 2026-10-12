# TASK 001.5 — Mutation-Path Proof

- Audited executable-source SHA: `b2b1a78c9d43a30e077d936e65a9314ddddcfcdf`
- Status: NOT CLOSED.

## Observed paths

Android: business callers and recovery code invoke signed request wrappers; `signedRequest()` is the authenticated REST mutation sink currently inspected. Wrappers include POST, DELETE, and cancel-replace operations.

Python: `ExecutionBarrier.execute()` is a guarded submission path; `ExecutionRecoveryWorker` is read-only; `BinanceSpotClient._request()` performs REST transport and contains endpoint-aware retry handling. `Trader` and multi-position components invoke barrier/recovery abstractions, but the entire repository call graph has not been enumerated and mechanically verified.

## Change in this task

Mutation transport retry policy is now explicit and tested independently. This guards the timestamp-retry behavior but is not a substitute for source-to-sink containment.

## Still required

- Enumerate every caller, wrapper, adapter, endpoint and legacy/disabled path.
- Add a static checker that detects direct HTTP mutations and unclassified endpoint additions across all relevant source.
- Prove disabled legacy paths are not executable.
- Run bypass tests and inspect their output at the exact final source SHA.

No complete mutation-boundary proof is claimed.
