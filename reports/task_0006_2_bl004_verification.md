# TASK 000.6.2 — BL-004 verification

## Current code path
`BinanceSpotClient._request` sanitizes transport errors and HTTP response details. `ExecutionBarrier.execute` persists an ambiguous intent, marks `position_state=RECONCILE_REQUIRED`, emits a sanitized diagnostic, and refuses further submissions while unresolved intents remain.

## CI-discovered defect
Run `38231994444` executed against merge ref SHA `698fd9e6218c01a3107ef998482635d781da2b0c`, which merged branch SHA `3bdafc120a9d7f8d4057e058c71d6eb6ff6b83e3` with base `0007e79...`. Result: 218 passed, 1 failed. The failing restart test expected `TimeoutError` even though the intended safe API is now a sanitized `RuntimeError`.

## Additional security issue identified
Python's `raise SanitizedError from None` suppresses display of the context but still leaves the original exception reachable via the public exception object's `__context__`. The execution barrier now stores a safe message inside the exception handler and raises the new `RuntimeError` only after leaving that handler. This removes the transport exception from both `__context__` and `__cause__` in the intended path.

## Test updates
- `tests/test_sqlite_lifecycle_safety.py`: restart recovery test now expects the sanitized ambiguity error, asserts no cause/context, verifies unresolved state survives DB reopen, and verifies retry is not called.
- `tests/test_execution_barrier_secret_redaction.py`: checks no raw synthetic secret and no original exception in cause/context.
- `tests/test_binance_client_secret_redaction.py`: covers synthetic API key/secret, signature, Authorization header, sensitive query parameter, and response body with mocked transport.

## Evidence limitation
The exact final source SHA is `8f40eeca38eab84a26b96bebb2d7b1bee8edc223`. No CI run for that SHA was returned by the available PR-triggered workflow lookup. The test suite has not been executed locally through this integration. Persistent logs/SQLite/recovery sinks have not all been exercised by an end-to-end runtime scan.

## Status
**OPEN — FIX NOT VERIFIED**. A concrete exception-chain flaw was corrected and tests added, but BL-004 cannot close until the tests pass on the exact final source SHA and sink coverage is completed.