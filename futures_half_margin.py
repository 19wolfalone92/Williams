"""Account-wide USDT margin envelope. No exchange or strategy authority here.

The 50% source rule is implemented with conservative engineering estimates:
min(wallet, equity), isolated collateral, residual orders and .35% cost reserve.
Two matching exposure observations are required; they are not an atomic API.
Unknown/triggering liabilities block NEW exposure, never protective management.
"""
from __future__ import annotations

from decimal import Decimal, InvalidOperation


class HalfMarginUnknown(ValueError):
    pass


class ExposureNotSubmittedError(RuntimeError):
    """A local final transport fence failed before any exposure POST."""


def _require(condition, detail):
    if not condition:
        raise HalfMarginUnknown("FUTURES_HALF_MARGIN_UNKNOWN:" + detail)


def _number(value, *, signed=False):
    _require(type(value) in (str, int, float, Decimal), "NUMBER")
    try:
        result = Decimal(str(value))
    except InvalidOperation:
        raise HalfMarginUnknown("FUTURES_HALF_MARGIN_UNKNOWN:NUMBER") from None
    _require(result.is_finite() and (signed or result >= 0), "NUMBER")
    return result


def _rows(value):
    _require(type(value) is list and all(type(x) is dict for x in value), "ROWS")
    return value


def _flag(row, field):
    value = row.get(field)
    _require(type(value) is bool or value in ("true", "false"), "BOOLEAN:" + field)
    return value is True or value == "true"


def _symbol(value):
    _require(type(value) is str and value.endswith("USDT") and value.isalnum(), "USDT_SYMBOL")
    return value


def _leverage(row):
    value = _number(row.get("leverage"))
    _require(1 <= value <= 125 and value == int(value), "LEVERAGE")
    _require(row.get("marginType") in ("ISOLATED", "CROSSED"), "MARGIN_MODE")
    return value


