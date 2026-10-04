from pathlib import Path
import os
from dotenv import load_dotenv

load_dotenv(Path(".env"))

from binance_client import BinanceSpotClient
from market_scanner import MarketScanner
from risk_engine import RiskEngine


def pct(v):
    return f"{float(v) * 100:.3f}%"


def floor_step(value, step):
    if step <= 0:
        return value
    return int(value / step) * step


api_key = os.getenv("BINANCE_API_KEY")
api_secret = os.getenv("BINANCE_API_SECRET")

if not api_key or not api_secret:
    raise RuntimeError(
        "BINANCE_API_KEY/BINANCE_API_SECRET not found in .env"
    )


print("=" * 120)
print("WILLIAMS SCANNER + RISK ENGINE DRY RUN")
print("BINANCE SPOT TESTNET")
print("READ ONLY — NO ORDERS")
print("=" * 120)

# Credentials are used ONLY for signed account read.
client = BinanceSpotClient(
    api_key=api_key,
    api_secret=api_secret,
    testnet=True,
)

scanner = MarketScanner(client)

account = client.account()

balance = next(
    (
        float(b["free"])
        for b in account.get("balances", [])
        if b.get("asset") == "USDT"
    ),
    0.0,
)

print()
print(f"USDT free balance: {balance:.4f}")

risk = RiskEngine(balance_quote=balance)

print("RiskEngine: initialized")

print()
print("Configured symbols:")
print(", ".join(scanner.symbols))

print()
print("Scanning...")
print()

candidates = scanner.scan()

if not candidates:
    print("NO CANDIDATES")
    print()
    print("No order calculation performed.")
    print()
    print("SAFETY: NO ORDERS")
    raise SystemExit(0)


