"""Testable release contracts; no exchange or trading operations are performed."""
from __future__ import annotations
import ast
import re
from pathlib import Path
from typing import Mapping

class ReleaseContractError(AssertionError):
    """A required release invariant is missing or inconsistent."""

WORKFLOWS = (
    ".github/workflows/android-apk.yml",
    ".github/workflows/campaign-ci.yml",
    ".github/workflows/python-ci.yml",
    ".github/workflows/testnet-readonly.yml",
)
VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")

def read_canonical_version(path: Path | str) -> str:
    source = Path(path)
    if not source.is_file():
        raise ReleaseContractError(f"Missing canonical version source: {source}")
    values = [line.strip() for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(values) != 1 or not VERSION_PATTERN.fullmatch(values[0]):
        raise ReleaseContractError("VERSION must contain exactly one MAJOR.MINOR.PATCH value.")
    return values[0]

def assert_version_alignment(canonical: str, backend: str, android: str) -> None:
    if backend != canonical or android != canonical:
        raise ReleaseContractError(f"Version mismatch: source={canonical!r}, backend={backend!r}, Android={android!r}")

def _assignment(tree: ast.Module, name: str) -> ast.expr | None:
    values = []
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == name for t in node.targets):
            values.append(node.value)
        elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name) and node.target.id == name and node.value is not None:
            values.append(node.value)
    if len(values) > 1:
        raise ReleaseContractError(f"Multiple assignments for {name}.")
    return values[0] if values else None

def validate_backend_version_source(server: str, version_module: str) -> None:
    st, vt = ast.parse(server), ast.parse(version_module)
    imported = any(isinstance(n, ast.ImportFrom) and n.module == "version_contract" and any(a.name == "APP_VERSION" for a in n.names) for n in st.body)
    version_assignment = _assignment(st, "VERSION")
    app_assignment = _assignment(vt, "APP_VERSION")
    path_source = re.search(r"""VERSION_FILE\s*=\s*Path\(__file__\)\.resolve\(\)\.parent\s*/\s*["']VERSION["']""", version_module)
    reader = any(isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name == "read_version" for n in vt.body)
    dynamic_app = isinstance(app_assignment, ast.Call) and isinstance(app_assignment.func, ast.Name) and app_assignment.func.id == "read_version" and not app_assignment.args
    if not (imported and isinstance(version_assignment, ast.Name) and version_assignment.id == "APP_VERSION" and path_source and reader and dynamic_app):
        raise ReleaseContractError("Backend VERSION is not structurally sourced from the repository VERSION file.")

def validate_android_gradle_version_source(source: str) -> None:
    values = re.findall(r"(?m)^\s*versionName\s*=\s*([^\s]+)", source)
    if values != ["canonicalAppVersion"]:
        raise ReleaseContractError("Android versionName must use canonicalAppVersion, not a duplicate literal.")
    has_file = re.search(r"""val\s+canonicalVersionFile\s*=\s*rootProject\.file\(["']VERSION["']\)""", source)
    has_read = re.search(r"val\s+canonicalAppVersion\s*=\s*canonicalVersionFile\.readText\(Charsets\.UTF_8\)\.trim\(\)", source)
    if not has_file or not has_read or "GradleException" not in source:
        raise ReleaseContractError("Gradle must read and validate the canonical VERSION file.")

def validate_android_ui_contract(source: str) -> None:
    visible = re.search(r'Text\(\s*"[^"]*"\s*\+\s*BuildConfig\.VERSION_NAME', source)
    stale = re.search(r"WILLIAMS(?:\s+COCKPIT)?\s+\d+\.\d+\.\d+", source, re.IGNORECASE)
    if not visible or stale:
        raise ReleaseContractError("Android UI must display BuildConfig.VERSION_NAME and contain no stale hard-coded app version.")

def parse_apk_version_from_badging(output: str) -> str:
    match = re.search(r"versionName='([^']+)'", output)
    if not match:
        raise ReleaseContractError("aapt badging output does not include versionName.")
    return match.group(1)

def validate_artifact_version(source: str, artifact: str, variant: str) -> None:
    if variant not in {"debug", "release"}:
        raise ReleaseContractError(f"Unknown APK variant: {variant!r}")
    expected = source + ("-debug" if variant == "debug" else "")
    if artifact != expected:
        raise ReleaseContractError(f"{variant} APK version mismatch: expected {expected!r}, got {artifact!r}")

def parse_dotenv(path: Path | str) -> dict[str, str]:
    source = Path(path)
    if not source.is_file():
        raise ReleaseContractError(f"Missing configuration template: {source}")
    values: dict[str, str] = {}
    for number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            if key in values:
                raise ReleaseContractError(f"Duplicate key {key} at {source}:{number}")
            values[key] = value.strip().strip('"').strip("'")
    return values

