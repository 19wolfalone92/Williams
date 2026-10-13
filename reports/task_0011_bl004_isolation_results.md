# TASK 001.1 — BL-004 exception isolation
**Status: NOT VERIFIED — P0 OPEN.**

## Scope required
Synthetic markers must be absent from public error responses, `str`, `repr`, exception cause/context, formatted traceback, Android logs, structured events, SQLite, persistent state, and post-restart recovery diagnostics.

## Current evidence
- The central mutation sink uses generic messages for replay-block and cancel-replace reconciliation conditions.
- The existing `StandaloneRuntime.handle` error response serializes `x.message`; multiple legacy paths also expose exception messages and write them into diagnostics/audit state.
- No marker-injection tests covering the complete channel list have been executed.
- This report does not claim BL-004 compliance. The current error/log/persistence surface requires a dedicated sanitizer boundary and end-to-end tests before closure.
