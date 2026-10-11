# TASK 004.13 — FRACTAL CONFIRMATION MATRIX

**Status: PARTIAL UNIT EVIDENCE; full runtime/restart proof BLOCKED.**  
Shared fixture: `app/src/test/resources/williams_strategy_golden_vectors.json`, blob `d0a6e221c4382150339ece9bc68540b08e8b9a1c`.  
Android helper: `WilliamsFractalMath.kt`, blob `b9830e6f9bca360dcf1b73d399fc450266d1b0dd`.

| Case | Expected contract | Current fixture/test evidence | Status |
|---|---|---|---|
| Strict 5-bar fractal | 2 left bars establish pivot; 2 qualifying right bars required; unavailable before confirmation | Strict-up fixture has center index 2 and confirmation index 4; Python and Android test pre-confirmation prefix | PARTIAL PASS |
| Equal right-side high/low | Equal value does not count as a qualifying lower high/higher low; confirmation is delayed | Equal-right-high and equal-right-low fixtures delay expected confirmation | PARTIAL PASS |
| Stronger right-side high/lower low | Candidate is invalidated, not confirmed | Cancellation fixtures exist for both directions | PARTIAL PASS |
| Pivot timestamp vs confirmation timestamp | Pivot price/structure uses center time; availability uses confirmation time | Android result has separate center and confirmation indexes; Python assertions inspect confirmed center at confirmation row | PARTIAL PASS |
| Duplicate/repeated signal | Same pivot must not emit duplicate confirmation events across subsequent prefixes | No exhaustive event-identity replay evidence found | BLOCKED |
| Restart between pivot and confirmation | Restart restores candidate consistently or safely recomputes without early signal | Not demonstrated by cited fixture | BLOCKED |
| Unclosed right-side candle | Must not count as confirmed until the bar is closed/eligible under explicit data contract | Not demonstrated end-to-end | BLOCKED |
| Actual live order gate | Pending WM3 cannot become actionable before confirmation timestamp and price trigger | Helper/provenance tests exist; exhaustive sink-bound runtime proof is not shown here | BLOCKED |

## Deterministic behavior in Android helper

`confirmUp/confirmDown` take an explicit `throughIndex`, reject invalid/non-finite values, require strict left-side extrema, count only strictly qualifying right-side values, let equal right-side extremes delay confirmation, and return the first index at which the required right-side count is reached. A stricter contrary extreme returns no confirmation.

This is evidence about the helper, not proof every Android/Python strategy path uses it correctly.

## Rule classes

- **WILLIAMS RULE:** fractal pivot and qualifying bars as documented in the selected TC2 source profile; source locators are listed in `REPORT_RU.md`.
- **SYSTEM SAFETY RULE:** a pivot is not an actionable confirmed fractal before confirmation exists.
- **IMPLEMENTATION RULE:** zero-based indices, timestamp fields, event identity, persistence and closed-bar convention.
- **RESEARCH REQUIRED:** any tie or candle-finality behavior not resolved by the inspected source and explicit runtime contract. **UNRESOLVED = BLOCKED.**

## Required tests

For both up/down fractals, run each prefix independently; compare event sets; add a new bar at a time; verify exact confirmation time; repeat with ties, stronger contrary extreme, missing bars, duplicate timestamps, non-finite values and process restart. Python and Android must consume the same fixtures and agree on pivot, level, confirmation index/time and emitted event identity.