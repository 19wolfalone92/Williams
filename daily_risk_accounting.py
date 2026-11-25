"""Williams Bot UTC daily-risk Model B contract and evidence reconstruction."""
from __future__ import annotations
from decimal import Decimal, InvalidOperation, localcontext
import hashlib

MODEL_VERSION = 2
UTC_DAY_MS = 86_400_000
DAILY_ENTRY_LOSS_LIMIT = Decimal("0.03")
EMERGENCY_BREAKER_LIMIT = Decimal("0.05")
CAPITAL_FLOW_TYPES = frozenset({
    "TRANSFER", "INTERNAL_TRANSFER", "DEPOSIT", "WITHDRAW", "WELCOME_BONUS",
    "BONUS", "CONTEST_REWARD", "AUTO_EXCHANGE", "COIN_SWAP_DEPOSIT",
    "COIN_SWAP_WITHDRAW", "CROSS_COLLATERAL_TRANSFER",
    "PORTFOLIO_MARGIN_TRANSFER", "STRATEGY_UMFUTURES_TRANSFER",
})
TRADING_INCOME_TYPES = frozenset({
    "REALIZED_PNL", "FUNDING_FEE", "COMMISSION", "INSURANCE_CLEAR",
    "REFERRAL_KICKBACK", "COMMISSION_REBATE", "API_REBATE",
    "OPTIONS_PREMIUM_FEE", "OPTIONS_SETTLE_PROFIT", "DELIVERED_SETTELMENT",
    "POSITION_LIMIT_INCREASE_FEE", "FEE_RETURN", "BFUSD_REWARD",
})

class DailyRiskDataError(ValueError):
    pass

def decimal_value(value, field):
    if value is None or isinstance(value, bool):
        raise DailyRiskDataError(f"{field}: missing numeric value")
    try:
        result = value if isinstance(value, Decimal) else Decimal(str(value).strip())
    except (InvalidOperation, ValueError, TypeError) as exc:
        raise DailyRiskDataError(f"{field}: malformed decimal") from exc
    if not result.is_finite():
        raise DailyRiskDataError(f"{field}: non-finite decimal")
    return result

def account_fingerprint(api_key):
    value = str(api_key or "").strip()
    if not value:
        raise DailyRiskDataError("Futures account identity is unavailable")
    return hashlib.sha256(value.encode("utf-8")).hexdigest()

def calculate_daily_risk_model_b(start_equity, current_equity, net_external_flows):
    e0 = decimal_value(start_equity, "E0")
    et = decimal_value(current_equity, "Et")
    cf = decimal_value(net_external_flows, "CF")
    if e0 <= 0:
        raise DailyRiskDataError("E0 must be positive; percentage risk is undefined")
    if et < 0:
        raise DailyRiskDataError("Et cannot be negative")
    with localcontext() as ctx:
        ctx.prec = 34
        pnl = et - e0 - cf
        loss = max(Decimal(0), -pnl)
        return {
            "start_equity": e0, "current_equity": et, "net_external_flows": cf,
            "daily_pnl": pnl, "daily_loss": loss,
            "loss_fraction": loss / e0, "loss_pct": loss / e0 * Decimal(100),
            "risk_adjusted_equity": et - cf,
            "entry_blocked": loss >= e0 * DAILY_ENTRY_LOSS_LIMIT,
            "emergency_breaker_tripped": loss >= e0 * EMERGENCY_BREAKER_LIMIT,
        }

