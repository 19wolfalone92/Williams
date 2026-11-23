# ADR / QEP DIAG13 — signed ETH close-all STOP vs historical SHORT ledger

Date: 2026-11-23. Scope: Williams Android USD-M **Demo only**.
Base: PR #266 head `506b6d574ab3a1cc47dc315aa1106108ab458688`.
Source: user-provided `Williams_Diagnostics (13).zip`; device captured
2026-11-23T04:21:05.601Z; source_revision
`f7aa62c3cf305625daf97b05ea31e4bfc75e0fc1`.
The archive is a point-in-time capture, **not** a current Binance status.

## Verified source facts (sanitized)
- Signed Binance one-way ETH position: LONG 0.463, exchange entry 1641.32,
  MARK 1733.94; full close-all SELL MARK STOP 1648.34, `priceProtect=false`.
- Matching signed proof: `EXTERNAL_PROTECTED`, 1 signed full STOP,
  0 strategy ownership, `OBSERVE_ONLY`; client id
  `W2H_ETHUSDT_0be6c940adc0914fc4`.
- Coordinator `external_open_orders` includes **the exact same client id**
  with `kind=EXTERNAL_ALGO_ORDER`, `order_type=STOP_MARKET`,
  `close_position=true`, `reduce_only=true`, zero quantity, SELL.
- Local historic `ENTRY`: ETH SHORT 0.031, `campaign_status=OPEN_PROTECTED`,
  reconciled >45h earlier; no proof it owns the current opposite LONG.
- A separate `EXIT` intent for LONG 0.463 has `SUBMISSION_UNKNOWN`.
  Never resubmit it blindly, infer an execution or delete this evidence.
- Runtime `RECONCILE_REQUIRED`, signed new-entry proof stale, user WS up.
  Audit had EXE-03/04/11 FAIL despite fresh exact signed STOP, and
  EXE-12 NOT_VERIFIED while coordinator reconciliation was running.
- Repeated old `COCKPIT_AUTO_RECOVERY` events: `Failed requirement.`
  The newer stage-aware logging in PR #266 must be deployed before we can
  identify the exact H1/H4 recovery precondition. It is **not** solved by
  changing diagnostic severity.

## Root causes and narrow fix

1. **EXE-11 / operator reason** matched any `W2H_*` external algo id as
   an entry. Introduce a read-only strict proof join:
   current signed position + unique signed `EXTERNAL_PROTECTED` proof +
   unique exact external algo id + STOP_MARKET + CONDITIONAL + BOTH +
   MARK_PRICE + active order + closePosition/reduceOnly + priceProtect=false
   + zero quantity + correct side, trigger price and MARK relationship.
   Do not use a prefix as an identity proof. Unknown fields fail closed.
2. **EXE-03/04** treated the stale SHORT `OPEN_PROTECTED` as an
   **unprotected current** position, overriding signed opposite-side live
   protection. Exclude historical opposite-side campaign from *the current
   protection verdict* only when a fresh, matching signed opposite position
   and exact exchange protective STOP are present. Historical ledger and
   `RECONCILE_REQUIRED` are unchanged.
3. API36 smoke occasionally fails to read UI XML after `uiautomator dump`.
   Permit bounded XML-generation retries with PID and app-package checks,
   no bypass of UI assertions, no masking crash or missing evidence.

## Safety invariants and non-goals
- **Zero exchange writes** from diagnostic logic. No cancel, STOP overwrite,
  repeated entry or MARKET EXIT.
- Never infer strategy ENTRY ownership from an existing STOP, from `W2H_`
  or a historic client id.
- `RECONCILE_REQUIRED` stays in force until independent live Binance REST
  account + order + executions prove otherwise; new entries remain blocked.
- Daily loss 3%; three *consecutive* losing closes; circuit breaker 5%;
  total margin 50%; TC2 H1; 15 instruments, max 10 campaigns unchanged.
- STOP-only autonomous adoption from PR #266 requires H1/H4 review and two
  matching signed snapshots at least 30s apart, plus durable execution door.
- Does not prove FILLED→STOP timing, Samsung background operation, or Demo
  execution acceptance. A new **installed** APK diagnostic and signed Binance
  read-only checks are mandatory.

## Falsification tests
- Synthetic DIAG13 ETH signed tuple + historic SHORT: EXE-03/04 and
  EXE-11 report protection/role correctly, **EXE-12 remains unowned** and
  execution/position management are still barred.
- Stale signed STOP, wrong id, wrong side, missing closePosition, priceProtect
  on, wrong trigger/workingType, duplicate ids, stale position: EXE-11 returns
  FAIL, not green.
- Existing stale-unknown-stop, reversal, unprotected fill and orphan-entry
  regressions must still pass.
- CI must build an Android APK and run API35/API36 lifecycle smoke. Physical
  Demo test of both sides and all protective failure modes remains unverified.

## Definition of Done
- [ ] Unit regressions green on this commit (runner evidence).
- [ ] Android build and API35/API36 UI smoke green.
- [ ] Independently review this patch against the sanitized original archive.
- [ ] Install new APK and rerun diagnostic; verify current signed STOP and
      monitor recovery stage instead of masking repeated preconditions.
- [ ] Verify signed Demo position reconciliation, STOP-only supervision and
      no duplicate orders after network/reboot chaos test on physical phone.
- [ ] Only after P0 passes merge PR chain sequentially to main.
