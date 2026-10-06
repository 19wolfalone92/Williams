from l2_slippage import L2SlippageGuard, expected_fill_from_depth


def test_expected_fill_buy():
    avg, qty = expected_fill_from_depth(
        {"asks": [["100", "1"], ["101", "1"]], "bids": []},
        150,
        "BUY",
    )
    assert qty > 0
    assert avg > 100


def test_guard_blocks_deep_slippage():
    class Client:
        def book_ticker(self, symbol):
            return {"askPrice": "100"}
        def depth(self, symbol, limit=100):
            return {"asks": [["100", "1"], ["110", "1"]], "bids": []}

    guard = L2SlippageGuard(max_slippage_pct=0.001)
    try:
        guard.check_buy_quote(Client(), "BTCUSDT", 150)
    except ValueError as exc:
        assert "slippage" in str(exc)
    else:
        raise AssertionError("expected L2 slippage block")
