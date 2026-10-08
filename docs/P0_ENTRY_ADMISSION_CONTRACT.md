# P0 Entry Admission Contract (WMS-CR-1.0)

Status: controlled implementation draft; validation remains in progress. See `docs/P0_EXECUTION_SCOPE_AND_CLOSURE.md` for market coverage and the deadline architecture analysis.

## Scope

This contract applies to every autonomous Spot BUY that opens or increases exposure. It does not gate SELL operations used to close exposure, restore or modify protective orders, cancel orders, or reconcile exchange state.

## Supported entry purposes

- `ENTRY`: an entry intent using the same mandatory signal and context contract.
- `CAMPAIGN_ENTRY`: a new initial Williams campaign entry.
- `CAMPAIGN_ADD_ON`: a later entry that increases an already-open campaign.

Any BUY with another or missing purpose is rejected. A caller cannot obtain fewer checks by choosing a different purpose string.

## Mandatory fields for every entry intent

- `symbol`, `side=BUY`, `order_type`, `client_order_id`.
- Stable non-empty `signal_id`.
- Positive absolute `signal_expires_at_ms`, copied from the source signal's resolved deadline.
- Non-empty `required_context_versions` mapping, representing the context snapshot on which the signal was admitted; it must include `permission_interval`.
- Non-empty `permission_interval`, naming the operative timeframe whose current direction permission is authoritative.
- `campaign_id` for `CAMPAIGN_ENTRY` and `CAMPAIGN_ADD_ON`.
- Non-empty `intent_id`.
- Intent creation timestamp as a positive integer, not in the future, within a positive integer `max_age_ms` policy.
- Exactly one of positive finite `quantity` or `quote_order_quantity`; current `STOP_LOSS` entry requires base `quantity`.
- For a conditional `STOP_LOSS` entry, explicit positive finite `trigger_price` must be above the positive `invalidation_level`. The trigger cannot remain implicit only inside a submit closure.
- A client order ID accepted by the current Spot adapter's conservative 1–36 character format check.
- For the currently approved Campaign Engine Spot path, entry `order_type` is limited to `STOP_LOSS`; `MARKET` remains accepted only for the generic `ENTRY` contract where explicitly used by a tested caller.

## Admission checks

1. Reject expired intent and signal deadlines. Deadline equality means expired.
2. Reject missing required context versions, missing context snapshots, or any version mismatch.
3. Reject missing operative timeframe context.
4. Reject LONG/BUY when the operative context does not allow long; reject SHORT/SELL when it does not allow short.
5. Reject a campaign entry without campaign identity.
6. Run the strategy-specific pre-submit checks already defined by the caller.
7. Revalidate time-sensitive intent/context conditions immediately before the exchange call. The shared context lock prevents context publication between validation and submission.
8. The intent must be durably saved before a new entry submission; absent storage or a persistence error blocks the entry.
9. Revalidate the contract immediately before the exchange call.
10. Never blindly retry an ambiguous exchange mutation. Reconcile by the durable client order ID first.
11. Validate context version types, source timestamps, and positive finite price. These checks reject malformed/unavailable data but do not define a maximum age for an already-published context.

An empty `required_context_versions` mapping is never an implicit pass for a new entry.

## Signal deadline semantics

An explicit positive `SignalSpec.expires_at_ms` is authoritative even if it is already expired. It is never recomputed relative to the scan time. Legacy signals without an explicit deadline derive one from the original signal bar timestamp only; an absent/invalid origin timestamp fails closed.

The current code defaults remain unchanged pending methodology review:
- Reversal: `WILLIAMS_PENDING_REVERSAL_BARS` (default 2 bars).
- Super AO: `WILLIAMS_PENDING_SUPER_AO_BARS` (default 2 bars).
- Fractal: `WILLIAMS_PENDING_FRACTAL_BARS` (default 8 bars).

These are existing implementation defaults, not claimed as author-certified universal Williams rules. Their methodological validity remains an open decision.

A persisted conditional entry that remains untriggered after expiry must be cancelled and the exchange response reconciled. Missing expiry in an old persisted campaign is not treated as permission to keep the order armed. This is best-effort while the process is running or on recovery: an exchange-hosted conditional order can still trigger while the bot is offline. Strict no-fill-after-expiry semantics require a separately approved design for server-side expiry or client-managed trigger submission; P0 does not claim that property is solved.

## Williams Core preservation

This contract does not add a universal Alligator + AO + AC + Wave conjunction. Signal formation remains specific to Reversal, Super AO, and Fractal models. The barrier checks intent identity, expiry, required context version consistency, operative direction permission, and the already-defined strategy-specific last-mile checks; it does not reconstruct every indicator model at the point of submission.

## Safe legacy mode

When `CAMPAIGN_ENGINE=false`, the legacy `MultiPositionTrader` autonomous MARKET-BUY route is disabled because it cannot provide the same conditional Williams signal/expiry contract. The legacy `Trader.market_buy(quote)` method is also fail-closed because a quote amount alone does not identify a Williams event. Position exits, protective actions, and reconciliation remain separate paths.

## Open decisions

1. Confirm the signal-lifetime defaults per model against the complete approved Master Specification and primary Williams materials.
2. Define a sourced maximum age/freshness policy for already-published higher-timeframe context. P0 enforces exact context version consistency and direction permission but does not invent a universal HTF age threshold.
3. Confirm whether any approved autonomous entry model outside the three current `SignalType` values exists before allowing another purpose/type.
4. Decide how to enforce absolute expiry for exchange-hosted conditional orders during process/network downtime; the current exchange order can outlive the signal until cancellation is processed.


## Current concrete market adapter

The implemented live-order adapter is Binance Spot. No Futures, margin, or second-exchange execution adapter is established by this contract. Campaign Entry uses exchange-hosted conditional Spot `STOP_LOSS`; campaign protection is a separately managed SELL stop, not native OCO. The legacy Trader's OCO path is not the approved autonomous BUY path and remains fail-closed for new uncontracted entries.

## Mandatory persistence semantics

A new entry is blocked if `save_execution_intent(..., "PENDING")` is unavailable or fails. If the exchange submission appears to have succeeded but the durable `SUBMITTED` status update fails, the outcome is treated as reconciliation-required; the caller must not report an unqualified clean success. Protection, exits, cancellations and reconciliation are not blocked solely because entry-intent persistence is unavailable.

## Freshness boundary

Exact context-version equality does not prove context freshness. The barrier rejects malformed, missing, non-positive, or future candle-close timestamps and invalid prices. A maximum context-age threshold remains unapproved and must not be invented universally; context freshness is not certified until a sourced/approved policy is added.
