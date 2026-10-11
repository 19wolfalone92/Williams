# TASK 004.13 — TIMEFRAME POLICY TESTS

**Status: PARTIAL helper-level evidence; exhaustive runtime enforcement BLOCKED.**

The repository helper `WilliamsTimeframeContract.kt` defines this owner-approved implementation policy (not a Bill Williams book rule):

| Timeframe | Contract role | May originate a canonical entry signal? |
|---|---|---|
| D1 | Macro context | No |
| H4 | Higher context | No |
| H1 | Canonical decision | Yes |
| M15 | Execution/fill monitoring | No |
| M5 | Replay/diagnostics only | No |

The Python golden test asserts H1 as the configured execution timeframe and records the ordered timeframe contract. Android tests assert H1 allowed and M15/M5 blocked, including unknown-frame provenance failures.

## Required test matrix

| Test | Required result | Evidence/status |
|---|---|---|
| H1 valid, warmed-up, closed bar | May continue to normal gates | Helper/config evidence only; full path BLOCKED |
| H1 history fetch fails or is stale | Fail closed; no alternate timeframe fallback | Claimed in PR description, not exhaustively proven by this matrix |
| M15 detects fill or execution event | May update execution/position monitoring; cannot originate new signal | BLOCKED pending call-path test |
| M5 replay/diagnostic candidate | Diagnostic only; cannot enter PendingSignal/campaign entry path | BLOCKED pending call-path test |
| D1/H4 context absent or stale | Fail closed where required context contract says required; no invented hard veto | Exact hard-veto semantics remain unresolved |
| Mixed TF timestamps / stale higher-TF candle | No future or not-yet-closed context used | BLOCKED |
| Restart / restored pending signal | Original canonical timeframe and provenance preserved | BLOCKED |
| Spot bearish signal | SELL can only reduce/close existing base inventory; no automatic short | Policy/vector exists; full mutation-path proof remains separately BLOCKED |

## Required implementation proof

Instrument all entry-producing call sites and assert that every canonical `PendingSignal` originates from H1. Feed M15/M5-only data and verify zero entry candidates, zero campaign starts and zero order mutation attempts. Test H1 fetch failure, stale bars, unclosed bars, duplicated timestamps and restart. Any missing/ambiguous timeframe provenance must block the entry.