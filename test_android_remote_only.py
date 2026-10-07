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
    assert 'cleartextTrafficPermitted="true"' not in network


def test_android_never_persists_binance_credentials():
    main = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    backup = _read(ANDROID / "java/com/williamsbot/BackupManager.kt")

    assert '.putString("api_key"' not in main
    assert '.putString("api_secret"' not in main
    assert '.put("api_key"' not in backup
    assert '.put("api_secret"' not in backup
    assert '.putString("api_key"' not in backup
    assert '.putString("api_secret"' not in backup


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


def test_spot_testnet_endpoints_and_no_futures_execution():
    client = _read(ROOT / "binance_client.py")
    data = _read(ROOT / "data.py")
    ws = _read(ROOT / "ws_hub.py")

    assert "https://testnet.binance.vision" in client
    assert "testnet.binance.vision" in data
    assert "wss://stream.testnet.binance.vision" in ws
    assert "wss://ws-api.testnet.binance.vision/ws-api/v3" in ws

    # Williams trading core is Spot-only. Research files may discuss derivatives,
    # but executable Binance Futures endpoints must not exist anywhere in Python.
    for path in ROOT.rglob("*.py"):
        if any(part in {".git", "__pycache__"} for part in path.parts):
            continue
        if path == ROOT / "test_android_remote_only.py":
            continue
        text = path.read_text(errors="replace")
        assert "/fapi/" not in text, f"Futures REST endpoint found in {path}"
        assert "binancefuture.com" not in text, f"Futures Testnet host found in {path}"


def test_environment_secrets_are_gitignored():
    ignore = _read(ROOT / ".gitignore")
    assert ".env" in ignore
    assert "keystore.properties" in ignore
    assert "data/binance_credentials.enc" in ignore
    assert "data/credential_master.key" in ignore



def test_android_does_not_register_local_foreground_trading_runtime():
    manifest = _read(ANDROID / "AndroidManifest.xml")
    main = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")

    assert "WilliamsForegroundService" not in manifest
    assert "FOREGROUND_SERVICE" not in manifest
    assert "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" not in main


def test_chart_refresh_binds_symbol_and_timeframe():
    main = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")

    assert "LaunchedEffect(selectedPositionSymbol, selectedChartInterval)" in main
    assert "/api/v1/market/klines?symbol=" in main
    assert "&interval=" in main



def test_debug_android_manifest_does_not_allow_cleartext():
    debug_manifest = _read(ROOT / "app/src/debug/AndroidManifest.xml")
    debug_network = _read(ROOT / "app/src/debug/res/xml/network_security_config.xml")

    assert 'usesCleartextTraffic="true"' not in debug_manifest
    assert 'cleartextTrafficPermitted="true"' not in debug_network



def test_android_has_no_direct_binance_user_data_client():
    direct_ws = ANDROID / "java/com/williamsbot/BinanceUserDataStream.kt"
    assert not direct_ws.exists()



def test_positions_widget_uses_encrypted_https_backend_only():
    widget = _read(
        ANDROID / "java/com/williamsbot/PositionsWidgetProvider.kt"
    )

    assert "EncryptedSharedPreferences" in widget
    assert 'williams_backend_connection' in widget
    assert "http://127.0.0.1:18080" not in widget
    assert '"https://"' in widget
