from pathlib import Path
import re

ROOT = Path(".")

FILES = [
    "market_scanner.py",
    "trader.py",
    "server.py",
    "ws_hub.py",
    "db.py",
    "risk_engine.py",
    "strategy.py",
]

print("=" * 100)
print("WILLIAMS SCANNER -> TRADER INTEGRATION AUDIT")
print("READ ONLY AUDIT — NO ORDERS")
print("=" * 100)

errors = []
warnings = []

def read(name):
    p = ROOT / name
    if not p.exists():
        errors.append(f"MISSING FILE: {name}")
        return ""
    return p.read_text()

files = {name: read(name) for name in FILES}

def check(label, condition, warning=False):
    if condition:
        print(f"[PASS] {label}")
    else:
        tag = "WARNING" if warning else "FAIL"
        print(f"[{tag}] {label}")
        (warnings if warning else errors).append(label)

# ---------------------------------------------------------------------
# 1. Scanner
# ---------------------------------------------------------------------

print()
print("=" * 100)
print("1. MARKET SCANNER")
print("=" * 100)

scanner = files["market_scanner.py"]

check(
    "MarketScanner exists",
    "class MarketScanner" in scanner
)

check(
    "scanner has scan()",
    re.search(r"def\s+scan\s*\(", scanner) is not None
)

check(
    "scanner has best()",
    re.search(r"def\s+best\s*\(", scanner) is not None
)

check(
    "scanner has multiple-symbol configuration",
    "SCAN_SYMBOLS" in scanner
)

check(
    "scanner produces Candidate",
    "Candidate(" in scanner
)

check(
    "strict long_signal is preserved",
    "long_signal" in scanner
)

check(
    "scanner does not place BUY orders",
    not re.search(r"\.market_buy\s*\(", scanner)
)

check(
    "scanner does not place SELL orders",
    not re.search(r"\.market_sell\s*\(", scanner)
)

# ---------------------------------------------------------------------
# 2. Trader
# ---------------------------------------------------------------------

print()
print("=" * 100)
print("2. TRADER")
print("=" * 100)

trader = files["trader.py"]

check(
    "Trader exists",
    "class Trader" in trader
)

check(
    "Trader has symbol",
    "self.symbol" in trader
)

check(
    "Trader setup uses exchange info",
    "exchange_info" in trader
)

check(
    "Trader has recover_state()",
    re.search(r"def\s+recover_state\s*\(", trader) is not None
)

check(
    "Trader has process()",
    re.search(r"def\s+process\s*\(", trader) is not None
)

check(
    "Trader has ENTRY_PENDING protection",
    "ENTRY_PENDING" in trader
)

check(
    "Trader has RECONCILE_REQUIRED protection",
    "RECONCILE_REQUIRED" in trader
)

check(
    "Trader has OCO logic",
    "place_oco" in trader
)

check(
    "Trader uses clientOrderId",
    "clientOrderId" in trader or "client_order_id" in trader
)

# ---------------------------------------------------------------------
# 3. Current single-symbol limitation
# ---------------------------------------------------------------------

print()
print("=" * 100)
print("3. SYMBOL SWITCHING / MULTI-PAIR READINESS")
print("=" * 100)

symbol_assignments = re.findall(
    r"self\.symbol\s*=",
    trader
)

print(f"Trader self.symbol assignments: {len(symbol_assignments)}")

check(
    "Trader currently has explicit symbol state",
    len(symbol_assignments) >= 1
)

check(
    "Trader does not blindly recreate symbol from environment during every process cycle",
    trader.count('os.getenv("SYMBOL"') <= 2,
    warning=True,
)

# ---------------------------------------------------------------------
# 4. Recovery
# ---------------------------------------------------------------------

print()
print("=" * 100)
print("4. RECOVERY SAFETY")
print("=" * 100)

check(
    "ENTRY_PENDING recovery exists",
    "ENTRY_PENDING" in trader and "recover_state" in trader
)

check(
    "RECONCILE_REQUIRED state exists",
    "RECONCILE_REQUIRED" in trader
)

check(
    "OCO reconciliation exists",
    "openOrderList" in trader or "open_order_lists" in trader
)

check(
    "foreign balance protection exists",
    "foreign_base_balance" in trader
)

check(
    "hard recovery block exists",
    "RECONCILE_REQUIRED" in trader and "FLAT" in trader
)

# ---------------------------------------------------------------------
# 5. Duplicate signal protection
# ---------------------------------------------------------------------

print()
print("=" * 100)
print("5. DUPLICATE ENTRY PROTECTION")
print("=" * 100)

check(
    "last_signal_candle protection exists",
    "last_signal_candle" in trader
)

check(
    "ENTRY_PENDING exists before/around BUY handling",
    "ENTRY_PENDING" in trader and "market_buy" in trader
)

# ---------------------------------------------------------------------
# 6. Risk
# ---------------------------------------------------------------------