for rank, candidate in enumerate(candidates, 1):

    print()
    print("=" * 120)
    print(f"CANDIDATE #{rank}: {candidate.symbol}")
    print("=" * 120)

    print(f"State:             {candidate.setup_state}")
    print(f"Strict signal:     {'YES' if candidate.signal else 'NO'}")
    print(f"Score:             {candidate.score:.2f}")
    print(f"Setup score:       {candidate.setup_score:.2f}")
    print(f"Signal strength:   {candidate.signal_strength:.2f}")
    print(f"Breakout distance: {candidate.breakout_distance_pct:.3f}%")
    print(f"Risk:              {candidate.risk_pct:.2f}%")
    print(f"R:R:               {candidate.risk_reward:.2f}")
    print(f"ATR:               {pct(candidate.atr_pct)}")
    print(f"Spread:             {pct(candidate.spread_pct)}")
    print(f"HTF confirmed:     {'YES' if candidate.htf_confirmed else 'NO'}")
    print(f"Reason:             {candidate.reason}")

    print()
    print("SCANNER FILTERS")

    if candidate.setup_state not in {"STRONG_SIGNAL", "SETUP_READY"}:
        print("  Setup:             BLOCK")
        print("  Reason:            setup not ready")
        continue

    print("  Setup:             PASS")

    if candidate.atr_pct > scanner.max_atr_pct:
        print("  ATR:               BLOCK")
        print("  Reason:            ATR above maximum")
        continue

    print("  ATR:               PASS")

    if candidate.spread_pct > scanner.max_spread_pct:
        print("  Spread:            BLOCK")
        print("  Reason:            spread above maximum")
        continue

    print("  Spread:            PASS")

    if candidate.risk_reward < scanner.min_risk_reward:
        print("  R:R:               BLOCK")
        print("  Reason:            R:R below minimum")
        continue

    print("  R:R:               PASS")

    if candidate.signal and scanner.require_htf_confirmation:
        if not candidate.htf_confirmed:
            print("  HTF:               BLOCK")
            print("  Reason:            strict signal without HTF confirmation")
            continue

    print("  HTF:               PASS")

    if not candidate.signal:
        print()
        print("  FINAL:             WAITING_FOR_BREAKOUT")
        continue

    print()
    print("  FINAL SCANNER:     ELIGIBLE")

    # =========================================================
    # BINANCE FILTERS
    # =========================================================

    print()
    print("BINANCE EXCHANGE FILTERS")

    try:
        info = client.exchange_info(candidate.symbol)
        symbols = info.get("symbols", [])

        if not symbols:
            print("  Exchange info:     BLOCK")
            print("  Reason:            symbol not found")
            continue

        symbol_info = symbols[0]
        status = symbol_info.get("status")

        print(f"  Status:            {status}")

        if status != "TRADING":
            print("  FINAL:             BLOCKED")
            print("  Reason:            symbol is not TRADING")
            continue

        print("  Status:            PASS")

        filters = {
            f["filterType"]: f
            for f in symbol_info.get("filters", [])
        }

        price_filter = filters.get("PRICE_FILTER", {})

        lot_filter = (
            filters.get("LOT_SIZE")
            or filters.get("MARKET_LOT_SIZE")
            or {}
        )

        notional_filter = (
            filters.get("NOTIONAL")
            or filters.get("MIN_NOTIONAL")
            or {}
        )

        tick_size = float(
            price_filter.get("tickSize", "0.01")
        )

        min_qty = float(
            lot_filter.get("minQty", "0")
        )

        step_size = float(
            lot_filter.get("stepSize", "0.000001")
        )

        min_notional = float(
            notional_filter.get("minNotional", "0")
        )

        print(f"  Tick size:         {tick_size}")
        print(f"  Min quantity:      {min_qty}")
        print(f"  Step size:         {step_size}")
        print(f"  Min notional:      {min_notional}")

        # =====================================================
        # MARKET
        # =====================================================

        ticker = client.ticker_price(candidate.symbol)
        entry_price = float(ticker["price"])

        print()
        print("MARKET")
        print(f"  Entry price:       {entry_price:.8f}")

        # =====================================================
        # RISK ENGINE
        # =====================================================

        print()
        print("RISK ENGINE")

        try:
            result = risk.calculate_position(
                entry_price=entry_price,
                stop_pct=0.02,
                target_pct=0.04,
                risk_per_trade_pct=0.01,
                position_fraction=0.25,
            )
        except Exception as e:
            print(
                f"  RiskEngine error: "
                f"{type(e).__name__}: {e}"
            )
            continue

        print(f"  Result type:       {type(result).__name__}")

        if isinstance(result, dict):

            for key, value in result.items():
                print(f"  {key:<20} {value}")

            quantity = float(
                result.get("quantity")
                or result.get("qty")
                or result.get("position_qty")
                or 0
            )

            stop_price = float(
                result.get("stop_price")
                or result.get("stop")
                or result.get("sl")
                or entry_price * 0.98
            )

            target_price = float(
                result.get("target_price")
                or result.get("take_profit")
                or result.get("tp")
                or entry_price * 1.04
            )

        else:
            print(f"  Result:             {result}")
            quantity = 0.0
            stop_price = entry_price * 0.98
            target_price = entry_price * 1.04

        # =====================================================
        # NORMALIZATION
        # =====================================================

        normalized_qty = floor_step(
            quantity,
            step_size,
        )

        normalized_notional = (
            normalized_qty * entry_price
        )

        print()
        print("NORMALIZED ORDER")

        print(f"  Entry:              {entry_price:.8f}")
        print(f"  Stop:               {stop_price:.8f}")
        print(f"  Take profit:        {target_price:.8f}")
        print(f"  Raw quantity:       {quantity:.12f}")
        print(f"  Normalized qty:     {normalized_qty:.12f}")
        print(f"  Notional:           {normalized_notional:.4f}")

        # =====================================================
        # BINANCE VALIDATION
        # =====================================================

        print()
        print("BINANCE VALIDATION")

        valid = True

        if normalized_qty < min_qty:
            print("  LOT_SIZE:           BLOCK")
            valid = False
        else:
            print("  LOT_SIZE:           PASS")

        if min_notional > 0:
            if normalized_notional < min_notional:
                print("  NOTIONAL:           BLOCK")
                valid = False
            else:
                print("  NOTIONAL:           PASS")
        else:
            print("  NOTIONAL:           PASS")

        if normalized_qty <= 0:
            print("  QUANTITY:           BLOCK")
            valid = False
        else:
            print("  QUANTITY:           PASS")

        if not valid:
            print()
            print("  FINAL:              BLOCKED_BY_BINANCE_FILTERS")
            continue

        # =====================================================
        # FINAL DRY RUN
        # =====================================================

        print()
        print("=" * 120)
        print("FINAL DECISION")
        print("=" * 120)

        print(f"  Symbol:             {candidate.symbol}")
        print(f"  Score:              {candidate.score:.2f}")
        print(f"  Strict signal:      {'YES' if candidate.signal else 'NO'}")
        print(f"  Entry:              {entry_price:.8f}")
        print(f"  Stop:               {stop_price:.8f}")
        print(f"  Take profit:        {target_price:.8f}")
        print(f"  Quantity:           {normalized_qty:.12f}")
        print(f"  Notional:           {normalized_notional:.4f}")

        if candidate.signal:
            print()
            print("  FINAL:              ELIGIBLE_FOR_ENTRY")
        else:
            print()
            print("  FINAL:              WAITING_FOR_BREAKOUT")

        print()
        print("  DRY RUN ONLY")
        print("  BUY CREATED:        NO")
        print("  SELL CREATED:       NO")

    except Exception as e:

        print()
        print("  SIMULATION ERROR")
        print(
            f"  {type(e).__name__}: {e}"
        )


print()
print("=" * 120)
print("SAFETY CHECK")
print("=" * 120)
print("Orders created:      NO")
print("BUY executed:       NO")
print("SELL executed:      NO")
print("Live trading:       NO")
print("Simulation complete.")
print("=" * 120)
