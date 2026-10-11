# RECOVERY_TEST_MATRIX — TASK 006

**Status: BLOCKED — physical-device recovery scenarios NOT RUN**  
All scenarios are defined below; none is represented as passed.

| ID | Fault/action | Required observations | Expected safe outcome | Status |
|---|---|---|---|---|
| DEV-001 | Ordinary process start | UTC events, run ID, persisted state load, each recovery stage | Recover → Reconcile → Validate; entry eligibility only after all required gates | NOT RUN |
| DEV-002 | Close Dashboard | Engine process/component owner; state before/after | UI teardown does not erase authoritative trading state; report actual background limits | NOT RUN |
| DEV-003 | Explicit relaunch | State snapshot, unresolved operations, idempotency keys | Restore persistent state; do not auto-create duplicate operations | NOT RUN |
| DEV-004 | Force-stop + explicit relaunch | ADB command/exit code, PID, logs and DB snapshots | Run recovery/reconciliation; no blind resend | NOT RUN |
| DEV-005 | Device reboot | Boot record and post-launch logs | Safe recovery when app starts; do not assume Android guarantees auto-start | NOT RUN |
| DEV-006 | Corrupt/unavailable SQLite | Controlled corruption, error, durable fail-closed state | Block entries; never silently initialize empty state if orders/positions may exist | NOT RUN |
| DEV-007 | Binance API unavailable | REST/user-stream failure and reconciliation state | Recovery remains incomplete; entry blocked | NOT RUN |
| DEV-008 | Ambiguous operation result | Durable intent, response timeout/5xx, subsequent lookup | UNKNOWN until reconciled; no blind retry | NOT RUN |
| DEV-009 | Lost position protection | Protection state and gate decision | Block risk-increasing actions and persist reason | NOT RUN |
| DEV-010 | Repeat recovery | State/effect hashes before/after multiple passes | Idempotent result; no duplicate financial effects | NOT RUN |

## Mandatory event sequence

`PROCESS START → RECOVER → RECONCILE → VALIDATE → TRADE ELIGIBILITY`

Every stage needs UTC timestamp, run ID, input state, output/result, blocker reason, diagnostic event ID, and explicit next-stage/block decision. If any mandatory stage is failed or UNKNOWN, new entries must remain blocked.

## Evidence protocol

- Use Binance Spot Testnet only; no real order submission is permitted.
- Preserve original ADB logs and hash them after collection.
- Record exact APK SHA-256, version, device model, Android/API version, source SHA and commands/exit codes.
- Capture sanitized SQLite/FSM state before and after each test; exclude credentials.
- Record exchange mutation count and prove it remains zero for tests that do not require mutations.
- Keep force-stop and reboot as separate scenarios.
- Do not reset Safe Mode or kill switch to force a pass.
- Never classify source-level/unit-test evidence as physical-device recovery evidence.
