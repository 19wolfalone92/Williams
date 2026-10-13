# TASK 004.13 — WILLIAMS RULE SOURCE MATRIX

**Status: SOURCE CROSSWALK EXISTS; implementation and behavioral proof remain partially blocked.** Refer to `REPORT_RU.md` for source edition separation and page/figure locators. Do not combine TC1, New Trading Dimensions, Trading Chaos 2 and Trading Chaos Course into one undifferentiated ruleset.

| Rule | Class | Source/canonical interpretation | Code/test evidence | Status / blocker |
|---|---|---|---|---|
| AO = SMA(5, Median Price) − SMA(34, Median Price) | WILLIAMS RULE | TC2 ch. 10, Fig. 10.1; locator in REPORT_RU.md | Shared Python/Android AO vectors | PARTIAL PASS |
| AC = AO − SMA(5, AO) | WILLIAMS RULE (NTD indicator) | NTD ch. 6; not automatically a TC2 entry gate | Shared AC formula vectors | PARTIAL PASS; no AC-only TC2 entry |
| Alligator 13/8/5 and display shifts 8/5/3 | WILLIAMS RULE | NTD Alligator chapter; forward display shift is not future data | Shared Alligator vectors | PARTIAL PASS; seed convergence unresolved |
| SMMA SMA seed | IMPLEMENTATION RULE | Deterministic cross-platform initialization convention | Python invalid-seed/gap test and shared line values | PARTIAL PASS; not claim of universal source prescription |
| WM1 divergent/reversal bar and angulation | WILLIAMS RULE + RESEARCH REQUIRED | Qualitative “good angulation” is not proven as a numeric formula by current scan | Approximate code path documented in REPORT_RU.md | BLOCKED for automated rule-exact execution until formula/source resolved |
| WM2 third same-direction AO bar / Super AO | WILLIAMS RULE | TC2 ch. 10, Fig. 10.3; independent of prior WM3 confirmation | Python independent-candidate test and AO vector | PARTIAL PASS; complete Android/live-path parity not proven |
| WM3 confirmed fractal | WILLIAMS RULE | TC2 ch. 11, Figs. 11.1–11.5; pivot and confirmation time differ | Shared strict/tie/cancellation cases | PARTIAL PASS; full replay/restart not proven |
| Price trigger after signal | WILLIAMS RULE + SYSTEM SAFETY RULE | Conditional price level must actually trigger; never backdate availability | Pending trigger helpers/tests | PARTIAL PASS; complete path coverage BLOCKED |
| D1/H4/H1/M15/M5 role split | IMPLEMENTATION RULE | Owner-approved bot contract, not a book rule | Timeframe helper and config tests | PARTIAL PASS; exhaustive entry-path proof BLOCKED |
| Spot SELL | SYSTEM SAFETY RULE | Reduce/close Spot inventory only; no automatic short | Shared Spot semantics fixture | Full order-path proof BLOCKED |
| TTL / pending expiry | IMPLEMENTATION RULE | Explicit expiry is deterministic safety policy, not universal author-defined rule | Fixture and pending signal tests | PARTIAL PASS; policy governance and full restart proof needed |
| Campaign and trailing | WILLIAMS RULE + SYSTEM SAFETY RULE | TC2 campaign/trailing source examples must be mapped individually; safety may block uncertain state | Existing campaign code/tests; no full causal cross-platform vector | BLOCKED |
| Any rule lacking a reliable locator or exact semantics | RESEARCH REQUIRED | Do not infer a numeric threshold or merge historical profiles | Source report labels unresolved rows | UNRESOLVED = BLOCKED |

## Evidence vocabulary

- **SOURCE-CONFIRMED:** primary source text/figure inspected with edition and locator.
- **CODE-CONFIRMED:** implementation exists at named SHA.
- **BEHAVIOUR-CONFIRMED:** exact behavior demonstrated by executed test/replay at named SHA.
- A passing formula vector does not prove a live signal call path uses the formula. A test file's presence is not a passing test result.
