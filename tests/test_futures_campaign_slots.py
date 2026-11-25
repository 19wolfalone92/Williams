from futures_williams_runtime import active_campaign_slot_count


def test_pending_initial_entries_consume_campaign_slots():
    opened = {f"OPEN{i}USDT" for i in range(1, 7)}
    pending = {f"PENDING{i}USDT" for i in range(1, 5)}
    assert active_campaign_slot_count(opened, pending) == 10


def test_open_campaign_and_its_pending_addon_consume_only_one_slot():
    opened = {"BTCUSDT", "ETHUSDT"}
    pending = {"BTCUSDT", "ETHUSDT", "SOLUSDT"}
    assert active_campaign_slot_count(opened, pending) == 3


def test_ten_pending_campaigns_exhaust_the_portfolio_even_without_fills():
    pending = {
        "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
        "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT",
    }
    assert active_campaign_slot_count(set(), pending) == 10
    assert active_campaign_slot_count(set(), pending | {"SUIUSDT"}) == 11


def test_campaign_slot_count_is_case_insensitive_and_deduplicated():
    assert active_campaign_slot_count(
        {"btcusdt", "BTCUSDT", "ETHUSDT"},
        {"BTCUSDT", "ethusdt", "SOLUSDT"},
    ) == 3
