from pathlib import Path


ROOT = Path(__file__).resolve().parent
ANDROID = ROOT / "app" / "src" / "main"


def _read(path):
    return path.read_text(errors="replace") if path.exists() else ""


def test_android_has_autonomous_runtime():
    standalone = ANDROID / "java/com/williamsbot/StandaloneRuntime.kt"
    user_stream = ANDROID / "java/com/williamsbot/BinanceUserDataStream.kt"
    service = ANDROID / "java/com/williamsbot/TradingForegroundService.kt"
    assert standalone.exists()
    assert user_stream.exists()
    assert service.exists()

    main = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    assert "TradingForegroundService" in main
    assert "https://" in main


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


def test_native_runtime_uses_binance_spot_testnet():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    ws = _read(ANDROID / "java/com/williamsbot/BinanceUserDataStream.kt")
    assert "https://testnet.binance.vision" in runtime
    assert "wss://stream.testnet.binance.vision" in runtime
    # Require the exact canonical Spot Testnet WebSocket API endpoint.
    assert "wss://ws-api.testnet.binance.vision/ws-api/v3" in runtime
    assert "private val maxOpenPositions: Int = 1" in runtime
    assert "private val maxSlippagePct = 0.0015" in runtime
    assert "val maxOpenPositions: Int = 1" in _read(ANDROID / "java/com/williamsbot/MainActivity.kt")


def test_android_has_portfolio_local_api():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    assert 'path == "/api/v1/portfolio"' in runtime
    assert "fun portfolio()" in runtime


def test_android_never_stores_binance_credentials_in_main_activity():
    main = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    assert '.putString("api_key"' not in main
    assert '.putString("api_secret"' not in main


def test_server_spot_contract_is_present():
    server = _read(ROOT / "server.py")
    assert "def portfolio():" in server
    assert "'account_type': 'SPOT'" in server
    assert "'max_open_positions': multi.max_open_positions" in server
    assert "risk_capacity_positions" in server


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
