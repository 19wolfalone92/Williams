from pathlib import Path


ROOT = Path(__file__).resolve().parent
ANDROID = ROOT / "app" / "src" / "main"


def _read(path):
    return path.read_text(errors="replace") if path.exists() else ""


def test_android_has_remote_cockpit_runtime():
    standalone = ANDROID / "java/com/williamsbot/StandaloneRuntime.kt"
    user_stream = ANDROID / "java/com/williamsbot/BinanceUserDataStream.kt"
    service = ANDROID / "java/com/williamsbot/TradingForegroundService.kt"
    assert standalone.exists()
    assert user_stream.exists()
    assert service.exists()

    main = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    assert "TradingForegroundService" in main or "BackendApi" in main
    assert "https://" in main
    assert "StandaloneRuntime.start(this)" not in main


def test_android_local_runtime_does_not_require_remote_https():
    main = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    network = _read(ANDROID / "res/xml/network_security_config.xml")
    assert "http://127.0.0.1:18080" in main
    assert "https://" in main
    assert "127.0.0.1" in network
    assert 'cleartextTrafficPermitted="true"' in network


def test_android_does_not_clear_native_binance_credentials_on_start():
    main = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    assert 'remove("api_key")' not in main
    assert 'remove("api_secret")' not in main


def test_android_does_not_start_native_spot_runtime():
    main = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    service = _read(ANDROID / "java/com/williamsbot/TradingForegroundService.kt")
    assert "StandaloneRuntime.start(this)" not in main
    assert "StandaloneRuntime.autostart(this)" not in main
    assert "legacy native Spot runtime" in service
    assert "remote Futures backend" in service


def test_android_has_portfolio_local_api():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    assert 'path == "/api/v1/portfolio"' in runtime
    assert "fun portfolio()" in runtime


def test_android_never_stores_binance_credentials_in_main_activity():
    main = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    assert '.putString("api_key"' not in main
    assert '.putString("api_secret"' not in main


def test_server_futures_contract_is_present():
    server = _read(ROOT / "server.py")
    assert "def portfolio():" in server
    assert "'account_type': 'FUTURES'" in server
    assert "'max_open_positions': multi.max_open_positions" in server
    assert "risk_capacity_positions" in server


def test_binance_client_has_ambiguous_execution_barrier():
    client = _read(ROOT / "binance_client.py")
    assert "unknown_execution=True" in client
    assert "order_safe" in client
    assert "create_oco_sell_safe" in client
    assert "X-MBX-ORDER-COUNT-10S" in client


def test_active_backend_uses_futures_and_legacy_spot_is_not_authoritative():
    server = _read(ROOT / "server.py")
    futures = _read(ROOT / "binance_futures_client.py")
    runtime = _read(ROOT / "futures_williams_runtime.py")
    assert "BinanceFuturesClient" in server
    assert "FuturesWilliamsRuntime" in server
    assert "/fapi/v1/order" in futures
    assert "positionAmt" in futures
    assert "reduceOnly" in futures
    assert "STOP_MARKET" in runtime

def test_environment_secrets_are_gitignored():
    ignore = _read(ROOT / ".gitignore")
    assert ".env" in ignore
    assert "keystore.properties" in ignore
    assert "data/binance_credentials.enc" in ignore
    assert "data/credential_master.key" in ignore
