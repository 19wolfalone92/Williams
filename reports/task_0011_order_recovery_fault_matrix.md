# TASK 001.1 — Order recovery fault matrix
**Status: NOT EXECUTED — P0 OPEN.** This is the required test plan and current evidence state, not a passing test report.

Implementation under test: `DurableMutationLedger.kt`, `StandaloneRuntime.signedRequest`; code commit `05d26e34cb34af66472124621a95c118ec69a81a`.

| # | Fault scenario | Required assertions | Current evidence |
|---:|---|---|---|
| 1 | Timeout before exchange acceptance | UNKNOWN/no blind replay; zero or one mutation as injected; entry blocked | NOT RUN |
| 2 | Timeout after exchange acceptance | UNKNOWN; one exchange mutation; reconciliation finds order | NOT RUN |
| 3 | Exchange success then local write failure | durable SUBMITTING/UNKNOWN; no resend | NOT RUN |
| 4 | Unknown exchange order status | remain blocked | NOT RUN |
| 5 | Partial fill | reconcile executed quantity and remaining order | NOT RUN |
| 6 | Duplicate execution events | fill counted once | NOT RUN |
| 7 | Restart with unresolved intent | intent survives process recreation; entry blocked | NOT RUN |
| 8 | Local order absent, exchange order exists | exchange is authoritative; recover order | NOT RUN |
| 9 | Order not found with unresolved intent | do not assume rejection; remain blocked pending authoritative proof | NOT RUN |
| 10 | Ambiguous cancel/emergency exit | no blind replay; verify order/position state | NOT RUN |
| 11 | Reconciliation fails midway | durable unresolved state survives; entries blocked | NOT RUN |
| 12 | Retry while prior operation unresolved | no second exchange mutation | NOT RUN |

No scenario is marked PASS because a deterministic fake-exchange suite has not yet been integrated and executed. The criteria require durable state, fake exchange state, exact mutation count, restart, gate state, and safe continuation after reconciliation for every row.
