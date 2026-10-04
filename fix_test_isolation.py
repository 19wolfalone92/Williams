from pathlib import Path

def replace(path, old, new):
    p = Path(path)
    s = p.read_text()
    if old not in s:
        print(f"[SKIP] pattern not found: {path}")
        return
    p.write_text(s.replace(old, new, 1))
    print(f"[OK] patched: {path}")

# ---------------------------------------------------------
# 1. Trader должен уважать WILLIAMS_DB_PATH
# ---------------------------------------------------------

replace(
    "trader.py",
    """self.db=Database(os.getenv('DB_PATH','data/trader.sqlite3'))""",
    """self.db=Database(
            os.getenv('WILLIAMS_DB_PATH')
            or os.getenv('DB_PATH')
            or 'data/trader.sqlite3'
        )"""
)

# ---------------------------------------------------------
# 2. FakeClient: step может приходить строкой
# ---------------------------------------------------------

replace(
    "recovery_oco_failure_test.py",
    """def decimal_floor(self,x,step):
        return float(f"{(int(x/step)*step):.8f}")""",
    """def decimal_floor(self,x,step):
        x=float(x)
        step=float(step)
        if step <= 0:
            raise ValueError("step must be > 0")
        return float(f"{(int(x/step)*step):.8f}")"""
)

print()
print("=== PATCH COMPLETE ===")