def summarize_income_rows(rows, *, day_start_ms, end_ms,
                          snapshot_window_start_ms, snapshot_window_end_ms):
    if not isinstance(rows, (list, tuple)):
        raise DailyRiskDataError("Futures income history is not a list")
    if not (0 <= day_start_ms <= snapshot_window_start_ms <= snapshot_window_end_ms <= end_ms
            and end_ms < day_start_ms + UTC_DAY_MS
            and snapshot_window_end_ms < day_start_ms + UTC_DAY_MS):
        raise DailyRiskDataError("Futures income observation window is invalid or crosses UTC midnight")
    total = capital = trading = Decimal(0)
    seen, symbols = {}, set()
    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            raise DailyRiskDataError(f"Malformed income row {index}")
        kind = str(row.get("incomeType", "")).strip().upper()
        asset = str(row.get("asset", "")).strip().upper()
        if not kind or asset != "USDT":
            raise DailyRiskDataError(f"Unclassified or non-USDT income row {index}")
        amount = decimal_value(row.get("income"), f"income[{index}]")
        try:
            stamp = int(row.get("time"))
        except (TypeError, ValueError, OverflowError) as exc:
            raise DailyRiskDataError(f"Income row {index} timestamp invalid") from exc
        if stamp < day_start_ms or stamp > end_ms:
            raise DailyRiskDataError(f"Income row {index} outside requested UTC day")
        if snapshot_window_start_ms <= stamp <= snapshot_window_end_ms:
            raise DailyRiskDataError("Income event overlaps account/position snapshot window")
        tran_id = str(row.get("tranId", "")).strip()
        if not tran_id:
            raise DailyRiskDataError(f"Income row {index} lacks transaction identity")
        identity = (kind, asset, tran_id)
        signature = (stamp, amount, str(row.get("symbol", "")).upper(), str(row.get("tradeId", "")))
        if identity in seen:
            if seen[identity] != signature:
                raise DailyRiskDataError("Conflicting duplicate income transaction")
            continue
        seen[identity] = signature
        if kind in CAPITAL_FLOW_TYPES:
            capital += amount
        elif kind in TRADING_INCOME_TYPES:
            trading += amount
        else:
            raise DailyRiskDataError(f"Unclassified Binance Futures incomeType: {kind}")
        total += amount
        symbol = str(row.get("symbol", "")).strip().upper()
        if symbol:
            symbols.add(symbol)
    return {"total_income": total, "capital_flow": capital, "trading_income": trading,
            "row_count": len(seen), "symbols": tuple(sorted(symbols))}

