# WILLIAMS FINAL ACCEPTANCE — BLOCKED / PARTIAL (2026-11-23)

## Source snapshot

- Repo: `19wolfalone92/Williams`, candidate version **4.64.52**.
- Original audit baseline PR #270 HEAD: `301833bd0007817b6dd3ed0cfbd686ad2c72faf8`.
- Audit branch: `audit/global-four-books-tc2-full-chain-20261123`, draft PR **#271**, targeting PR #270 branch; `main` unchanged.
- No `Williams-AI` writes. No real trades, signed Demo order writes, or CI APK release publication by this audit.

## Acceptance gates

| Gate | Status | Proof or missing evidence |
|---|---|---|
| Original TC1 140, NTD 156, TC2 224, Course 122 physical PDF pages (642) located | PARTIAL | Private files accessible; section manifest 57 is verified as contiguous index, not proof of figure comprehension |
| Machine-readable rule provenance | PARTIAL | `book_rules_registry.json`: 57 indexed sections, 92 chapter→rule links, 0 rules BOOK_VERIFIED, 2 PARTIAL TC2 stops, 3 explicit engineering extensions |
| TC2 signal fidelity (WM1/2/3 including first WM2/3) | NOT_VERIFIED | No independent full figure-by-figure LONG/SHORT exam |
| Accurate UTC OHLCV/cache causality | PARTIAL | Kotlin code fixed in three entry routes and tests created; complete runtime regression/CI required |
| Exact 15 pairs in frozen historical exam | PARTIAL | SUI public closed H1 data added; legacy UNI removed from active benchmark universe; CI required |
| Adaptive 3/4/5 vs fixed 3/4/5/fractal trail | NOT_VERIFIED | Missing same-cohort out-of-sample comparison and signed replacement execution |
| Entry/partial fill/STOP/recovery signed Demo | NOT_VERIFIED | No controlled signed Binance Demo evidence on this head |
| Daily loss 3%, three consecutive losses, circuit breaker 5%, margin <=50% | NOT_VERIFIED as independent end-to-end proof | Do not assume green unit tests validate cross-process/daily-rollover signed ledger |
| Android 35/36 UI-only smoke | PARTIAL | Earlier candidate CI passed UI-only scenarios; new head and physical Samsung Android 17 tests missing |
| Stable-signature installable new APK | NOT_VERIFIED | Prior CI produces ephemeral-signed debug or unsigned release. No verified persistent certificate/update compatibility |
| Global P0/P1 acceptance | NOT_VERIFIED | Critical signed STOP, trailing, campaign ownership, risk and independent book exam outstanding |
| Merge to main | BLOCKED | PR #271 draft; do not merge before full gates |

### Explicit distinctions

A valid PDF catalog is **not** proof of textbook compliance. Successful compilation and Python unit tests are **not** proof of a Binance Demo protected fill or profitable strategy. An APK produced by CI under an ephemeral certificate is **not** an authenticated installable update over a prior user release. A historical paper trade is **not** a live or Demo execution.

The source-backed defect register is `WILLIAMS_DEFECT_REGISTER.json`. Gate remains **BLOCKED** until signed exchange ownership, protective STOP continuity, full TC2 path, independent historical campaigns, accurate risk, verified CI, and persistent-signing conditions are demonstrated.
