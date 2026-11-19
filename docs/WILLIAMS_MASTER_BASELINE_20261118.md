# Williams Bot 4.64.40 — reproducible baseline / release hold

Date: 2026-11-18. All evidence in this record is scoped to the **pre-change** `main` commit.

## Immutable comparison point

- Repository: `19wolfalone92/Williams`; default branch `main`.
- Commit: `1235a02773b178af64aa909880612c77bdea98d2`.
- `VERSION`: `4.64.40`.
- Baseline source tree: [main at commit](https://github.com/19wolfalone92/Williams/tree/1235a02773b178af64aa909880612c77bdea98d2).
- Android APK build (baseline): [run 44170889498](https://github.com/19wolfalone92/Williams/actions/runs/44170889498), completed success.
- Debug APK **artifact ZIP**: `williams-bot-debug-apk-4931`, artifact ID `14366824259`; **ZIP** SHA-256: `f8f2a762c80923effec794307defcd46c72d6dca96eca2dc54e34bff15d92ea1`. This is the artifact archive checksum, **not an APK-file checksum**. The APK-file SHA-256 and install/upgrade hash are NOT independently established here.
- Unsigned release artifact ZIP: `williams-bot-release-unsigned-4931`, artifact ID `14367283849`; ZIP SHA-256: `49b777ad3ee458ff1f88f83517e1c9eec8370d96fa7e94c15e7bdd3879be838d`.
- Baseline Python CI [run 44170889637](https://github.com/19wolfalone92/Williams/actions/runs/44170889637): success; Android CI [run 44170889716](https://github.com/19wolfalone92/Williams/actions/runs/44170889716): success; Campaign CI [run 44170889504](https://github.com/19wolfalone92/Williams/actions/runs/44170889504): success.
- CI success is not exchange-observed proof of FILLED → confirmed STOP nor physical-device autonomy.

## Existing work / do not duplicate blindly

Open PRs at baseline:
- #234 Android memory/clock recovery; head based on historical feature branch.
- #227 scanner false entry ban and signed preflight; draft on an older chain.
- #185 signed Futures UTC clock fixes; draft.
- #141 diagnostics/MTF truth; draft.
- #140 source/terminal evidence; draft.

All five compared to current `main` as **diverged** (not automatically safe to merge). Special unique-change inventory and exact tests must precede any cherry-pick. Older `feat/nov16-intent-ledger-shadow-4.64.38-stacked-v4` also diverges from main. No old branch deleted in this work.

## Operational contract (user-approved 2026-11-18)

- Trading authority: TC2 only; TC1/NTD/Course observation-only for NEW exposure.
- D1/H4 interpretation; H1 decisions; M15/M5 diagnostic only.
- 15 approved USDT contracts; maximum 10 simultaneous campaigns subject to account/campaign risk.
- New-entry halt at daily trading drawdown 3% UTC; 5% independent circuit breaker; all-in margin envelope 50% of defined account basis.
- Trading LONG and SHORT on Binance USDⓈ-M Demo/Testnet only. No live orders or live endpoints.
- Existing exchange positions and protective stops have priority over new scans.
- Main never changes before exact-head CI, implementation review, matched Python/Android tests and release gates.

## Release blockers not waived

1. Actual signed Binance Demo LONG and SHORT, full/partial fill and exact STOP read-back.
2. Unknown POST, cancel-vs-fill races, reboot and Wi-Fi/cellular outage reconciliation on physical Android.
3. Independent four-primary-source review of ambiguous TC2 angulation, equal-extreme figures and wave interpretation.
4. Held-out historical verification and verified baseline-vs-candidate comparison.
5. SHA-256 of the extracted actual APK, signature, upgrade install and installed-process survival.

State: **baseline captured; Demo and full release NOT ACCEPTED**.