def margin_observation(account, configuration, orders, symbols):
    """Parse one complete, signed, account-wide observation (no allowlist filter).

    symbols maps each exposure-order symbol to signed symbolConfig + mark_price.
    Missing reduceOnly/closePosition is UNKNOWN, not a reduction by default.
    """
    _require(type(account) is dict and type(configuration) is dict and type(symbols) is dict, "OBJECT")
    _require(configuration.get("canTrade") is True and configuration.get("dualSidePosition") is False
             and configuration.get("multiAssetsMargin") is False, "ACCOUNT_MODE")
    for field in ("canTrade", "dualSidePosition", "multiAssetsMargin"):
        if field in account:
            _require(type(account[field]) is bool and account[field] is configuration[field], "ACCOUNT_MODE_CONFLICT")
    wallet = _number(account.get("totalWalletBalance"))
    pnl = _number(account.get("totalUnrealizedProfit"), signed=True)
    equity = _number(account.get("totalMarginBalance"))
    available = _number(account.get("availableBalance"))
    _require(wallet > 0 and equity > 0 and abs(wallet + pnl - equity) <= Decimal("0.00000001"), "EQUITY")
    root_initial = _number(account.get("totalInitialMargin"))
    root_positions = _number(account.get("totalPositionInitialMargin"))
    root_orders = _number(account.get("totalOpenOrderInitialMargin"))
    assets = _rows(account.get("assets"))
    _require(len({x.get("asset") for x in assets}) == len(assets), "DUPLICATE_ASSET")
    usdt = [x for x in assets if x.get("asset") == "USDT"]
    _require(len(usdt) == 1, "USDT_ASSET")
    for row in assets:
        # Zero foreign wallets are allowed; foreign collateral/exposure is not.
        if row.get("asset") != "USDT":
            for key in ("walletBalance", "unrealizedProfit", "initialMargin"):
                _require(_number(row.get(key), signed=True) == 0, "FOREIGN_ASSET")
    asset = usdt[0]
    _require(abs(_number(asset.get("walletBalance")) - wallet) <= Decimal("0.00000001"), "USDT_WALLET")
    asset_initial = _number(asset.get("initialMargin"))
    asset_positions = _number(asset.get("positionInitialMargin"))
    asset_orders = _number(asset.get("openOrderInitialMargin"))
    position_initial = Decimal(0)
    isolated_extra = Decimal(0)
    position_identity = []
    live = {}
    for row in _rows(account.get("positions")):
        symbol = _symbol(row.get("symbol"))
        _require(symbol not in live and row.get("positionSide") == "BOTH", "POSITION_IDENTITY")
        amount = _number(row.get("positionAmt"), signed=True)
        initial = _number(row.get("initialMargin"))
        isolated_wallet = _number(row.get("isolatedWallet"))
        isolated_margin = _number(row.get("isolatedMargin"))
        _require(amount == 0 or initial > 0 or isolated_wallet > 0, "POSITION_MARGIN")
        position_initial += initial
        isolated_extra += max(Decimal(0), max(isolated_wallet, isolated_margin) - initial)
        live[symbol] = amount
        position_identity.append((symbol, amount, isolated_wallet, row.get("updateTime")))
    position_margin = max(root_positions, asset_positions, position_initial) + isolated_extra
    pending = Decimal(0)
    cost_notional = Decimal(0)
    order_identity = []
    by_client = {}
    seen_ids = set()
    for row in _rows(orders):
        symbol = _symbol(row.get("symbol"))
        _require(row.get("positionSide") == "BOTH" and row.get("side") in ("BUY", "SELL"), "ORDER_SIDE")
        algo = "algoId" in row
        identifier = str(row.get("algoId" if algo else "orderId", ""))
        client = row.get("clientAlgoId" if algo else "clientOrderId")
        _require(identifier not in ("", "0", "None") and type(client) is str and bool(client), "ORDER_IDENTITY")
        identity = ("ALGO" if algo else "ORDER", symbol, identifier)
        _require(identity not in seen_ids and client not in by_client, "DUPLICATE_ORDER")
        seen_ids.add(identity)
        reduce = _flag(row, "reduceOnly")
        close = _flag(row, "closePosition") if algo else False
        status = row.get("algoStatus" if algo else "status")
        _require(status in (("NEW",) if algo else ("NEW", "PARTIALLY_FILLED")), "ORDER_TRANSITION")
        if algo:
            _require(str(row.get("actualOrderId", "")) in ("", "0"), "ALGO_CHILD_UNRESOLVED")
        if reduce or close:
            # A signed BOTH reduceOnly/closePosition cannot increase exposure.
            order_identity.append((identity, client, row.get("side"), status, "REDUCTION"))
            by_client[client] = {"reduction": True}
            continue
        quantity = _number(row.get("quantity" if algo else "origQty"))
        executed = Decimal(0) if algo else _number(row.get("executedQty"))
        _require(quantity > 0 and 0 <= executed < quantity, "ORDER_QUANTITY")
        configuration_row = symbols.get(symbol)
        _require(type(configuration_row) is dict and configuration_row.get("symbol") == symbol, "SYMBOL_CONFIGURATION")
        leverage = _leverage(configuration_row)
        mark = _number(configuration_row.get("mark_price"))
        price = max(_number(row.get("triggerPrice" if algo else "price")), mark)
        _require(mark > 0 and price > 0, "ORDER_PRICE")
        notional = (quantity - executed) * price * Decimal("1.0015")
        pending += notional / leverage
        cost_notional += notional
        by_client[client] = {"symbol": symbol, "side": row["side"], "quantity": quantity,
                             "executed": executed, "order_id": identifier, "algo": algo, "reduction": False}
        order_identity.append((identity, client, row["side"], status, quantity, executed,
                               _number(row.get("triggerPrice" if algo else "price")), leverage,
                               configuration_row["marginType"]))
    confirmed_orders = max(root_orders, asset_orders)
    confirmed = max(root_initial, asset_initial,
                    position_margin + confirmed_orders)
    # The entire open-order bucket is represented once. Its independent envelope
    # includes ordinary AND conditional residuals; never add it again to root.
    used = max(root_initial, asset_initial,
               position_margin + max(confirmed_orders, pending))
    return {"base": min(wallet, equity), "available": available, "confirmed": confirmed,
            "used": used, "cost_notional": cost_notional, "orders": by_client, "live": live,
            "identity": (wallet, tuple(sorted(position_identity)), tuple(sorted(order_identity))),
            "symbols": symbols}


