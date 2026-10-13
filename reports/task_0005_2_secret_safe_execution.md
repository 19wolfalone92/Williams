# TASK 000.5.2 — Secret-safe execution path

## Initial finding
The prior report identified exception interpolation in `execution_barrier.py` and raw HTTP/transport detail interpolation in `binance_client.py`. This established a disclosure path, not evidence that a real credential had leaked.

## Remediation committed to PR #66
- `execution_barrier.py`: exception text is no longer interpolated into durable ambiguity reasons, blocked-state reasons, or event diagnostics; a fixed redaction marker is used while preserving exception class and failure category.
- `execution_barrier.py`: an ambiguous submit no longer re-raises the original exception or exposes it as a chained cause. It returns a fixed `RuntimeError` message requiring reconciliation. When durable ambiguity persistence itself fails, the error still explicitly reports the persistence/reconciliation failure without raw exception content.
- `binance_client.py`: transport exception messages are fixed and redacted; raw exception chaining is suppressed.
- `binance_client.py`: HTTP 409/5xx/error-body strings no longer include response payloads; `BinanceAPIError.payload` retains only a code where available, not the response body.
- Non-GET mutation transport outcomes remain UNKNOWN/ambiguous and are not retried blindly by the client/barrier.

## Regression tests added/updated
- `tests/test_binance_client_secret_redaction.py`: synthetic API key, secret, signature, Authorization header, query token, and HTTP body values; mock transport only; asserts redaction, UNKNOWN execution, sanitized payload, and no blind retry.
- `tests/test_execution_barrier_secret_redaction.py`: synthetic secret values injected into submit exceptions; checks returned exception, durable reasons, event log, UNKNOWN/AMBIGUOUS persistence, RECONCILE_REQUIRED state, and blocked subsequent submission.
- `tests/test_execution_barrier_persistence_fail_closed.py`: updated to expect the safe ambiguous-submit error rather than raw transport exception propagation.

## Evidence / remaining limitations
Concrete diffs are available in commits `13227ea385635aeff6310cfb4353226f61c60a2b`, `760371f9d72e289c89c28e37a11b5f12808c27b0`, `abb26cf3c2b5972b7e6eceabf70f796c29955085`, `1ca64572d8a25c5f96d7eb85b9abdb7ee5909ea8`, `c2527f5bca8e4f704251060bef0e8ee6d4d7d03b`, `9d201e39f0285cee8eb1b71abfbcd05c84017e38`, and `cb9b3f5e98bd966ccf6dedaf23a2d616b233750a`.

Tests have been committed but have **not yet been executed in a local checkout**, and the connected API returned no new PR-triggered workflow runs for the latest commit. Therefore the defect path has been corrected in code, but the required pre/post runtime proof and full sink coverage are not yet established.

## Status
**OPEN — FIX NOT VERIFIED**. No real credentials, Binance requests, or live orders were used.