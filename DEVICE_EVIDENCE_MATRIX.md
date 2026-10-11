# DEVICE_EVIDENCE_MATRIX — TASK 006

**Status: BLOCKED — physical device evidence unavailable**  
Target branch: `audit/williams-2.1-task-006-android-runtime`  
No synthetic device logs were generated. All device-specific tests below are NOT RUN.

| ID | Requirement | Required evidence | Status | Blocker |
|---|---|---|---|---|
| DEV-001 | Normal launch | ADB logcat, package/version, UTC timestamps, run ID, engine lifecycle | NOT RUN | No physical device/ADB evidence |
| DEV-002 | Close Dashboard | Evidence of actual engine owner and state before/after UI close | NOT RUN | No device trace |
| DEV-003 | Relaunch | Durable state snapshot and event replay/recovery log | NOT RUN | No device trace |
| DEV-004 | Force-stop then explicit relaunch | Commands/return codes, PID transitions, pre/post SQLite/FSM snapshots | NOT RUN | No device trace |
| DEV-005 | Reboot | Boot metadata, app/service start evidence, safe recovery sequence | NOT RUN | No device trace |
| DEV-006 | Corrupt/unavailable SQLite | Controlled test setup, blocked entry, preserved reconciliation latch | NOT RUN | No device trace; do not test on valuable state |
| DEV-007 | Binance API unavailable | Testnet connectivity fault, reconcile failure and entry block | NOT RUN | No controlled Testnet device run |
| DEV-008 | Ambiguous mutation result | No resend; durable UNKNOWN intent and reconciliation evidence | NOT RUN | No safe mutation-path harness/device evidence |
| DEV-009 | Protection loss | New risk-increasing actions blocked; diagnostic reason persisted | NOT RUN | No device trace |
| DEV-010 | Repeated recovery | Same final state, no duplicate effects/operations across repeated restart | NOT RUN | No device trace |

## Device identity fields (not collected)

- Device model: NOT VERIFIED
- Android version / API level: NOT VERIFIED
- Application ID / versionName / versionCode: NOT RUN
- Installed APK SHA-256: NOT RUN
- ADB availability/version: NOT RUN
- UTC test window: NOT RUN
- Original logcat artifact/hash: NOT RUN
- Tester commands and exit codes: NOT RUN

## Acceptance rule

A test can become PASS only with original device evidence, timestamps, exact APK SHA, tested source SHA, procedure, exit codes, and observed postcondition. CI, unit tests, source inspection, emulator output, or a manually written narrative cannot substitute for a physical-device test.
