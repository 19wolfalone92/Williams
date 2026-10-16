"""Keep manual diagnostic CLIs out of automatic, offline pytest collection.

These legacy scripts perform network scans or require historical local fixtures
during import. They remain explicit CLI commands. Pytest tests are collected.
"""
collect_ignore = [
    "safe_smoke_test.py",
    "test_live_portfolio_scan.py",
    "test_live_scanner.py",
    "test_portfolio_integration.py",
    "test_scanner_diagnostics.py",
    "test_scanner_trader_dry_run.py",
    "test_selected_symbol_prepare.py",
    "test_signal_conditions.py",
]
