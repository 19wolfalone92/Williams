# WILLIAMS BOT 2.1 — TASK 001.2
## Architecture Ownership, Integration Contracts & Cross-Module Audit

**Итоговый статус: BLOCKED — OWNERSHIP, MUTATION CLOSURE И FINANCIAL ATOMICITY НЕ ДОКАЗАНЫ**  
Дата аудита: 2026-10-11 UTC  
Репозиторий: `19wolfalone92/Williams`  
Рабочая ветка: `audit/williams-2.1-task-001-architecture`  
PR: [#47 — OPEN / DRAFT / NOT MERGED](https://github.com/19wolfalone92/Williams/pull/47)  
Исходный проверенный snapshot для кода: `cb0a5b1046502d92dfcc8eb1038a9ee4fe995fbc`  
Base/main SHA на момент проверки: `04da5f9c09572e99ff310be3a1908179165e528c`  
HEAD ветки после добавления документов TASK 001.2: `337ca4c36463c0f68e8e641a8f08cf69ddc8f87a`  
Parent текущего HEAD: `72c4710aec838279cc1651f99349a66c903a395c`

## 1. Краткое решение

TASK 001.2 **не принят как PASS**. Проверены GitHub refs/PR metadata, diff относительно base, CI snapshot и исходные файлы ключевых Python/Android компонентов. Добавлены пять требуемых вспомогательных документов и обновляется этот отчёт.

Подтверждено:
- PR #47 открыт как Draft, не merged; base `main`, base SHA `04da5f9c09572e99ff310be3a1908179165e528c`.
- На момент проверки PR head был `cb0a5b1046502d92dfcc8eb1038a9ee4fe995fbc`; сравнение с base показало 135 commits ahead, 0 behind, merge base совпадает с base SHA.
- Python и Android имеют отдельные execution/persistence пути. В обоих обнаружены durable mutation/ledger компоненты, но это не доказывает единственного executor или финансового writer.
- Python `ExecutionBarrier`, Spot adapter, Campaign execution и Android signed mutation transport существуют; полное закрытие всех callers и обходов не доказано.
- TASK 001.15 ранее уже фиксировал BLOCKED по writer closure и atomicity. TASK 001.2 не трактует существование этих документов или тестов как устранение блокеров.

Не подтверждено:
- локальный clean checkout, `git status`, локальный запуск тестов и exit codes;
- исчерпывающий AST/call graph всех order mutations;
- единственные владельцы Campaign, Position, fill effects, fees/PnL, risk reservation и kill switch;
- exactly-once применение fill ко всем downstream projections;
- APK ↔ source provenance и физическое устройство;
- полный Spot Testnet lifecycle;
- совместимость TASK 002–008 на одном candidate integration SHA.

## 2. Baseline, refs и воспроизводимость

| Проверка | Наблюдение | Статус |
|---|---|---|
| Target branch / PR | PR #47 указывает `audit/williams-2.1-task-001-architecture`, OPEN, DRAFT, NOT MERGED | VERIFIED |
| Code/source snapshot | `cb0a5b1046502d92dfcc8eb1038a9ee4fe995fbc` | VERIFIED as PR head at audit start |
| Parent of source snapshot | `c893be3358052774175f5ad3c23c731b50c699a0` (GitHub commit API) | VERIFIED |
| Base/main | `04da5f9c09572e99ff310be3a1908179165e528c` | VERIFIED via PR base and compare |
| Compare base → source snapshot | 135 ahead, 0 behind; merge base = base SHA | VERIFIED |
| PR changed files | 27 files, including Python execution/persistence, Android runtime, tests, workflows and prior reports | VERIFIED |
| Local checkout / worktree | No local checkout available to this task | NOT CHECKED; no `git status` claim |
| Local commands and exit codes | No shell commands could be executed against a local clone in this task | NOT RUN; not fabricated |
| CI for source snapshot `cb0a5b1...` | Python backend tests SUCCESS (run 38142652068 / #2453); Williams Campaign CI SUCCESS (38142652065 / #4519); Build Android APK SUCCESS (38142652069 / #2112) | CI metadata VERIFIED |
| CI for documentation commits | For `337ca4c...`, workflow endpoint returned Python run 38144474587 / #2480, Android APK run 38144474639 / #2139, Campaign CI run 38144474589 / #4593 as QUEUED at snapshot | PENDING; must re-check |
| Main/production changes by this task | No merge, no main write, no production code or strategy change in TASK 001.2 | VERIFIED by write scope |
| Exchange activity | No Mainnet request and no real order submitted by this audit | VERIFIED by task actions |

The five new documents were added as separate commits on the existing audit branch; one further documentation-only correction pinned the exact market-context blob SHA. Those commits do not establish runtime safety. The source-code evidence below remains anchored to source snapshot `cb0a5b1046502d92dfcc8eb1038a9ee4fe995fbc`; source blob SHAs identify individual file contents, not commit SHAs.

## 3. Actual architecture: observed path versus target path

Target audit spine:
`Market Data → Strategy → PendingSignal → Risk Engine → Campaign → Order Intent → ExecutionBarrier → Binance → Fill → Position → Financial Ledger → Recovery`.

This is a useful contract checklist, **not a claim that the repository implements one closed linear runtime**.

### Python path (partially observed)

```text
Binance REST / market stream
  → candle/history adapters and persisted market history
  → mtf_context_service.py / ContextCache / versioned TFMarketContext
  → Trader / strategy.py / Williams signal modules
  → SignalSpec / PendingSignal / CampaignEngine
  → MultiPositionTrader.execute_campaign(...)
  → CampaignExecutionService.arm_initial_entry(...) / arm_add_on(...)
  → db.py campaign / reservation / execution-intent persistence
  → ExecutionBarrier.execute(OrderIntent, callback)
  → binance_client.py / BinanceSpotClient
  → exchange order / trade / balance facts
  → fill accumulator and recovery/reconciliation
  → Campaign / Position / trade / fee / PnL projections
  → protection / exit through execution service and barrier
```

### Android standalone path (partially observed)

```text
TradingForegroundService / StandaloneRuntime worker
  → REST / stream / history warmup and candidate scan
  → signal / PendingEntry
  → persisted state + ExecutionGate reservation
  → signedPost / signedDelete
  → signedRequest / authorizeExchangeMutation
  → TradingAuditStore.beginMutationIntent (SQLite, before network)
  → performSignedRequest
  → persist exchange outcome or UNKNOWN
  → recovery / reconcile before new BUY
```

Android has its own runtime, mutation transport, SQLite audit store, execution accumulator and campaign/position state. It is not proven to be merely a UI for the Python executor. If both can mutate the same Binance account without fencing, the architecture has a potential competing-executor conflict.

### Transition evidence summary

| Transition | Evidence in source | Status |
|---|---|---|
| Market data → versioned context | `mtf_context_service.py`, `market_context.py`; blob `ac38e50ec7ea125047ce7e39d8de3c0ee711aada` for MTF service | Components exist; WS gap recovery and Android parity not proven |
| Context → Strategy | `Trader`, `strategy.py`, Williams core; shared cache injection described in architecture report | Partially traced; runtime call graph not executed |
| Strategy → PendingSignal/Campaign | `digital_williams_core.py`, `williams_signals.py`, `campaign_engine.py`, `campaign_model.py` | TASK 004 source parity/causality remains separate and not accepted here |
| Campaign/Risk → OrderIntent | `campaign_execution.py`; blob `699b28b1de25f90465336060ab6162537ef82668` | Intent contract visible; reservation and financial transaction coupling unproven |
| Intent → ExecutionBarrier | `execution_barrier.py`; blob `c0e7cf4eea424d037b273e6150fa2d3d0ea93b40` | Barrier exists; every caller/bypass not exhaustively closed |
| Barrier → Binance mutation | `binance_client.py`; blob `df3394990addd03dff4225ff8e62111412191f7e` | Spot adapter exists; mutation surface inventory remains partial |
| Binance → Fill | Python accumulators/DB and Android `ExecutionAccumulator.kt` / `TradingAuditStore.kt` | Fill stores/deduplication exist; all downstream exactly-once effects not proven |
| Fill → Position/Campaign/ledger | `db.py`, `campaign_execution.py`, `portfolio_trader.py`, `trader.py`, Android runtime and journal | Multiple writer candidates; single-owner/atomicity not proven |
| Restart → Recovery | Python campaign/portfolio/trader recovery; Android recovery/reconcile | Components exist; multi-process, crash-boundary and device recovery evidence missing |

## 4. Required artifacts created/updated

All five supporting documents are committed to the existing branch. Their blob SHA values are recorded below for reproducibility.

1. [ARCHITECTURE_OWNERSHIP_MATRIX.md](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-001-architecture/docs/ARCHITECTURE_OWNERSHIP_MATRIX.md) — blob SHA `dba856808c97fbdd86016a745dcfbaf050eee869`. Lists candidate owners, observed writers, unresolved conflicts and acceptance evidence.
2. [CROSS_MODULE_CALL_GRAPH.md](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-001-architecture/docs/CROSS_MODULE_CALL_GRAPH.md) — blob SHA `6cfc27355abaf5010966747b3d9b4dcc33a96ff8`. Maps Python and Android paths separately; marks unverified edges.
3. [EXECUTION_PATH_INVENTORY.md](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-001-architecture/docs/EXECUTION_PATH_INVENTORY.md) — blob SHA `8898eb80da12e44c691439103b030149fd246c1a`. Lists Spot order/cancel/cancel-replace/OCO/bulk cancel and Android mutation surfaces with required gates.
4. [INTEGRATION_CONTRACT_MATRIX.md](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-001-architecture/docs/INTEGRATION_CONTRACT_MATRIX.md) — blob SHA `6c16a6627728839e8ab479bd996f19bba25f9d7b`. Maps TASK 002–008 provenance, context, signal, risk, identity, ledger, recovery, Android and Testnet contracts.
5. [P0_BLOCKER_REGISTER.md](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-001-architecture/docs/P0_BLOCKER_REGISTER.md) — blob SHA `36a02d9cc0a557502a59925aa4bff9b1386ae906`. Tracks 14 P0 blockers and P1/P2 follow-up.
6. [REPORT_RU.md](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-001-architecture/REPORT_RU.md) — this report, to be pinned to the commit produced by this update.

Existing evidence documents reviewed include:
- `docs/WILLIAMS_BOT_2_1_TASK_001_ARCHITECTURE_AUDIT.md` — blob SHA `6617c75b55e4afae1dc47ca047db66821fcd96ed`.
- `docs/WILLIAMS_BOT_2_1_TASK_001_12_EXECUTABLE_SOURCE_RECOVERY.md` — blob SHA `15a0e31a848e5d0c11870b0d46b65d838352ec05`.
- `docs/WILLIAMS_BOT_2_1_TASK_001_14_CROSS_PLATFORM_FINANCIAL_WRITER_CLOSURE.md` — blob SHA `afe50e7de0f089a56c0e97c3baa92dd3bdb74309`.
- `docs/WILLIAMS_BOT_2_1_TASK_001_15_COMPLETE_WRITER_CLOSURE_FINANCIAL_OWNERSHIP_ATOMICITY_PROOF.md` — blob SHA `b922b3231f095fb78ee08960b7ad42c9513546ad`; its conclusion is BLOCKED.

## 5. ExecutionBarrier and mutation findings

Known Python Spot REST mutation families:
- `POST /api/v3/order` — standard/conditional order submit.
- `DELETE /api/v3/order` — cancel order.
- `POST /api/v3/order/cancelReplace` — cancel-replace.
- `DELETE /api/v3/openOrders` — bulk cancel.
- `POST /api/v3/orderList/oco` and compatibility wrappers — OCO/protection creation.
- `DELETE /api/v3/orderList` — OCO/order-list cancel.

Android transport audit describes `signedRequest` as the common gate above `performSignedRequest`, with POST/DELETE intent journaled before network. These findings are not exhaustive proof that no other writer exists.

Important observed safeguards:
- `ExecutionBarrier._persist` requires a durable intent store; persistence failure must not silently permit execution.
- The Spot client documents that ambiguous POST/DELETE outcomes are not blindly retried; timeout/5xx can mean exchange acceptance.
- Android `TradingAuditStore` contains orders/executions/trades and a mutation-intent journal.
- The Spot client defaults to Testnet but accepts a production-mode configuration. Default values do not replace a fail-closed Testnet release gate.

Still open:
- Full AST/call graph from every mutation endpoint through every caller.
- Prove all entry, add-on, protection, manual, emergency, recovery, cancel and replace routes pass through the required boundary or fail closed.
- Prove no second process/platform can mutate the same account.
- Fault injection for persistence failure, accepted-but-response-lost, response-persist failure, cancel-replace partial success, OCO partial fill and restart.
- Reconcile all exchange order/trade/balance facts before releasing new risk.

## 6. Ownership and financial atomicity findings

The static source evidence identifies more than one writer candidate for critical state:
- Campaign: `campaign_engine.py`, `campaign_execution.py`, `portfolio_trader.py` and Android campaign state.
- Position/trade projections: `db.py` generic APIs and direct SQL callers, `portfolio_trader.py`, legacy `trader.py`, Android `StandaloneRuntime.kt` / `TradeJournal.kt`.
- Fill observation/application: Python fill accumulator and database plus Android execution accumulator/audit store.
- Fees/PnL: Python trade/campaign persistence and Android trade/financial records.
- Risk reservation: campaign/risk/preflight persistence; complete atomic reserve→intent→fill/release semantics not established.
- Kill Switch/Safe Mode: control and gate logic exists on the Python and Android sides; a single durable cross-route latch is not proven.

Therefore:
- **Campaign single writer: NOT PROVEN.**
- **Position single writer: NOT PROVEN.**
- **Financial ledger single writer: NOT PROVEN.**
- **Exactly-once fill application end-to-end: NOT PROVEN.**
- **One execution authority per account: NOT PROVEN.**

A unique fill row or event-deduplication key alone is insufficient: every downstream financial effect must be idempotent and atomic or recoverably journaled. Python SQLite and Android SQLite/SharedPreferences do not share one transaction boundary.

## 7. TASK 002–008 contract compatibility

| Task | Contract needed by TASK 001 | Current result |
|---|---|---|
| 002 — Trusted Baseline / Version / Release Gate | Exact source/base refs, artifact provenance, one version, reproducible CI | Baseline refs established for PR snapshot; local checkout and final candidate release evidence remain open |
| 003 — Execution Safety | Complete mutation graph, durable intent, UNKNOWN/no blind retry | Partial implementation evidence; all-caller closure and fault matrix open |
| 004 — Williams Strategy | Causal signal schema, closed-bar/fractal confirmation, strategy version, Python–Android parity | Not accepted by this audit; source-to-code task evidence required |
| 005 — Risk Engine | Decimal sizing, invalidation, fee/filter/exposure and atomic reservation contract | Risk paths exist; ownership and atomicity not proven |
| 006 — Android Runtime | APK/source SHA, installed version, credential endpoint, one executor, recovery logs | BLOCKED: physical-device provenance not supplied |
| 007 — Recovery / Ledger | Fill identity, single financial writer, transaction boundary, restart/replay | BLOCKED: prior TASK 001.15 report also BLOCKED |
| 008 — Spot Testnet | Explicit Testnet-only configuration, exchange IDs, complete order/fill/protection/exit/recovery trace | BLOCKED: full lifecycle not proven |
| 009 — Integration gate | One candidate SHA with accepted TASK 002–008 evidence and complete checks | BLOCKED: no accepted cross-branch evidence manifest |

Distinct identifiers must remain distinct: `signal_id`, `campaign_id`, `intent_id`, `clientOrderId`, exchange `orderId`, `orderListId`, exchange `tradeId/fill identity`, `financial_effect_id`, and `risk_reservation_id`. Do not infer a fill from an acknowledgement or a FLAT position from missing local state.

## 8. P0 blocker summary

The blocker register contains full closure criteria. The critical open items are:
1. Choose and enforce one execution authority for each Binance account.
2. Exhaustively enumerate order mutations and prove barrier exclusivity.
3. Establish single owners for Campaign and Position projections.
4. Prove exactly-once financial effects for fills, fees and PnL.
5. Prove UNKNOWN/restart/cancel-replace/OCO recovery through fault injection.
6. Prove reservation/intent/fill transaction and race safety.
7. Prove APK/source provenance and physical-device runtime behavior.
8. Complete a Spot Testnet order → fill → protection → exit → restart/reconcile lifecycle.
9. Integrate TASK 002–008 on one exact candidate SHA.
10. Reproduce the baseline in a clean checkout and retain commands, exit codes and artifact hashes.
11. Prove risk, Kill Switch and Safe Mode gates cover all Python and Android entry paths.
12. Close version/artifact provenance in the release gate.

## 9. Commands, output and return codes

No local shell commands were run because no executable local checkout was available in this task. Therefore there are no local command exit codes to report; none are invented.

Remote evidence/actions:
- PR metadata read: #47 returned OPEN, DRAFT, base `main`, base SHA `04da5f9...`, head `cb0a5b1...` at source snapshot.
- Commit comparison: base `04da5f9...` to source `cb0a5b1...` returned 135 ahead / 0 behind and merge base = base SHA.
- Workflow endpoint for source snapshot: Python backend tests, Campaign CI and Android APK all returned completed / success.
- Workflow endpoint for documentation snapshot `337ca4c...`: Python, Campaign and Android workflows returned QUEUED at observation time; must be checked again after this report commit.
- Remote file fetches returned the blob SHAs listed above.
- Documentation commits were made only on the existing audit branch. No merge, main write, production source edit, Binance Mainnet request or real order was performed.

## 10. Acceptance criteria and final decision

**PASS** requires the critical owners and integration contracts to be proven with exact-SHA evidence and required tests.  
**FAIL** requires a reproducible violation of a named invariant on an exact source/test SHA.  
**BLOCKED** is required when baseline, complete caller graph, ownership, fault evidence, device provenance or Testnet lifecycle is missing.

**TASK 001.2: BLOCKED — NOT ACCEPTED AS PASS.**

The architecture intent is now mapped into explicit ownership, call graph, mutation, integration-contract and blocker documents. The implementation still has unresolved cross-platform execution/financial ownership and missing executable evidence. Do not use Binance Mainnet or authorize real orders based on this audit.