def reverse_position_at_midnight(current_quantity, current_entry_price, trades, *,
                                 symbol, day_start_ms, snapshot_window_start_ms,
                                 snapshot_window_end_ms):
    q = decimal_value(current_quantity, f"{symbol}.positionAmt")
    entry = decimal_value(current_entry_price or "0", f"{symbol}.entryPrice") if q != 0 else None
    if q != 0 and (entry is None or entry <= 0):
        raise DailyRiskDataError(f"{symbol}: active position has no average entry")
    if not isinstance(trades, (list, tuple)):
        raise DailyRiskDataError(f"{symbol}: userTrades history is not a list")
    normalized, seen = [], set()
    for index, row in enumerate(trades):
        if not isinstance(row, dict) or str(row.get("symbol", symbol)).upper() != symbol.upper():
            raise DailyRiskDataError(f"{symbol}: malformed or foreign trade row {index}")
        try:
            trade_id, stamp = int(row.get("id")), int(row.get("time"))
        except (TypeError, ValueError, OverflowError) as exc:
            raise DailyRiskDataError(f"{symbol}: trade identity/timestamp invalid") from exc
        if trade_id in seen:
            raise DailyRiskDataError(f"{symbol}: duplicate userTrades id {trade_id}")
        seen.add(trade_id)
        if stamp < day_start_ms or stamp > snapshot_window_end_ms:
            raise DailyRiskDataError(f"{symbol}: trade outside requested UTC day")
        if snapshot_window_start_ms <= stamp <= snapshot_window_end_ms:
            raise DailyRiskDataError(f"{symbol}: trade overlaps account/position snapshot window")
        if str(row.get("positionSide", "BOTH")).upper() != "BOTH":
            raise DailyRiskDataError(f"{symbol}: hedge-mode trade cannot be reconstructed")
        side = str(row.get("side", "")).upper()
        if side not in {"BUY", "SELL"}:
            raise DailyRiskDataError(f"{symbol}: unknown trade side")
        qty = decimal_value(row.get("qty", row.get("executedQty")), f"{symbol}.trade.qty")
        price = decimal_value(row.get("price"), f"{symbol}.trade.price")
        if qty <= 0 or price <= 0:
            raise DailyRiskDataError(f"{symbol}: trade quantity/price must be positive")
        raw_realized = row.get("realizedPnl")
        realized = None if raw_realized is None else decimal_value(raw_realized, f"{symbol}.trade.realizedPnl")
        normalized.append((stamp, trade_id, Decimal(1) if side == "BUY" else Decimal(-1),
                           qty, price, realized))
    normalized.sort(key=lambda x: (x[0], x[1]))
    with localcontext() as ctx:
        ctx.prec = 34
        for stamp, trade_id, direction, qty, price, realized in reversed(normalized):
            delta = direction * qty
            before = q - delta
            if before == 0:
                before_entry = None
            elif q == 0 or (before > 0) != (q > 0):
                close_qty = min(abs(before), abs(delta))
                if realized is None or close_qty <= 0:
                    raise DailyRiskDataError(f"{symbol}: closing fill lacks realizedPnl")
                signed_close_qty = (Decimal(1) if before > 0 else Decimal(-1)) * close_qty
                before_entry = price - realized / signed_close_qty
            elif (delta > 0) == (q > 0):
                if entry is None or abs(before) <= 0:
                    raise DailyRiskDataError(f"{symbol}: cannot reverse adding fill")
                before_entry = (abs(q) * entry - abs(delta) * price) / abs(before)
            else:
                if entry is None:
                    raise DailyRiskDataError(f"{symbol}: reduced position has no average entry")
                before_entry = entry
            if before != 0 and (before_entry is None or before_entry <= 0):
                raise DailyRiskDataError(f"{symbol}: reversed average entry is invalid")
            q, entry = before, before_entry
    return q, entry

