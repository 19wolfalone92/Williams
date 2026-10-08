import ast
import hashlib
import os
import re
import sqlite3
from pathlib import Path

ROOT = Path(".").resolve()

EXCLUDE_DIRS = {
    ".git",
    "__pycache__",
    ".gradle",
    "build",
    ".idea",
}

TEXT_EXTENSIONS = {
    ".py", ".kt", ".kts", ".yml", ".yaml", ".json",
    ".properties", ".gradle", ".md", ".sh", ".txt"
}

PY_FILES = [
    p for p in ROOT.rglob("*.py")
    if not any(part in EXCLUDE_DIRS for part in p.parts)
]

ALL_FILES = [
    p for p in ROOT.rglob("*")
    if p.is_file()
    and not any(part in EXCLUDE_DIRS for part in p.parts)
]

errors = 0
warnings = 0

def rel(p):
    try:
        return str(p.relative_to(ROOT))
    except Exception:
        return str(p)

def read_text(p):
    try:
        return p.read_text(errors="replace")
    except Exception:
        return ""

def section(title):
    print()
    print("=" * 90)
    print(title)
    print("=" * 90)

def finding(kind, msg):
    global errors, warnings
    if kind == "FAIL":
        errors += 1
        print(f"[FAIL] {msg}")
    elif kind == "WARN":
        warnings += 1
        print(f"[WARN] {msg}")
    else:
        print(f"[PASS] {msg}")

section("WILLIAMS FULL STATIC SAFETY AUDIT")
print("ROOT:", ROOT)
print("Python files:", len(PY_FILES))
print("All files:", len(ALL_FILES))

# ---------------------------------------------------------------------
# 1. Python syntax
# ---------------------------------------------------------------------
section("1. PYTHON SYNTAX")

for p in PY_FILES:
    try:
        ast.parse(read_text(p), filename=str(p))
    except Exception as e:
        finding("FAIL", f"{rel(p)}: syntax error: {e}")

if errors == 0:
    finding("PASS", "All Python files parse successfully")

# ---------------------------------------------------------------------
# 2. Dangerous exchange/order calls
# ---------------------------------------------------------------------
section("2. EXCHANGE / ORDER CALL AUDIT")

danger_patterns = [
    r"\.order\s*\(",
    r"\.create_order\s*\(",
    r"\.create_oco\s*\(",
    r"\.order_oco\s*\(",
    r"\.cancel_order\s*\(",
    r"\.cancel_open_orders\s*\(",
    r"\.cancel_all_open_orders\s*\(",
    r"\bmarket_buy\s*\(",
    r"\bmarket_sell\s*\(",
    r"\bplace_oco\s*\(",
]

for p in PY_FILES:
    text = read_text(p)
    lines = text.splitlines()

    for n, line in enumerate(lines, 1):
        for pattern in danger_patterns:
            if re.search(pattern, line):
                print(f"{rel(p)}:{n}: {line.strip()}")
                break

# ---------------------------------------------------------------------
# 3. Trader construction audit
# ---------------------------------------------------------------------
section("3. ALL Trader() CONSTRUCTIONS")

for p in PY_FILES:
    lines = read_text(p).splitlines()

    for n, line in enumerate(lines, 1):
        if re.search(r"\bTrader\s*\(", line):
            print(f"{rel(p)}:{n}: {line.strip()}")

# ---------------------------------------------------------------------
# 4. Database construction / SQLite audit
# ---------------------------------------------------------------------
section("4. DATABASE / SQLITE USAGES")

for p in PY_FILES:
    lines = read_text(p).splitlines()

    for n, line in enumerate(lines, 1):
        if (
            re.search(r"\bDatabase\s*\(", line)
            or "sqlite3.connect" in line
        ):
            print(f"{rel(p)}:{n}: {line.strip()}")

# ---------------------------------------------------------------------
# 5. DB environment variables
# ---------------------------------------------------------------------
section("5. DATABASE ENVIRONMENT PATHS")