def assess_half_margin(first, second, proposal, liabilities=(), *, elapsed_ms=0):
    """Bound final NEW risk. Pending liabilities require exact current readback.

    A fully filled durable intent is represented by the account position bucket.
    Unknown POST/cancel/triggering must be reconciled before new admission.
    """
    _require(0 <= elapsed_ms <= 15_000, "STALE")
    _require(first["identity"] == second["identity"], "READS_CHANGED")
    symbol = _symbol(proposal.get("symbol"))
    client = proposal.get("client_id")
    _require(type(client) is str and bool(client), "PROPOSAL_IDENTITY")
    _require(client not in first["orders"] and client not in second["orders"], "PROPOSAL_ALREADY_PRESENT")
    _require(proposal.get("side") in ("BUY", "SELL"), "PROPOSAL_SIDE")
    quantity = _number(proposal.get("quantity"))
    price = _number(proposal.get("price"))
    expected_leverage = _number(proposal.get("leverage"))
    _require(quantity > 0 and price > 0, "PROPOSAL_QUANTITY_PRICE")
    prices = [price]
    configurations = []
    for observation in (first, second):
        config = observation["symbols"].get(symbol)
        _require(type(config) is dict and config.get("symbol") == symbol, "PROPOSAL_CONFIGURATION")
        leverage = _leverage(config)
        _require(config["marginType"] == "ISOLATED" and leverage == expected_leverage, "PROPOSAL_LEVERAGE_CHANGED")
        mark = _number(config.get("mark_price"))
        _require(mark > 0, "PROPOSAL_MARK")
        prices.append(mark)
        configurations.append((leverage, config["marginType"]))
    _require(configurations[0] == configurations[1], "LEVERAGE_CHANGED")
    seen = set()
    claimed_orders = set()
    for liability in liabilities:
        cid = liability.get("client_id")
        _require(type(cid) is str and cid and cid not in seen, "LIABILITY_IDENTITY")
        seen.add(cid)
        if cid == client:
            # Only the current write-before-network intent may be excluded.
            _require(liability.get("current_proposal") is True, "LIABILITY_PROPOSAL")
            continue
        status = liability.get("status")
        _require(status not in ("SUBMISSION_UNKNOWN", "AMBIGUOUS", "TRIGGERING", "TRIGGERED",
                                 "CANCEL_SUBMISSION_UNKNOWN", "RECONCILE_REQUIRED", ""), "LIABILITY_UNRESOLVED")
        if status == "FILLED":
            # No residual is released by this function; reconciliation owns fills.
            _require(liability.get("fill_confirmed") is True, "FILL_UNPROVEN")
            continue
        if status in ("CANCELED", "EXPIRED", "EXPIRED_IN_MATCH", "REJECTED"):
            _require(liability.get("terminal_confirmed") is True, "TERMINAL_UNPROVEN")
            continue
        _require(status in ("NEW", "PARTIALLY_FILLED"), "LIABILITY_STATUS")
        for index, observation in enumerate((first, second)):
            actual = observation["orders"].get(cid)
            if actual is None and liability.get("actual_order_id"):
                children = [x for x in observation["orders"].values() if not x["reduction"]
                            and not x["algo"] and x["order_id"] == str(liability["actual_order_id"])
                            and x["symbol"] == liability.get("symbol")]
                _require(len(children) == 1, "LIABILITY_CHILD_UNPROVEN")
                actual = children[0]
            _require(actual is not None and not actual["reduction"], "LIABILITY_MISSING")
            if index == 0:
                key = (actual["symbol"], actual["algo"], actual["order_id"])
                _require(key not in claimed_orders, "LIABILITY_DUPLICATE_CLAIM")
                claimed_orders.add(key)
            _require(actual["symbol"] == liability.get("symbol") and actual["side"] == liability.get("side")
                     and actual["quantity"] == _number(liability.get("quantity")), "LIABILITY_MISMATCH")
            if "executed" in liability:
                _require(actual["executed"] == _number(liability["executed"]), "LIABILITY_FILL_CHANGED")
    notional = quantity * max(prices) * Decimal("1.0015")
    new_margin = notional / expected_leverage
    costs = (max(first["cost_notional"], second["cost_notional"]) + notional) * Decimal("0.0035")
    used = max(first["used"], second["used"])
    confirmed = max(first["confirmed"], second["confirmed"])
    limit = min(first["base"], second["base"]) / 2
    projected = used + new_margin + costs
    available = min(first["available"], second["available"])
    allowed = projected <= limit and new_margin + costs <= available * Decimal("0.95")
    return {"schema": "WILLIAMS_HALF_MARGIN_V1", "allowed": allowed,
            "blocker": "" if allowed else "FUTURES_HALF_MARGIN_EXCEEDED",
            "limit_usdt": str(limit), "confirmed_usdt": str(confirmed),
            "residual_usdt": str(max(Decimal(0), used - confirmed)),
            "new_margin_usdt": str(new_margin), "cost_buffer_usdt": str(costs),
            "projected_usdt": str(projected), "elapsed_ms": elapsed_ms}