def execution_start_blocker(env: Mapping[str, str], *, api_key: str | None, api_secret: str | None, configured_testnet: bool) -> str | None:
    mode = str(env.get("TESTNET", "")).strip().lower()
    if mode not in {"true", "false"}:
        return "TESTNET must be explicitly configured as true or false before trading can start."
    if (mode == "true") != bool(configured_testnet):
        return "Configured Binance environment does not match TESTNET."
    if not str(api_key or "").strip() or not str(api_secret or "").strip():
        return "Binance API key and secret are required before trading can start."
    if mode == "false":
        if str(env.get("ALLOW_LIVE", "false")).strip().lower() != "true":
            return "Mainnet is blocked unless ALLOW_LIVE=true is explicitly configured."
        if str(env.get("DRY_RUN", "true")).strip().lower() != "false":
            return "Mainnet is blocked unless DRY_RUN=false is explicitly configured."
    return None

def _api_returns_version(source: str, function_name: str) -> bool:
    tree = ast.parse(source)
    node = next((n for n in tree.body if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name == function_name), None)
    if node is None:
        return False
    return any(
        isinstance(n, ast.Dict) and any(isinstance(k, ast.Constant) and k.value == "version" and isinstance(v, ast.Name) and v.id == "VERSION" for k, v in zip(n.keys, n.values))
        for n in ast.walk(node)
    )

def _validate_config(root: Path, env: Mapping[str, str]) -> None:
    tree = ast.parse((root / "trading_config.py").read_text(encoding="utf-8"))
    cls = next((n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "TradingConfig"), None)
    if cls is None:
        raise ReleaseContractError("TradingConfig is missing.")
    fields = {}
    for node in cls.body:
        if isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name) and node.value is not None:
            try:
                fields[node.target.id] = ast.literal_eval(node.value)
            except (TypeError, ValueError):
                pass
    defaults = {"dry_run": True, "allow_live": False, "max_open_positions": 5, "risk_per_trade_pct": 0.005, "max_total_risk_pct": 0.01}
    if any(fields.get(k) != v for k, v in defaults.items()):
        raise ReleaseContractError(f"TradingConfig safety defaults changed: {defaults!r}")
    required_env = {
        "TESTNET": "true", "ALLOW_LIVE": "false", "DRY_RUN": "true",
        "MAX_OPEN_POSITIONS": "5", "AUTO_SCAN_SYMBOLS": "", "SCAN_ALL_USDT": "true",
        "SCAN_MAX_SYMBOLS": "0", "LIQUIDITY_PRESELECT": "0", "WAVE_FULL_TF_ALL": "true",
        "MAX_TOTAL_RISK_PCT": "0.01", "MAX_RISK_PER_TRADE_PCT": "0.005",
    }
    mismatches = {k: (v, env.get(k)) for k, v in required_env.items() if env.get(k) != v}
    if mismatches:
        raise ReleaseContractError(f".env.example safety/config contract mismatch: {mismatches!r}")
    if "def execution_start_blocker(" not in (root / "trading_config.py").read_text(encoding="utf-8"):
        raise ReleaseContractError("Trading start mode/credential validator is missing.")

def validate_gradle_wrapper_version(source: str) -> None:
    urls = [
        line.split("=", 1)[1]
        for line in source.splitlines()
        if line.startswith("distributionUrl=")
    ]
    if len(urls) != 1:
        raise ReleaseContractError("Expected one Gradle wrapper distributionUrl.")
    url = urls[0]
    if "gradle-" not in url or "-bin.zip" not in url:
        raise ReleaseContractError("Gradle wrapper distribution URL is missing or malformed.")
    version_text = url.split("gradle-", 1)[1].split("-bin.zip", 1)[0]
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)+", version_text):
        raise ReleaseContractError("Gradle wrapper version in distribution URL is malformed.")
    version = tuple(int(part) for part in version_text.split("."))
    if version < (8, 14, 6):
        raise ReleaseContractError(
            "Gradle wrapper must be at least 8.14.6; the current Android lint gate rejects 8.14.5."
        )
