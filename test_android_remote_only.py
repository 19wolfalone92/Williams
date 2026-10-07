from pathlib import Path


ROOT = Path(__file__).resolve().parent
ANDROID = ROOT / "app" / "src" / "main"


def _read(path):
    return path.read_text(errors="replace") if path.exists() else ""


def test_local_trading_runtime_is_absent():
    assert not (
        ANDROID
        / "java/com/williamsbot/StandaloneRuntime.kt"
    ).exists()


def test_android_is_https_backend_only():
    main = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    fgs = _read(ANDROID / "java/com/williamsbot/WilliamsForegroundService.kt")
    network = _read(ANDROID / "res/xml/network_security_config.xml")

    assert "StandaloneRuntime" not in main
    assert "StandaloneRuntime" not in fgs
    assert "http://127.0.0.1" not in main
    assert "http://localhost" not in main
    assert "cleartextTrafficPermitted="true"" not in network


def test_android_never_persists_binance_credentials():
    main = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    backup = _read(ANDROID / "java/com/williamsbot/BackupManager.kt")

    assert ".putString("api_key"" not in main
    assert ".putString("api_secret"" not in main
    assert ".put("api_key"" not in backup
    assert ".put("api_secret"" not in backup
    assert ".putString("api_key"" not in backup
    assert ".putString("api_secret"" not in backup


def test_server_spot_contract_is_present():
    server = _read(ROOT / "server.py")
    assert "def portfolio():" in server
    assert "'account_type': 'SPOT'" in server
    assert "'source': 'binance_spot_account'" in server
    assert "'max_open_positions': multi.max_open_positions" in server
    assert "risk_capacity_positions" in server  # retained only for status calculation


def test_binance_client_has_ambiguous_execution_barrier():
    client = _read(ROOT / "binance_client.py")
    assert "unknown_execution=True" in client
    assert "order_safe" in client
    assert "create_oco_sell_safe" in client
    assert "X-MBX-ORDER-COUNT-10S" in client
