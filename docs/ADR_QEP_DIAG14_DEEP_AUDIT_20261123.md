# Williams Bot — QEP v1.0 deep audit DIAG14 (2026-11-23)

Repository: `19wolfalone92/Williams` (NOT Williams AI).
Evidence: supplied `Williams_Diagnostics (14).zip`, 47 entries, 6,241,798 bytes.
Capture: 2026-11-23 09:26:53.409 UTC. APK: `4.64.51-debug`, Android SDK 37,
Samsung test device (device identifier withheld). Installed source revision `c0b9f31aba98246cfef7d195f5dc71c5e7c0fd80`
(PR #267 merge candidate). All numeric trading examples in public tests are synthetic; exact account quantities, prices and client IDs remain in the locally supplied ZIP only. This is a **historic signed read**; not live access
to the user's Binance account.

## Audited chain / baseline

Diagnostic graph: 25 cores, 42 dependencies, 79 component checks:
62 PASS, 4 WARNING, 13 NOT_VERIFIED, 0 FAIL. No result proves
FILLED→STOP timing or a continuously running Android service.
No attempt is made to relabel NOT_VERIFIED as PASS.

| Subsystem | Observed signed/source evidence | Remaining gap |
|--|--|--|
| Binance signed account | REST HTTP 200, signed positions / algos, risk snapshot | No global full-success reconciliation; old opposite campaign |
| Public data | 15/15 H1 history, recent quotes, public WS up | Historical wave validity / external experts not certified |
| Private stream | Connected, signed user-stream sync proven; durable queue 0 | Real disconnect/reboot continuity and physical SL issuance |
| Open ETH | signed LONG (account quantity and price redacted) | No verified original TC2 LONG ENTRY ownership |
| Exchange STOP | SELL close-all MARK_PRICE, unique, `priceProtect=false`; exact client ID redacted | Independent stop protection observed, NOT a Williams-owned ENTRY |
| Stop-only supervision | `AUTO_VERIFIED_STOP_ONLY`, ACTIVE, two independent signed observations over 30s apart, current review WAITING_FOR_STRUCTURE | Reversed-campaign branch erroneously hid this signed management scope |
| Historic ENTRY | older ETH SHORT, verified historic journal, no current signed matching exposure | Reversed relative to current signed LONG; NEVER transfer ownership |
| EXIT | ETH LONG reduce-only, `SUBMISSION_UNKNOWN` from earlier session | Query exact signed clientOrderId; never resubmit or pretend flat |
| Risk | Daily 3%, 0/3 consecutive losses; risk reservation 0.0004 of 0.006 total | Actual unowned exposure risk/liquidation NOT_VERIFIED |
| Android resources | moderate Java heap within declared limit, private events 0, rate headroom observed | Snapshot cannot certify peak memory/Doze/reboot |
| SQLite | 407 canonical intents / 407 shadow, parity PASS; canonical remains legacy JSON | Full migration and signed fill forensics incomplete |
| Books | TC2 execution only, H1 decisions, NTD/Course observers | Independent four-book fidelity / future H1 trade exam NOT VERIFIED |
| Runtime | `RECONCILE_REQUIRED`, user pause=true, signed STOP visible | No verified stable new-exposure authorization, must remain HOLD |

There were 145 repeated `COCKPIT_RECONCILIATION` events among 150 bounded
timeline entries, all blocked on the old opposite-side ETH campaign.
`last_successful_reconciliation_completed_at_ms=0` means NO fully
authorized post-conflict account reconciliation. It is NOT evidence of
Binance downtime or lack of a current signed stop.

## Confirmed code root causes

### P0-A: independently approved supervisor disappears inside reversed-campaign branch

`NativeFuturesCampaignCoordinator.reconcile()` calculates
`reversedCampaignSymbols` and enters a special guard which ALWAYS wrote
`REVERSED_CAMPAIGN_QUARANTINE` / `automatic_trailing_authorized=false`
for ETH. The branch `continue` bypassed the later signed
`supervisedProtection` check. This is a real missing chain edge:
signed Binance STOP → separate supervised operator journal → management
capability. The existing STOP-only executor itself had independent signed
checks and protective-first replacement, but never received the signed
eligible observation in this case.

**Change:** `FuturesQuarantinedStopOnly.kt` binds the approved supervision to
the SAME signed position/entry/quantity/direction, one signed symbol STOP,
one signed account STOP with same fingerprint, mark-relative trigger, no
open standard orders, exact exchange client ID and strict flags. AUTO
supervision also requires the two durable signed reads >=30 seconds apart
and recent H1/H4 evaluation. Only this narrowly checked stop-only scope
may appear as `AUTO_VERIFIED_STOP_ONLY`; strategy ownership remains
`false`, old opposite journal untouched and new entries blocked.

### P0-B: ambiguous LONG exit remains permanently uninspected behind old SHORT

The `if(symbol in reversedCampaignSymbols) continue` happened BEFORE
the generic `role==EXIT` signed GET. Therefore the exact
`SUBMISSION_UNKNOWN` MARKET_REDUCE_ONLY record was never revisited
while the old SHORT conflict persisted.

**Change:** `FuturesQuarantinedExitObservation.kt` only accepts the exact
signed `GET order`: symbol, client ID, BUY/SELL, MARKET, BOTH,
`reduceOnly=true`, original qty, executed qty <= requested and valid
orderId. A definite terminal order is recorded as
`EXIT_TERMINAL_POSITION_OPEN`, NEVER as signed flat; active orders
remain pending. An absent / unqueryable order stays UNKNOWN.
Zero resubmissions/cancellations/position-closure assumptions.

### P1-C: unowned is NOT the same as unmanageable

The EXE-12 diagnostic described every unowned position as non-manageable,
despite an independent signed STOP-only authority.
The diagnostic now reports the strictly verified STOP-only scope
separately while continuing to FAIL / NOT_VERIFY original ENTRY ownership.
EXE-03/04 signed STOP coverage remains independent.

## Required strict falsification (unit fixtures)

- Same-direction approved, signed full stop with exact mark, side, qty,
  level, id and two signed snapshots → ONLY STOP-only scope is eligible.
- Wrong price, sign, type, status, missing / duplicate STOP,
  stale H1 review, missing two snapshots or open symbol order → NOT ELIGIBLE.
- Never approve an unowned historical SHORT as the current LONG ENTRY.
- No signed EXIT response → preserve SUBMISSION_UNKNOWN, never POST again.
- Exact FILLED EXIT with still-open signed position → terminal old order,
  **not** flat / trading-authorized. Other statuses fail closed.
- Never override operator pause, three consecutive losers, daily loss 3%,
  margin ≤50%, TC2 only, Demo-only, or independent Execution Door.

## Outstanding P0 and validation requirements (not claimed fixed)

1. Real signed Binance Demo revalidation after installing this exact build:
   supervisor signed scope, signed EXIT query, actual current position and STOP,
   zero accidental orders, STOP-only ratchet when TC2 permits, rollback.
2. Independent sustained Android 37 / Samsung foreground, Doze, reboot,
   network cut and WebSocket resubscription; an emulator API35/36 CI pass
   does not certify physical runtime.
3. Signed actual fill-to-STOP first creation, partial execution protection,
   STOP POST rejection, timeout recovery without blind retries.
4. Historical LONG ENTRY provenance still UNKNOWN. No automatic attempt to
   'reconcile away' the opposite SHORT or give it new-trade ownership.
5. Independent historical H1 / book fidelity, unverified wave hypotheses,
   actual-risk/liquidation proof for unowned positions.
6. Review CI and PR chain sequentially. Leave `main` and all non-bot repos
   unchanged pending full P0 acceptance.

All changes occur only in the new branch / PR; no Binance Demo mutation is
performed by this audit itself.