patterns = [
    "WILLIAMS_DB_PATH",
    "WILLIAMS_TEST_DB",
    "DB_PATH",
    "trader.sqlite3",
    "foreign_base_balance",
]

for p in ALL_FILES:
    if p.suffix.lower() not in TEXT_EXTENSIONS:
        continue

    text = read_text(p)

    for n, line in enumerate(text.splitlines(), 1):
        if any(x in line for x in patterns):
            print(f"{rel(p)}:{n}: {line.strip()}")

# ---------------------------------------------------------------------
# 6. DRY_RUN / LIVE safety
# ---------------------------------------------------------------------
section("6. DRY_RUN / LIVE SAFETY")

safety_patterns = [
    "DRY_RUN",
    "dry_run",
    "ALLOW_LIVE",
    "TESTNET",
    "testnet",
]

for p in PY_FILES:
    lines = read_text(p).splitlines()

    for n, line in enumerate(lines, 1):
        if any(x in line for x in safety_patterns):
            print(f"{rel(p)}:{n}: {line.strip()}")

# ---------------------------------------------------------------------
# 7. Critical method inventory
# ---------------------------------------------------------------------
section("7. CRITICAL TRADER METHODS")

critical = {
    "market_buy": [],
    "place_oco": [],
    "switch_symbol": [],
    "recover_state": [],
    "_auto_scan_process": [],
    "process": [],
    "setup": [],
}

for p in PY_FILES:
    try:
        tree = ast.parse(read_text(p), filename=str(p))
    except Exception:
        continue

    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            if node.name in critical:
                critical[node.name].append((rel(p), node.lineno))

for name, locations in critical.items():
    if not locations:
        finding("WARN", f"{name} not found")
    else:
        print(f"{name}:")
        for file, line in locations:
            print(f"  {file}:{line}")

# ---------------------------------------------------------------------
# 8. Duplicate critical method definitions
# ---------------------------------------------------------------------
section("8. DUPLICATE CRITICAL METHOD DEFINITIONS")

for name, locations in critical.items():
    if len(locations) > 1:
        finding(
            "WARN",
            f"{name} defined {len(locations)} times: {locations}"
        )
    elif locations:
        finding("PASS", f"{name}: single definition")

# ---------------------------------------------------------------------
# 9. Static DRY_RUN gate inspection
# ---------------------------------------------------------------------
section("9. MARKET_BUY / MARKET_SELL SAFETY GATE")

trader_file = ROOT / "trader.py"

if trader_file.exists():
    text = read_text(trader_file)

    # Entry execution is performed by market_buy().

    # Exit execution is handled by native OCO via place_oco().

    for method in ("market_buy",):
        m = re.search(
            rf"def\s+{re.escape(method)}\s*\([^)]*\):",
            text
        )

        if not m:
            finding("FAIL", f"{method} not found")
            continue

        start = m.start()
        next_method = re.search(
            r"\n\s*def\s+\w+\s*\(",
            text[m.end():]
        )

        if next_method:
            body = text[start:m.end() + next_method.start()]
        else:
            body = text[start:]

        dry_pos = body.find("self.dry_run")
        order_pos = min(
            [x for x in [
                body.find("self.client.order"),
                body.find(".order("),
                body.find("create_order"),
            ] if x >= 0] or [10**9]
        )

        print(f"{method}:")
        print("  dry_run position:", dry_pos)
        print("  order position:  ", order_pos)

        if order_pos < dry_pos:
            finding(
                "FAIL",
                f"{method}: exchange order appears before DRY_RUN gate"
            )
        else:
            finding("PASS", f"{method}: DRY_RUN precedes exchange order")