def _validate_workflows(root: Path) -> None:
    texts = {}
    for name in WORKFLOWS:
        path = root / name
        if not path.is_file():
            raise ReleaseContractError(f"Required workflow is missing: {name}")
        active = "\n".join(line for line in path.read_text(encoding="utf-8").splitlines() if not line.lstrip().startswith("#"))
        if not re.search(r"(?m)^\s*run:\s*python3\s+ci_release_gate\.py\s*$", active):
            raise ReleaseContractError(f"Workflow is missing an executable release-gate step: {name}")
        # Reject shell/GitHub secret expansion into output, but allow fixed error messages
        # that merely name a missing secret without revealing its value.
        secret_expansion = re.compile(
            r"(?i)(?:echo|printf)\s+[^\n]*"
            r"(?:\$\{?\s*(?:BINANCE_API_SECRET|WILLIAMS_TESTNET_API_SECRET|WILLIAMS_TESTNET_API_KEY)\s*\}?"
            r"|\$\{\{\s*secrets\.(?:WILLIAMS_TESTNET_API_SECRET|WILLIAMS_TESTNET_API_KEY)\s*\}\})"
        )
        if secret_expansion.search(active):
            raise ReleaseContractError(f"Workflow may expose an exchange secret value: {name}")
        texts[name] = active
    testnet = texts[".github/workflows/testnet-readonly.yml"]
    if not re.search(r"""(?m)^\s*TESTNET:\s*["']true["']\s*$""", testnet):
        raise ReleaseContractError("Testnet workflow must explicitly set TESTNET=true.")
    if not re.search(r"""(?m)^\s*ALLOW_LIVE:\s*["']false["']\s*$""", testnet):
        raise ReleaseContractError("Testnet workflow must explicitly set ALLOW_LIVE=false.")
    if "test_testnet_release_gate.py --read-only" not in testnet:
        raise ReleaseContractError("Testnet workflow must run the non-mutating read-only gate.")
    android = texts[".github/workflows/android-apk.yml"]
    for variant, path in (("debug", "app/build/outputs/apk/debug/app-debug.apk"), ("release", "app/build/outputs/apk/release/app-release-unsigned.apk")):
        if f"python3 verify_apk_version.py --apk {path} --variant {variant}" not in android:
            raise ReleaseContractError(f"Android workflow must verify embedded {variant} APK version.")
    for name, text in texts.items():
        if re.search(r"(?m)^\s*path:.*(?:^|/)\.env(?:$|/|\*)", text):
            raise ReleaseContractError(f"Workflow artifact may include .env: {name}")

def validate_release_contract(root: Path | str) -> str:
    root = Path(root).resolve()
    version = read_canonical_version(root / "VERSION")
    required = (
        "version_contract.py", "release_contract.py", "server.py", "trading_config.py", "ci_release_gate.py",
        "market_scanner.py", "portfolio_controller.py", "portfolio_trader.py", "trader.py",
        "wave_engine.py", "binance_client.py", "preflight_gate.py", "test_testnet_release_gate.py",
        "test_release_contract.py", "gradle/wrapper/gradle-wrapper.properties", "app/build.gradle.kts", "app/src/main/java/com/williamsbot/MainActivity.kt",
        "app/src/main/java/com/williamsbot/StandaloneRuntime.kt", "app/src/main/java/com/williamsbot/BinanceUserDataStream.kt",
        ".env.example", "README.md", "docs/RELEASE_CONTRACT.md",
    )
    for relative in required:
        if not (root / relative).is_file():
            raise ReleaseContractError(f"Required release-contract input is missing: {relative}")
    server = (root / "server.py").read_text(encoding="utf-8")
    validate_backend_version_source(server, (root / "version_contract.py").read_text(encoding="utf-8"))
    validate_android_gradle_version_source((root / "app/build.gradle.kts").read_text(encoding="utf-8"))
    validate_gradle_wrapper_version((root / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8"))
    validate_android_ui_contract((root / "app/src/main/java/com/williamsbot/MainActivity.kt").read_text(encoding="utf-8"))
    for name in ("health", "status", "settings"):
        if not _api_returns_version(server, name):
            raise ReleaseContractError(f"/api/v1/{name} does not return the canonical backend version.")
    if 'result["version"] = VERSION' not in server:
        raise ReleaseContractError("Scanner endpoint does not return the canonical backend version.")
    native = (root / "app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text(encoding="utf-8")
    for marker in ('.put("app_version", BuildConfig.VERSION_NAME)', '.put("version", BuildConfig.VERSION_NAME)', '.put("binance_testnet", true)', "https://testnet.binance.vision", "wss://stream.testnet.binance.vision", "wss://ws-api.testnet.binance.vision/ws-api/v3"):
        if marker not in native:
            raise ReleaseContractError(f"Native Android Testnet/version diagnostics missing contract: {marker}")
    env = parse_dotenv(root / ".env.example")
    _validate_config(root, env)
    forbidden = ("/" + "fapi/", "binance" + "future.com")
    for relative in ("server.py", "trader.py", "binance_client.py", "data.py", "ws_hub.py",
                     "app/src/main/java/com/williamsbot/StandaloneRuntime.kt",
                     "app/src/main/java/com/williamsbot/BinanceUserDataStream.kt"):
        source = (root / relative).read_text(encoding="utf-8")
        if any(fragment in source for fragment in forbidden):
            raise ReleaseContractError(f"Spot release scope includes a Futures endpoint in {relative}.")
    if "class BinanceSpotClient" not in (root / "binance_client.py").read_text(encoding="utf-8"):
        raise ReleaseContractError("Python backend execution adapter is not Spot.")
    for relative in ("README.md", "docs/RELEASE_CONTRACT.md"):
        if "VERSION" not in (root / relative).read_text(encoding="utf-8"):
            raise ReleaseContractError(f"{relative} must document the canonical VERSION source.")
    _validate_workflows(root)
    return version
