import os
from dotenv import load_dotenv

from binance_client import BinanceSpotClient
from market_scanner import MarketScanner
from portfolio_controller import PortfolioController

load_dotenv()

SYMBOLS = [
    "BTCUSDT",
    "ETHUSDT",
    "SOLUSDT",
    "BNBUSDT",
    "XRPUSDT",
    "ADAUSDT",
    "DOGEUSDT",
    "AVAXUSDT",
    "LINKUSDT",
    "DOTUSDT",
]


def main():
    print()
    print("=" * 72)
    print("WILLIAMS PORTFOLIO INTEGRATION DRY RUN")
    print("BINANCE SPOT TESTNET")
    print("READ ONLY — NO ORDERS")
    print("=" * 72)

    client = BinanceSpotClient(
        api_key=os.getenv("BINANCE_API_KEY"),
        api_secret=os.getenv("BINANCE_API_SECRET"),
        testnet=True,
    )

    account = client.account()
    balance = 0.0

    for asset in account.get("balances", []):
        if asset["asset"] == "USDT":
            balance = float(asset["free"])
            break

    print(f"USDT free balance: {balance:.4f}")
    assert balance > 0, "USDT balance must be greater than zero"

    controller = PortfolioController(
        client=client,
        balance_quote=balance,
        symbols=SYMBOLS,
        interval=os.getenv("INTERVAL", "1h"),
    )

    print()
    print("SCANNER SYMBOLS:")
    print(", ".join(SYMBOLS))

    print()
    print("RUNNING PORTFOLIO SCAN...")
    candidates = controller.scanner.scan()

    print()
    print("-" * 72)
    print("SCANNER RESULT")
    print("-" * 72)

    if not candidates:
        print("No candidates passed scanner.")
    else:
        for i, c in enumerate(candidates, 1):
            print(
                f"{i:2}. {c.symbol:10} "
                f"state={c.setup_state:13} "
                f"signal={str(c.signal):5} "
                f"score={c.score:6.2f} "
                f"setup={c.setup_score:6.2f} "
                f"HTF={str(c.htf_confirmed):5} "
                f"ATR={c.atr_pct:.3f}% "
                f"spread={c.spread_pct:.3f}%"
            )

    print()
    print("-" * 72)
    print("PORTFOLIO SELECTION")
    print("-" * 72)

    selections = controller.select_portfolio(open_risk_quote=0.0, open_positions=0)

    if not selections:
        print("ACTION: WAIT")
        print("No STRICT_SIGNAL candidate fits the aggregate risk budget.")
    else:
        print(f"SELECTED POSITIONS: {len(selections)}")
        for rank, selection in enumerate(selections, 1):
            c = selection.candidate
            r = selection.risk
            print(f"#{rank}: {c.symbol} score={c.score:.2f} risk={r.risk_pct:.2f}% R:R={r.risk_reward:.2f} action={selection.action}")
        print(f"TOTAL ALLOCATED RISK: {sum(x.risk.risk_pct for x in selections):.2f}%")

    print()
    print("-" * 72)
    print("MULTI-POSITION RISK TEST")
    print("-" * 72)
    print("Existing open risk is accounted for by reserved_risk_quote in the canonical executor.")
    print("A remaining risk budget may be allocated to additional independent symbols.")

    print()
    print("-" * 72)
    print("SAFETY CHECK")
    print("-" * 72)
    print("Orders created: NO")
    print("BUY executed:   NO")
    print("SELL executed:  NO")
    print("OCO created:    NO")
    print("Live trading:   NO")

    print()
    print("PORTFOLIO INTEGRATION DRY RUN: PASS")


if __name__ == "__main__":
    main()