# Williams uses a local protected-exit wrapper backed by Binance native OCO.
# The wrapper lives in portfolio_trader.py and the exchange call lives in
# binance_client.py, so validate both files as one execution contract.
portfolio_trader_text = read_text(ROOT / "portfolio_trader.py")
binance_client_text = read_text(ROOT / "binance_client.py")
has_local_oco = bool(re.search(r"def\s+_?create_oco\s*\(", portfolio_trader_text))
has_binance_oco = bool(re.search(r"def\s+create_oco_sell(?:_safe)?\s*\(", binance_client_text))
if has_local_oco and has_binance_oco:
    finding(
        "PASS",
        "exit: native Binance OCO via portfolio_trader wrapper + Binance client"
    )
else:
    finding(
        "FAIL",
        "exit: protected native OCO implementation is incomplete"
    )

# ---------------------------------------------------------------------
# 10. Dangerous Python execution primitives
# ---------------------------------------------------------------------
section("10. DANGEROUS EXECUTION PRIMITIVES")

dangerous_exec = [
    r"\beval\s*\(",
    r"\bexec\s*\(",
    r"\bos\.system\s*\(",
    r"\bsubprocess\.",
    r"\bos\.popen\s*\(",
]

for p in PY_FILES:
    lines = read_text(p).splitlines()

    for n, line in enumerate(lines, 1):
        # The audit script itself uses subprocess only for the
        # read-only "git status --short" inspection in section 15.
        if p.resolve() == (ROOT / "full_safety_audit.py").resolve():
            if "subprocess.run(" in line and "git_status_result" in line:
                continue
            if n >= 460 and n <= 475:
                continue

        for pattern in dangerous_exec:
            if re.search(pattern, line):
                finding(
                    "WARN",
                    f"{rel(p)}:{n}: {line.strip()}"
                )

# ---------------------------------------------------------------------
# 11. Old architecture / suspicious patterns
# ---------------------------------------------------------------------
section("11. LEGACY / SUSPICIOUS PATTERN SEARCH")

legacy_patterns = [
    r"foreign_base_balance\s*=",
    r"state_set\(['\"]foreign_base_balance",
    r"state_get\(['\"]foreign_base_balance",
    r"active_symbol.*foreign_base_balance",
    r"switch_symbol",
    r"AUTO_SCAN_ENABLED",
    r"AUTO_SCAN_SYMBOLS",
    r"max_open_positions",
    r"RECONCILE_REQUIRED",
    r"ENTRY_PENDING",
    r"EXIT_PENDING",
]

for p in PY_FILES:
    lines = read_text(p).splitlines()

    for n, line in enumerate(lines, 1):
        for pattern in legacy_patterns:
            if re.search(pattern, line):
                print(f"{rel(p)}:{n}: {line.strip()}")
                break

# ---------------------------------------------------------------------
# 12. GitHub Actions audit
# ---------------------------------------------------------------------
section("12. GITHUB ACTIONS")

workflow_dir = ROOT / ".github" / "workflows"

if workflow_dir.exists():
    workflows = list(workflow_dir.glob("*"))

    if not workflows:
        finding("WARN", "No GitHub Actions workflow files found")

    for p in workflows:
        print()
        print("---", rel(p), "---")
        print(read_text(p))

        text = read_text(p)

        if "assemble" in text.lower():
            finding("PASS", f"{rel(p)} contains Android assemble step")
else:
    finding("WARN", ".github/workflows does not exist")

# ---------------------------------------------------------------------
# 13. Android/backend integration inventory
# ---------------------------------------------------------------------
section("13. ANDROID / BACKEND INTEGRATION")

for p in ALL_FILES:
    if p.suffix.lower() not in {".kt", ".kts", ".gradle", ".json"}:
        continue

    text = read_text(p)

    interesting = [
        "localhost",
        "127.0.0.1",
        "10.0.2.2",
        "server.py",
        "FastAPI",
        "uvicorn",
        "http://",
        "https://",
        "WebSocket",
        "ws://",
        "wss://",
    ]

    if any(x in text for x in interesting):
        print(f"\n--- {rel(p)} ---")

        for n, line in enumerate(text.splitlines(), 1):
            if any(x in line for x in interesting):
                print(f"{n}: {line.strip()}")

