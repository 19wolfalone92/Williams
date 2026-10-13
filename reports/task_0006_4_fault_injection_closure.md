# TASK 000.6.4 — Fault-injection closure

All tests must use mock transport and temporary SQLite databases. No Binance request/order was sent.

| Scenario | Current evidence | Result |
|---|---|---|
| Timeout after submit | Restart test exists; CI exposed outdated expectation, now corrected | FIX NOT VERIFIED on final SHA |
| Response lost but exchange created order | No full fake-exchange lookup/recovery simulation demonstrated | INCOMPLETE |
| Duplicate execution event | No complete idempotency test evidence | INCOMPLETE |
| Partial fill | Not verified end-to-end | INCOMPLETE |
| Unknown status | Barrier ambiguity handling exists; full status matrix absent | PARTIAL |
| SQLite failure | Several rollback/busy/persistence tests exist | PARTIAL |
| Exchange order missing local row | No end-to-end recovery test demonstrated | INCOMPLETE |
| Local order without exchange confirmation | Ambiguity state is covered partially; full reconciliation proof missing | PARTIAL |
| Process restart | SQLite reopen test exists; expectation corrected after CI failure | FIX NOT VERIFIED |
| Exception contains synthetic secret | Redaction tests exist; latest execution pending | FIX NOT VERIFIED |

## Concrete CI finding
Previous Python CI: 218 passed, 1 failed. Failure was the restart test expecting raw TimeoutError. Test was updated to assert sanitized RuntimeError and no exception context/cause. New test result remains pending.

## Status
**OPEN — FIX NOT VERIFIED** for timeout/restart/secret-redaction tests; **INCOMPLETE — EVIDENCE MISSING** for the full fault matrix.