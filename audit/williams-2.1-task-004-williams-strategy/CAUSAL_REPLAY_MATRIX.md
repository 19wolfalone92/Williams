# TASK 004.13 — CAUSAL REPLAY MATRIX

**Status: BLOCKED — NOT ACCEPTED**  
Implementation under review: `82ecc9e8f899b1128892e66c773d12763986425f`  
Observed branch HEAD before TASK 004.13 docs: `dd1eb3d462f3a8641bf2df00ad2086192ea9b88c`

## Acceptance definition

A causal replay must run the same timestamped OHLCV sequence on prefixes (P_1...P_n). At each new bar, every previously available event must remain unchanged unless the event model explicitly permits a forward state transition. A full-history batch result alone is not causal-replay evidence.

| Component | Required prefix test | Evidence found | Status |
|---|---|---|---|
| Median price / SMMA / Alligator | Recompute each prefix; compare every previously valid line value; prove display shift never reads future OHLC | Shared indicator vectors and invalid-seed/gap tests; no exhaustive prefix comparison | BLOCKED |
| AO / AC | Recompute every prefix and compare all prior AO/AC values and colour states | Shared AO/AC formula vectors; no all-prefix test found | PARTIAL |
| WM1 | Prefix replay of divergent bar, structural level and price-trigger availability | No complete prefix replay evidence found | BLOCKED |
| WM2 | Third same-direction AO bar available without WM3; later bars must not move its original timestamp/trigger | Python independence test and AO colour vector exist; no complete runtime differential replay | PARTIAL |
| WM3 | Candidate unavailable until qualifying right bars exist; ties delay confirmation; stronger extreme cancels | Selected prefix/full fixture cases exist | PARTIAL |
| Structure / Setup / Confirmation | State changes only when corresponding bars/events become available | No exhaustive event trace found | BLOCKED |
| Price trigger / PendingSignal | Never re-arm after trigger crossed or invalidation breached; expiry is deterministic | Selected unit cases exist | PARTIAL |
| Campaign / trailing / exit | Prefix replay of entry, add-on, structural invalidation, trail and close | No end-to-end prefix trace found | BLOCKED |
| Restart | Persist and restore exact state between pivot and confirmation and between signal and trigger | Not demonstrated by this fixture suite | BLOCKED |
| Closed/open candle boundary | An unclosed candle cannot produce a final canonical signal unless explicitly specified | No complete boundary matrix found | BLOCKED |

## Required replay record

Each passing case must record: dataset ID/hash; symbol; timeframe; ordered OHLCV and timestamps; candle-close status; prefix length; emitted event IDs; signal-bar time; confirmation/availability time; trigger; invalidation; state before/after; implementation SHA; test command and result.

## Classification

- **WILLIAMS RULE:** indicator and named signal formulas supported by the selected source profile.
- **SYSTEM SAFETY RULE:** future data, incomplete data or unknown state must not authorize an order.
- **IMPLEMENTATION RULE:** event IDs, timestamp serialization, replay harness and explicit state-transition allowances.
- **RESEARCH REQUIRED:** any source ambiguity. **UNRESOLVED = BLOCKED.**

## Exit criteria

PASS only after every row marked BLOCKED is covered by executed deterministic prefix tests on Python and Android/JVM, with previous-event invariance asserted. Existing CI success and static inspection do not substitute for this proof.