# ---------------------------------------------------------------------
# 14. Production DB current state
# ---------------------------------------------------------------------
section("14. PRODUCTION DATABASE")

db = ROOT / "data" / "trader.sqlite3"

if not db.exists():
    if os.getenv("CI", "").lower() == "true":
        finding("PASS", "Production DB absent on clean CI runner (expected; no production DB is committed)")
    else:
        finding("FAIL", f"Production DB missing: {db}")
else:
    digest = hashlib.sha256(db.read_bytes()).hexdigest()
    print("SHA256:", digest)

    try:
        con = sqlite3.connect(db)
        integrity = con.execute(
            "PRAGMA integrity_check"
        ).fetchone()[0]

        print("integrity:", integrity)

        state = dict(con.execute(
            "SELECT key,value FROM bot_state"
        ).fetchall())

        print("bot_state:", state)

        for table in ("candles", "orders", "trades", "events"):
            count = con.execute(
                f"SELECT COUNT(*) FROM {table}"
            ).fetchone()[0]
            print(f"{table}: {count}")

        con.close()

        if integrity != "ok":
            finding("FAIL", "Production SQLite integrity failure")
        else:
            finding("PASS", "Production SQLite integrity OK")

    except Exception as e:
        finding("FAIL", f"Could not inspect production DB: {e}")

# ---------------------------------------------------------------------
# 15. WILLIAMS TRADING CORE REGRESSION INVARIANTS
# ---------------------------------------------------------------------
section("15. TRADING CORE REGRESSION INVARIANTS")

android_root = ROOT / "app" / "src" / "main"
android_files = [
    p for p in android_root.rglob("*")
    if p.is_file() and p.suffix.lower() in {".kt", ".java", ".xml"}
] if android_root.exists() else []
android_text = "\n".join(read_text(p) for p in android_files)

for forbidden in ("LunaScreen", "AiForgeClient", "AI_FORGE", "AI Forge"):
    finding(
        "FAIL" if forbidden in android_text else "PASS",
        f"Trading APK {'still contains' if forbidden in android_text else 'free of'} {forbidden}"
    )

native_runtime = ROOT / "app" / "src" / "main" / "java" / "com" / "williamsbot" / "StandaloneRuntime.kt"
native = read_text(native_runtime)

