"""Williams Testnet Release Gate.

This file is the machine-readable contract for the final pre-live verification.
Offline CI validates the contract and safety invariants.  The read-only live
mode can be run manually against Binance Spot Testnet with explicit opt-in.

A full trade cycle is never started implicitly by pytest or by APK builds.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

from binance_client import BinanceSpotClient
from preflight_gate import PreflightCheckService


@dataclass(frozen=True)
class GateStep:
    id: str
    phase: str
    description: str
    mode: str  # OFFLINE, TESTNET_READ_ONLY, TESTNET_TRADE


GATE_STEPS = (
    GateStep("P01", "environment", "Testnet endpoint is selected everywhere", "OFFLINE"),
    GateStep("P02", "environment", "Production endpoint is impossible without explicit live permission", "OFFLINE"),
    GateStep("P03", "auth", "API key and secret are accepted by Binance", "TESTNET_READ_ONLY"),
    GateStep("P04", "auth", "Request signature/timestamp/recvWindow are valid", "TESTNET_READ_ONLY"),
    GateStep("P05", "auth", "Spot account is reachable and is not locked", "TESTNET_READ_ONLY"),
    GateStep("P06", "market", "exchangeInfo and symbol status are valid", "TESTNET_READ_ONLY"),
    GateStep("P07", "market", "PRICE/LOT/MIN_NOTIONAL filters are enforced", "OFFLINE"),
    GateStep("P08", "market", "Market data is fresh and internally consistent", "TESTNET_READ_ONLY"),
    GateStep("P09", "scanner", "Scanner starts and heartbeat/age telemetry updates", "TESTNET_READ_ONLY"),
    GateStep("P10", "scanner", "Scanner selects the intended universe and candidate set", "OFFLINE"),
    GateStep("P11", "strategy", "Signal -> Wave Engine -> MTF gate is bound", "OFFLINE"),
    GateStep("P12", "strategy", "Wave 3 is preferred over an exhausted Wave 5 when appropriate", "OFFLINE"),
    GateStep("P13", "strategy", "Nested lower-TF Wave 3 inside higher-TF Wave 5 is handled", "OFFLINE"),
    GateStep("P14", "risk", "Risk sizing, RR, ATR, spread and breakers agree", "OFFLINE"),
    GateStep("P15", "execution", "Execution intent is durable before exchange submission", "OFFLINE"),
    GateStep("P16", "execution", "Duplicate BUY cannot be admitted by concurrent workers", "OFFLINE"),
    GateStep("P17", "execution", "BUY is submitted once and its Binance order ID is durable", "TESTNET_TRADE"),
    GateStep("P18", "execution", "Actual fills determine quantity and VWAP", "TESTNET_TRADE"),
    GateStep("P19", "protection", "Native OCO is created and verified on Binance", "TESTNET_TRADE"),
    GateStep("P20", "protection", "OCO child legs are one shared position quantity, never additive", "OFFLINE"),
    GateStep("P21", "protection", "One OCO leg PARTIALLY_FILLED while the sibling remains active", "OFFLINE"),
    GateStep("P22", "protection", "Partial OCO execution leaves exactly the residual inventory protected", "OFFLINE"),
    GateStep("P23", "recovery", "Crash after BUY and before OCO is recoverable and blocked safely", "OFFLINE"),
    GateStep("P24", "recovery", "Crash after OCO / after exit event reconciles to Binance", "OFFLINE"),
    GateStep("P25", "recovery", "REST catch-up closes WebSocket gaps and persists events", "OFFLINE"),
    GateStep("P26", "recovery", "DB state equals authoritative Binance state after restart", "TESTNET_TRADE"),
    GateStep("P27", "android", "APK receives scanner/Binance/WebSocket state from backend", "TESTNET_READ_ONLY"),
    GateStep("P28", "android", "Clean reinstall cannot resurrect local Binance credentials", "OFFLINE"),
    GateStep("P29", "portfolio", "Portfolio balances/positions/orders agree with Binance", "TESTNET_READ_ONLY"),
    GateStep("P30", "release", "Final state is CLEAN/PASS and only then Testnet trading is considered enabled", "TESTNET_TRADE"),
)


def live_e2e_enabled() -> bool:
    return os.getenv("WILLIAMS_TESTNET_E2E", "").strip() == "1"


def assert_live_opt_in() -> None:
    if not live_e2e_enabled():
        raise RuntimeError(
            "Live Testnet execution is disabled. Set WILLIAMS_TESTNET_E2E=1 explicitly."
        )
    if os.getenv("TESTNET", "true").strip().lower() != "true":
        raise RuntimeError("Release Gate refuses to run: TESTNET must be true.")
    if not os.getenv("BINANCE_API_KEY") or not os.getenv("BINANCE_API_SECRET"):
        raise RuntimeError(
            "Release Gate requires BINANCE_API_KEY and BINANCE_API_SECRET."
        )


def run_testnet_read_only(symbol: str = "BTCUSDT") -> dict:
    """Run authenticated, non-mutating Binance Spot Testnet checks."""
    assert_live_opt_in()
    client = BinanceSpotClient(
        os.environ["BINANCE_API_KEY"],
        os.environ["BINANCE_API_SECRET"],
        testnet=True,
    )
    if client.base_url != "https://testnet.binance.vision":
        raise RuntimeError(
            f"Refusing Testnet Gate: unexpected endpoint {client.base_url!r}"
        )

    service = PreflightCheckService(
        client,
        symbols=[symbol],
        max_open_positions=int(os.getenv("MAX_OPEN_POSITIONS", "0")),
    )
    report = service.verify_all()
    if not report.get("ready"):
        raise RuntimeError(f"Testnet preflight failed: {report}")
    return report


def test_gate_manifest_has_all_required_release_steps():
    assert len(GATE_STEPS) == 30
    assert [step.id for step in GATE_STEPS] == [f"P{i:02d}" for i in range(1, 31)]
    assert {step.mode for step in GATE_STEPS} == {
        "OFFLINE",
        "TESTNET_READ_ONLY",
        "TESTNET_TRADE",
    }


def test_gate_contains_partial_oco_safety_cases():
    ids = {step.id for step in GATE_STEPS}
    assert {"P20", "P21", "P22"} <= ids
    text = " ".join(
        step.description.lower()
        for step in GATE_STEPS
        if step.id in {"P20", "P21", "P22"}
    )
    assert "partially_filled" in text
    assert "shared" in text
    assert "residual" in text


def test_testnet_client_uses_testnet_endpoint():
    testnet = BinanceSpotClient("k", "s", testnet=True)
    live = BinanceSpotClient("k", "s", testnet=False)
    assert testnet.base_url == "https://testnet.binance.vision"
    assert live.base_url == "https://api.binance.com"


def test_live_gate_is_opt_in_only(monkeypatch):
    monkeypatch.delenv("WILLIAMS_TESTNET_E2E", raising=False)
    with __import__("pytest").raises(RuntimeError):
        assert_live_opt_in()


def test_release_gate_never_defaults_to_live(monkeypatch):
    monkeypatch.delenv("WILLIAMS_TESTNET_E2E", raising=False)
    monkeypatch.delenv("TESTNET", raising=False)
    assert live_e2e_enabled() is False


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Williams Testnet Release Gate")
    parser.add_argument(
        "--read-only",
        action="store_true",
        help="Run authenticated non-mutating Binance Spot Testnet preflight",
    )
    parser.add_argument(
        "--print-checklist",
        action="store_true",
        help="Print the 30-step release checklist",
    )
    args = parser.parse_args()

    if args.print_checklist:
        for step in GATE_STEPS:
            print(f"{step.id} [{step.mode}] {step.description}")

    if args.read_only:
        result = run_testnet_read_only()
        print("TESTNET READ-ONLY GATE: PASS")
        print(result)