print()
print("=" * 100)
print("6. RISK ENGINE")
print("=" * 100)

risk = files["risk_engine.py"]

check(
    "RiskEngine exists",
    "class RiskEngine" in risk
)

check(
    "position calculation exists",
    "analyse" in risk
)

check(
    "risk-per-trade exists",
    "risk_per_trade_pct" in risk
)

check(
    "position fraction exists",
    "position_fraction" in risk
)

# ---------------------------------------------------------------------
# 7. Server
# ---------------------------------------------------------------------

print()
print("=" * 100)
print("7. SERVER CONTROL")
print("=" * 100)

server = files["server.py"]

for endpoint in (
    "/api/v1/control/start",
    "/api/v1/control/stop",
    "/api/v1/control/pause",
    "/api/v1/control/resume",
    "/api/v1/control/recover",
):
    check(
        f"endpoint present: {endpoint}",
        endpoint.split("/")[-1] in server
    )

check(
    "server creates Trader",
    "Trader(" in server
)

# ---------------------------------------------------------------------
# 8. WebSocket
# ---------------------------------------------------------------------

print()
print("=" * 100)
print("8. WEBSOCKET")
print("=" * 100)

ws = files["ws_hub.py"]

check(
    "WebSocket Hub exists",
    "class WebSocketHub" in ws or "class WSHub" in ws
)

check(
    "market websocket exists",
    "_market_loop" in ws
)

check(
    "user websocket exists",
    "_user_loop" in ws
)

check(
    "subscription state exists",
    "user_subscription_id" in ws
)

# ---------------------------------------------------------------------
# 9. Database / global trade state
# ---------------------------------------------------------------------

print()
print("=" * 100)
print("9. DATABASE / POSITION STATE")
print("=" * 100)

db = files["db.py"]

check(
    "database module exists",
    bool(db)
)

check(
    "open trade state exists",
    "open_trade" in db
)

check(
    "trade symbol is stored",
    "symbol" in db
)

# ---------------------------------------------------------------------
# 10. Important architecture warnings
# ---------------------------------------------------------------------

print()
print("=" * 100)
print("10. MULTI-PAIR ARCHITECTURE CHECK")
print("=" * 100)

# Per-symbol foreign-balance protection.
per_symbol_baseline = (
    "foreign_base_balance:{self.symbol}" in trader
    and "foreign_base_balance:{self.symbol}" in trader.replace(" ", "")
)

legacy_migration_guard = (
    "active_symbol" in trader
    and "str(active_symbol).upper() == self.symbol" in trader
)

check(
    "per-symbol foreign balance baseline exists",
    per_symbol_baseline
)

check(
    "legacy foreign balance migration is active-symbol guarded",
    legacy_migration_guard
)

# Global open-trade protection is required because the bot allows only
# one managed position at a time across the whole scanner universe.
global_open_trade_guard = (
    "any_open_trade = self.db.open_trade()" in trader
    and "Cannot switch symbol while a managed position exists" in trader
)

check(
    "global open-trade guard exists",
    global_open_trade_guard
)

# Controlled symbol switching must be performed through switch_symbol()
# rather than treating self.symbol as a permanently fixed pair.
controlled_switching = (
    "def switch_symbol(self, symbol)" in trader
    and "self.switch_symbol(selected_symbol)" in trader
    and "self.db.state_set('active_symbol', new_symbol)" in trader
)

check(
    "controlled scanner symbol switching exists",
    controlled_switching
)

if (
    per_symbol_baseline
    and legacy_migration_guard
    and global_open_trade_guard
    and controlled_switching
):
    print("[PASS] multi-pair architecture safety checks")

# ---------------------------------------------------------------------
# 11. Dangerous live order paths
# ---------------------------------------------------------------------

print()
print("=" * 100)
print("11. ORDER PATHS")
print("=" * 100)

buy_count = len(re.findall(r"\bmarket_buy\s*\(", trader))
oco_count = len(re.findall(r"\bplace_oco\s*\(", trader))

print(f"market_buy references: {buy_count}")
print(f"place_oco references: {oco_count}")

check(
    "BUY path is confined to Trader",
    buy_count >= 1
)

check(
    "OCO path exists",
    oco_count >= 1
)

# ---------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------

print()
print("=" * 100)
print("AUDIT SUMMARY")
print("=" * 100)

print(f"Hard failures: {len(errors)}")
print(f"Warnings:      {len(warnings)}")

if errors:
    print()
    print("FAILURES:")
    for x in errors:
        print(" -", x)

if warnings:
    print()
    print("WARNINGS:")
    for x in warnings:
        print(" -", x)

print()
if errors:
    print("AUDIT RESULT: NEEDS FIXES")
else:
    print("AUDIT RESULT: STRUCTURE OK — INTEGRATION WORK REQUIRED")

print()
print("NO ORDERS CREATED")
print("NO BUY EXECUTED")
print("NO SELL EXECUTED")