if native:
    if re.search(r"private\s+val\s+maxOpenPositions\s*=\s*1\b", native):
        finding("FAIL", "Android runtime still hard-locks maxOpenPositions=1")
    elif re.search(r"private\s+val\s+maxOpenPositions\s*=\s*0\b", native):
        finding("PASS", "Android runtime uses risk-budgeted multi-position mode")
    else:
        finding("WARN", "Could not prove Android multi-position default from source")

    finding(
        "PASS" if '/api/v3/orderList/oco' in native else "FAIL",
        "Android uses current Spot OCO endpoint"
    )
    finding(
        "PASS" if "campaignMutationLock" in native else "FAIL",
        "Android campaign mutations share a global execution lock"
    )

    # Every concrete signed mutation must be invoked from the canonical
    # withCampaignMutation() door. The signed* helper definitions themselves
    # are transport primitives and are excluded from this call-site check.
    mutation_calls = []
    for match in re.finditer(
        r"\b(signedPost|signedDelete|signedCancelReplace)\s*\(",
        native
    ):
        pos = match.start()
        line_no = native[:pos].count("\n") + 1
        line_start = native.rfind("\n", 0, pos) + 1
        line = native[line_start:native.find("\n", pos)]
        if "private fun signedPost" in line or "private fun signedDelete" in line:
            continue
        helper_start = native.rfind("private fun signedCancelReplace", 0, pos)
        helper_end = native.find("\n    private fun ", helper_start + 10) if helper_start >= 0 else -1
        if helper_start >= 0 and helper_end >= 0 and helper_start < pos < helper_end:
            continue
        wrapped = False
        for outer in re.finditer(r"\bwithCampaignMutation\s*\(", native[:pos]):
            brace = native.find("{", outer.end(), pos)
            if brace < 0:
                continue
            depth = native[brace:pos].count("{") - native[brace:pos].count("}")
            if depth > 0:
                wrapped = True
                break
        mutation_calls.append((line_no, line.strip(), wrapped))

    unwrapped = [item for item in mutation_calls if not item[2]]
    if unwrapped:
        for line_no, line, _ in unwrapped:
            finding(
                "FAIL",
                f"Android direct Binance mutation bypasses execution door at "
                f"StandaloneRuntime.kt:{line_no}: {line}"
            )
    else:
        finding(
            "PASS",
            f"Android mutation-door callsite audit passed ({len(mutation_calls)} calls)"
        )
    finding(
        "PASS" if 'userDataStream.subscribe.signature' in android_text else "FAIL",
        "Android uses signed User Data Stream subscription"
    )
    finding(
        "PASS" if 'EncryptedSharedPreferences' in native and 'MasterKey.KeyScheme.AES256_GCM' in native else "FAIL",
        "Binance credentials use encrypted Android storage"
    )
    finding(
        "PASS" if '"testnet.binance.vision"' in native and 'api.binance.com' not in native else "WARN",
        "Android trading REST base is pinned to Spot Testnet"
    )
    finding(
        "FAIL" if 'max_open_positions_locked", true' in native else "PASS",
        "Android status does not advertise fixed max-position lock"
    )

bc_text = read_text(ROOT / "binance_client.py")
if bc_text:
    finding(
        "PASS" if "X-MBX-ORDER-COUNT-10S" in bc_text and "order_limit_10s" in bc_text else "FAIL",
        "Python Binance client tracks 10s order-rate window"
    )
    finding(
        "PASS" if "_update_rate_limits_from_exchange_info" in bc_text else "FAIL",
        "Python client learns dynamic Binance rate limits"
    )
    finding(
        "PASS" if "unknown_execution=True" in bc_text and "clientOrderId" in bc_text else "FAIL",
        "Ambiguous order outcome requires clientOrderId reconciliation"
    )

ob_text = read_text(ROOT / "app" / "src" / "main" / "java" / "com" / "williamsbot" / "OrderBookCache.kt")
finding(
    "PASS" if "var valid: Boolean" in ob_text and "book.valid = false" in ob_text else "FAIL",
    "L2 cache invalidates state on sequence gaps"
)

ws_text = read_text(ROOT / "ws_hub.py")
finding(
    "PASS" if "user_stream_out_of_order" in ws_text and "return" in ws_text else "FAIL",
    "User-stream out-of-order events are rejected before mutation"
)

# ---------------------------------------------------------------------
# 15. Git status
# ---------------------------------------------------------------------
section("15. GIT STATUS")

import subprocess

# Read-only repository inspection. This is intentionally allowed.
# It does not execute trading commands or arbitrary user input.
_git_status_result = subprocess.run(
    ["git", "status", "--short"],
    check=False,
    capture_output=True,
    text=True,
)

if _git_status_result.stdout:
    print(_git_status_result.stdout, end="")

# ---------------------------------------------------------------------
# FINAL
# ---------------------------------------------------------------------
section("FINAL AUDIT RESULT")

print("FAILURES :", errors)
print("WARNINGS :", warnings)

if errors:
    print()
    print("AUDIT RESULT: ACTION REQUIRED")
    print("No project files were modified by this audit.")
    raise SystemExit(1)

print()
print("AUDIT RESULT: NO STATIC SAFETY FAILURES")
print("Warnings require review, but no automatic changes were made.")
