from pathlib import Path

tests = [
    "recovery_oco_failure_test.py",
    "test_scanner_trader_dry_run.py",
    "test_selected_symbol_prepare.py",
]

for name in tests:
    p = Path(name)
    if not p.exists():
        print(f"[SKIP] {name} not found")
        continue

    s = p.read_text()

    # Добавляем tempfile, если его ещё нет
    if "import tempfile" not in s:
        lines = s.splitlines()
        insert_at = 0

        while insert_at < len(lines) and (
            lines[insert_at].startswith("#")
            or lines[insert_at].strip() == ""
        ):
            insert_at += 1

        lines.insert(insert_at, "import tempfile")
        s = "\n".join(lines) + ("\n" if s.endswith("\n") else "")

    # Устанавливаем отдельную БД ДО импорта trader
    if "WILLIAMS_TEST_DB" not in s:
        marker = "os.environ[\"WILLIAMS_TEST_DB\"]"

        block = """# TEST SAFETY: never use production DB
_test_db_fd, _test_db_path = tempfile.mkstemp(
    prefix="williams_test_",
    suffix=".sqlite3"
)
os.close(_test_db_fd)
os.environ["WILLIAMS_TEST_DB"] = _test_db_path
os.environ["WILLIAMS_DB_PATH"] = _test_db_path
"""

        # os нужен для блока
        if "import os" not in s:
            s = "import os\n" + s

        # Вставляем после imports, но до trader import
        idx = s.find("from trader import")
        if idx == -1:
            idx = s.find("import trader")

        if idx != -1:
            s = s[:idx] + block + "\n" + s[idx:]
        else:
            s = block + "\n" + s

    p.write_text(s)
    print(f"[OK] isolated: {name}")

print("=== TEST ISOLATION PATCH COMPLETE ===")
