import os
from pathlib import Path
from dotenv import load_dotenv

load_dotenv(Path(__file__).with_name('.env'))

from binance_client import BinanceSpotClient
from portfolio_scanner import PortfolioScanner


def main():
    api_key = os.getenv("BINANCE_API_KEY")
    api_secret = os.getenv("BINANCE_API_SECRET")

    if not api_key or not api_secret:
        raise RuntimeError(
            "BINANCE_API_KEY / BINANCE_API_SECRET не установлены"
        )

    client = BinanceSpotClient(
        api_key=api_key,
        api_secret=api_secret,
        testnet=True,
    )

    account = client.account()

    balances = account.get("balances", [])
    usdt = next(
        (
            item for item in balances
            if item.get("asset") == "USDT"
        ),
        None,
    )

    if usdt is None:
        raise RuntimeError("USDT не найден в Binance account")

    balance = float(usdt.get("free", 0.0))

    if balance <= 0:
        raise RuntimeError(
            f"Некорректный баланс USDT Testnet: {balance}"
        )

    scanner = PortfolioScanner(
        client=client,
        balance_quote=balance,
    )

    print()
    print("========================================")
    print(" WILLIAMS PORTFOLIO SCAN")
    print(" BINANCE SPOT TESTNET")
    print(" BUY/SELL DISABLED")
    print("========================================")
    print(f"Balance: {balance:.2f} USDT")
    print()

    candidates = scanner.scan()

    if not candidates:
        print("Кандидатов, прошедших все фильтры, нет.")
        print("Новый ордер НЕ создаётся.")
        return

    print(f"Найдено кандидатов: {len(candidates)}")
    print()

    for i, item in enumerate(candidates, 1):
        c = item.candidate
        r = item.risk

        print(
            f"{i:02d}. {c.symbol:10s} "
            f"Score={item.final_score:6.2f} "
            f"Risk={r.stop_distance_pct * 100:5.2f}% "
            f"R:R={r.risk_reward:4.2f} "
            f"ATR={c.atr_pct:5.2f}% "
            f"Spread={c.spread_pct * 100:5.3f}% "
            f"HTF={'YES' if c.htf_confirmed else 'NO'}"
        )

        print(
            f"    Entry={r.entry_price:.8f} "
            f"SL={r.stop_price:.8f} "
            f"TP={r.take_profit_price:.8f} "
            f"Position={r.position_quote:.2f} USDT"
        )

    best = scanner.best()

    print()
    print("========================================")
    print(" BEST CANDIDATE")
    print("========================================")

    if best is None:
        print("NONE")
        return

    print(f"Symbol       : {best.candidate.symbol}")
    print(f"Final Score  : {best.final_score:.2f}")
    print(f"Risk         : {best.risk.stop_distance_pct * 100:.2f}%")
    print(f"R:R          : {best.risk.risk_reward:.2f}")
    print(f"Entry        : {best.risk.entry_price:.8f}")
    print(f"Stop Loss    : {best.risk.stop_price:.8f}")
    print(f"Take Profit  : {best.risk.take_profit_price:.8f}")
    print(f"Position     : {best.risk.position_quote:.2f} USDT")

    print()
    print("SAFE MODE: BUY/SELL НЕ ВЫПОЛНЯЛИСЬ")
    print("SCAN COMPLETED")


if __name__ == "__main__":
    main()
