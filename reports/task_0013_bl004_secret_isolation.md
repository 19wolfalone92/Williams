# TASK 001.3 — BL-004 secret isolation

**Status: PARTIAL / NOT VERIFIED across Android.** Current source SHA: `74f12c595181d0b4362fa02f2b2de436db9a751c`.

The identity parser rejects signature, API-key/secret and authorization parameters; synthetic-marker tests cover those input fields. This is not an end-to-end output-channel sweep.

| Output channel | Status | Evidence / limitation |
|---|---|---|
| Identity/fingerprint input | PARTIAL | Credential parameters rejected; Android tests ran on parent SHA, current transaction refactor unverified |
| Android exceptions/causes/stack traces | NOT VERIFIED | No complete marker injection sweep |
| OkHttp diagnostics / Logcat | NOT VERIFIED | No device/log capture |
| SQLite mutation ledger | PARTIAL | Credential fields absent from schema; all persistence paths not exhaustively tested |
| Audit records / persisted runtime state | NOT VERIFIED | End-to-end marker tests absent |
| Recovery diagnostics / JSON errors | NOT VERIFIED | End-to-end marker tests absent |
| Python API errors | PARTIAL | Python suite passed; not a full marker sweep |
| CI logs/artifacts | NOT VERIFIED | No marker sweep over all actual outputs |

No real credentials were used. BL-004 is not closed.
