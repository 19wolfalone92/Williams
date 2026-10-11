# TASK 004.13 — PYTHON–ANDROID GOLDEN VECTORS

**Status: PARTIAL FORMULA PARITY; full strategy parity BLOCKED.**

## Shared fixture and consumers

- Fixture: `app/src/test/resources/williams_strategy_golden_vectors.json`
- Fixture blob SHA: `d0a6e221c4382150339ece9bc68540b08e8b9a1c`
- Python consumer: `tests/test_williams_strategy_golden_vectors.py`, blob `d0e136c5298d971df35a5a05e1bb00915f4e8f7b`
- Android/JVM consumer: `app/src/test/java/com/williamsbot/WilliamsStrategyGoldenVectorsTest.kt`, blob `893946b46ba25d3c2b2120455cfbf4071eec6531`

Both test implementations read the same JSON fixture. The Python backend workflow includes the Python test; Campaign CI runs Android `testDebugUnitTest`. CI success establishes the configured test jobs passed, not complete parity of all live call paths.

## Vector coverage

| Vector family | Shared expected data | Current evidence | Status |
|---|---|---|---|
| Alligator | Median prices; Jaw/Teeth/Lips; periods 13/8/5; display offsets 8/5/3; selected values and unavailable pre-shift values | Assertions in Python and Android tests | PARTIAL PASS |
| AO / AC | Median-price sequence; selected AO/AC values; 5/34 and AO-SMA(5) formula | Assertions in both tests; Android checks exactly 34 slow samples | PARTIAL PASS |
| AO colour / WM2 | AO values and third rising index | Android helper checks third-bar classification; Python tests WM2 independence from fractal | PARTIAL PASS |
| Fractal | Strict confirmation, equal-right-side delay, stronger-extreme cancellation, center and confirmation indexes | Selected tests on both platforms | PARTIAL PASS |
| Pending trigger | Uncrossed trigger, already-crossed trigger, structural invalidation and expiry | Selected tests on both platforms | PARTIAL PASS |
| Timeframe | H1 canonical; M15/M5 prohibited as canonical signal frames | Helper-level assertions | PARTIAL PASS |
| Complete strategy decision | Context, structure, WM1/2/3, setup, confirmation, trigger, final signal and availability timestamp | No full shared output vector proving end-to-end decision equality | BLOCKED |
| Campaign / exits / restart | Entry, trailing, structural exit, repeat signal, persistence and restored state | No full shared vector suite demonstrated | BLOCKED |

## Numerical and semantic contract

- Existing selected numeric assertions use absolute tolerance `1e-9`; the project must explicitly approve tolerances for every computed output and preserve exact equality for categorical/event fields.
- Fixture indexes are zero-based. A production parity record must use timestamps as well as indexes.
- SMMA SMA-seed behavior is an implementation convention, not asserted as an unambiguous rule across every edition.
- TTL is a safety/implementation policy, not a universal Williams rule.
- Alligator forward display shift must shift the computed line only; it must not allow future OHLC values into a decision at time (t).

## Required next vectors

Add raw timestamped OHLCV plus open/closed status and expected outputs at each prefix. Include median price; every indicator value; WM1/WM2/WM3 candidate and confirmation; Setup/Confirmation state; trigger/invalidation; final signal ID; signal-bar time; availability time; and campaign/exit state. Run both runtimes against identical fixtures and compare complete serialized outputs, not merely each platform against selected scalar constants.

**Acceptance remains BLOCKED until end-to-end differential outputs and causal replay pass on one exact implementation SHA.**