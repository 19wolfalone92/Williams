# TASK 001.5 — BL-004 Secret Isolation

**Executable source SHA:** `cf35f2fb8d2f68770f191efaa272864e7debe04c`  
**Status:** PARTIAL — full-channel sentinel sweep remains open.

## Changes

- Added `SecretRedactor` for credential-like query parameters, JSON fields, and authorization headers.
- `TradingAuditStore.recordRestCall()` redacts request/response payloads before persistence.
- Persisted user-stream event JSON, trade raw JSON, and audit reasons are redacted before storage.
- Added synthetic-sentinel unit tests and a Robolectric integration test that verifies REST audit and user-stream event payloads do not persist sentinel credentials.
- Campaign CI run `38268195164` passed Android unit tests.

## Not yet proven

- Full sweep across every Android/Python log, exception, diagnostic, HTTP trace, report, recovery artifact, and backend record.
- Record-level proof that no API secret is stored remotely.
- All signed request/exception paths and every possible secret key alias.
- Redaction of every possible credential-bearing value in arbitrary nested payloads.

No BL-004 closure is claimed.