def reconstruct_midnight_equity(account_snapshot, positions, income_rows, trades_by_symbol,
                                mark_prices, *, day_start_ms, snapshot_window_start_ms,
                                snapshot_window_end_ms, account_identity, symbols):
    if not isinstance(account_snapshot, dict) or not isinstance(positions, (list, tuple)):
        raise DailyRiskDataError("Account/position snapshot is malformed")
    if account_snapshot.get("multiAssetsMargin") is not False:
        raise DailyRiskDataError("Model B requires confirmed single-asset USD-M mode")
    wallet_now = decimal_value(account_snapshot.get("totalWalletBalance"), "totalWalletBalance")
    unrealized_now = decimal_value(account_snapshot.get("totalUnrealizedProfit"), "totalUnrealizedProfit")
    current_equity = decimal_value(account_snapshot.get("totalMarginBalance", wallet_now + unrealized_now), "totalMarginBalance")
    if wallet_now < 0 or current_equity < 0 or not account_identity:
        raise DailyRiskDataError("Account equity or identity is invalid")
    income = summarize_income_rows(income_rows, day_start_ms=day_start_ms,
        end_ms=snapshot_window_end_ms, snapshot_window_start_ms=snapshot_window_start_ms,
        snapshot_window_end_ms=snapshot_window_end_ms)
    current_positions = {}
    for index, row in enumerate(positions):
        if not isinstance(row, dict):
            raise DailyRiskDataError(f"Malformed position row {index}")
        symbol = str(row.get("symbol", "")).strip().upper()
        if not symbol or symbol in current_positions:
            raise DailyRiskDataError("Position symbols are missing or duplicated")
        if str(row.get("positionSide", "BOTH")).upper() != "BOTH":
            raise DailyRiskDataError(f"{symbol}: account is not in one-way mode")
        current_positions[symbol] = row
    all_symbols = set(str(x).upper() for x in symbols if str(x).strip())
    all_symbols.update(current_positions)
    all_symbols.update(income["symbols"])
    if not all_symbols or not isinstance(trades_by_symbol, dict) or not isinstance(mark_prices, dict):
        raise DailyRiskDataError("Trade/mark-price evidence is malformed")
    start_unrealized, trade_count, position_rows = Decimal(0), 0, []
    for symbol in sorted(all_symbols):
        position = current_positions.get(symbol, {"positionAmt": "0", "entryPrice": "0"})
        trades = trades_by_symbol.get(symbol)
        if trades is None:
            raise DailyRiskDataError(f"{symbol}: complete userTrades evidence is missing")
        trade_count += len(trades)
        quantity, entry = reverse_position_at_midnight(position.get("positionAmt", "0"),
            position.get("entryPrice", "0"), trades, symbol=symbol, day_start_ms=day_start_ms,
            snapshot_window_start_ms=snapshot_window_start_ms,
            snapshot_window_end_ms=snapshot_window_end_ms)
        mark, upnl = None, Decimal(0)
        if quantity != 0:
            mark = decimal_value(mark_prices.get(symbol), f"{symbol}.midnightMarkPrice")
            if mark <= 0 or entry is None:
                raise DailyRiskDataError(f"{symbol}: midnight mark/entry is unavailable")
            upnl = quantity * (mark - entry)
            start_unrealized += upnl
        position_rows.append({"symbol": symbol, "quantity": str(quantity),
            "entry_price": None if entry is None else str(entry),
            "mark_price": None if mark is None else str(mark), "unrealized_pnl": str(upnl)})
    wallet_start = wallet_now - income["total_income"]
    start_equity = wallet_start + start_unrealized
    if wallet_start < 0 or start_equity <= 0:
        raise DailyRiskDataError("Reconstructed UTC-midnight equity is not positive")
    return {"schema_version": MODEL_VERSION, "utc_day_start_ms": int(day_start_ms),
        "account_fingerprint": str(account_identity),
        "wallet_balance_at_midnight_usdt": str(wallet_start),
        "unrealized_pnl_at_midnight_usdt": str(start_unrealized),
        "equity_at_midnight_usdt": str(start_equity),
        "source": "BINANCE_INCOME_USER_TRADES_MARK_PRICE_1M", "complete": True,
        "observed_window_start_ms": int(snapshot_window_start_ms),
        "observed_window_end_ms": int(snapshot_window_end_ms),
        "income_rows": income["row_count"], "trade_rows": trade_count,
        "positions_at_midnight": position_rows}

def model_b_snapshot_is_valid(snapshot, *, day_start_ms, account_identity):
    if not isinstance(snapshot, dict):
        return False
    try:
        return (int(snapshot.get("schema_version", 0)) == MODEL_VERSION
            and int(snapshot.get("utc_day_start_ms", -1)) == int(day_start_ms)
            and str(snapshot.get("account_fingerprint", "")) == str(account_identity)
            and snapshot.get("complete") is True
            and snapshot.get("source") == "BINANCE_INCOME_USER_TRADES_MARK_PRICE_1M"
            and decimal_value(snapshot.get("wallet_balance_at_midnight_usdt"), "wallet_start") >= 0
            and decimal_value(snapshot.get("unrealized_pnl_at_midnight_usdt"), "upnl_start").is_finite()
            and decimal_value(snapshot.get("equity_at_midnight_usdt"), "equity_start") > 0
            and isinstance(snapshot.get("positions_at_midnight"), list)
            and int(snapshot.get("observed_window_end_ms", -1)) >= int(snapshot.get("observed_window_start_ms", 0))
            and int(snapshot.get("observed_window_end_ms", -1)) < int(day_start_ms) + UTC_DAY_MS)
    except (DailyRiskDataError, TypeError, ValueError, OverflowError):
        return False
