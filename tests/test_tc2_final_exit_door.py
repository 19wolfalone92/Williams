"""Exercise the production market-exit boundary before pending-add cancellation/I/O."""
from types import SimpleNamespace
import pytest

from campaign_model import CampaignState
from futures_williams_runtime import FuturesWilliamsRuntime
from williams_edition_rules import TC2, NTD, COURSE
from williams_execution_policy import market_exit_blocker
from williams_exit_policy import plan_williams_stop
from db import Database
from market_context import ContextCache
from execution_barrier import ExecutionBarrier


@pytest.fixture
def exit_runtime(tmp_path):
    runtime=object.__new__(FuturesWilliamsRuntime)
    runtime.db=Database(str(tmp_path / 'exit-audit.sqlite3'))
    runtime.execution_barrier=ExecutionBarrier(ContextCache(),runtime.db)
    yield runtime
    runtime.db.conn.close()


@pytest.mark.parametrize("side", ["BUY", "SELL"])
@pytest.mark.parametrize("reason", [
    "STRATEGY_EXIT", "CORRECTION_PARENT_PRICE_INVALIDATED",
    "TAKE_PROFIT", "LIQUIDATION_GUARD",
])
def test_tc2_direct_exit_is_blocked_before_cancel_or_position_read(side, reason, exit_runtime):
    runtime = exit_runtime
    calls = []
    runtime.cancel_pending_entries = lambda *a: calls.append("cancel")
    runtime._position = lambda *a: calls.append("position")
    campaign = SimpleNamespace(symbol="BTCUSDT", side=side,
                               state=CampaignState.ADD_ON_PENDING,
                               tags={"strategy_profile": TC2})
    with pytest.raises(RuntimeError, match="TC2_EXIT_REQUIRES_BOOK_OPPOSITE_TRIGGER"):
        runtime._exit_market(campaign, reason)
    assert calls == []
    assert campaign.state == CampaignState.ADD_ON_PENDING
    assert "pending_exit_client_id" not in campaign.tags


@pytest.mark.parametrize("source,reason,emergency", [
    (TC2, "MANUAL_EXIT", False), (TC2, "KILL_SWITCH", False),
    (TC2, "OPPOSITE_WILLIAMS_LEVEL_TRIGGERED", False),
    (TC2, "OPPOSITE_WILLIAMS_TRIGGER_IN_HIGHER_CORRECTION", False),
    (TC2, "LIQUIDATION_GUARD_TEST", True),
    (NTD, "TEETH_CLOSE_EXIT", False),
    (COURSE, "COURSE_STAGE_TRIM", False),
])
def test_authorized_exit_still_reaches_existing_live_preflight(source, reason, emergency, exit_runtime):
    class ReachedLivePreflight(Exception):
        pass
    runtime = exit_runtime
    def position(_):
        raise ReachedLivePreflight()
    runtime._position = position
    import time
    now=int(time.time()*1000)
    tags={"strategy_profile":source}
    if source==TC2 and reason.startswith("OPPOSITE_WILLIAMS_"):
        tags["tc2_book_exit_proof"]={
            "reason":reason, "symbol":"BTCUSDT", "observed_at_ms":now-1000,
            "opened_at_ms":now-2*3_600_000, "strategy_profile":TC2,
            "direction":"SHORT","signal_id":"book_opposite_1",
            "trigger":96.0,"trigger_price_basis":"CONTRACT_PRICE","contract_price":95.0,
            "event":{"state":"TRIGGERED","known_at_ms":now-60_000}}
        tags["last_opposition_evidence"]={
            "signal_id":"book_opposite_1","event":tags["tc2_book_exit_proof"]["event"]}
    campaign = SimpleNamespace(symbol="BTCUSDT",side="BUY",state=CampaignState.TREND_ACTIVE,
                               tags=tags)
    with pytest.raises(ReachedLivePreflight):
        runtime._exit_market(campaign, reason, risk_emergency=emergency)


def test_unknown_edition_cannot_authorize_a_strategy_exit():
    assert market_exit_blocker("UNKNOWN", "STRATEGY_EXIT") == "EXIT_STRATEGY_OWNER_UNVERIFIED"


@pytest.mark.parametrize("side", ["BUY", "SELL"])
@pytest.mark.parametrize("contract_triggered", [False, True])
def test_trade_candle_exit_does_not_substitute_mark_for_contract(exit_runtime, side, contract_triggered):
    import time
    class ReachedVerifiedQuantity(Exception):
        pass
    now = int(time.time()*1000)
    runtime = exit_runtime
    # Both marks are profitable; only CONTRACT can prove this book breakout.
    trigger, crossed, uncrossed = (110.,108.,111.) if side == "BUY" else (90.,92.,89.)
    contract = crossed if contract_triggered else uncrossed
    mark = uncrossed if contract_triggered else crossed
    entry = 90. if side == "BUY" else 110.
    runtime.client = SimpleNamespace(time_offset_ms=0,
        ticker_price=lambda symbol:{"symbol":symbol,"price":str(contract)},
        mark_price=lambda symbol:{"symbol":symbol,"markPrice":str(mark)})
    runtime._position = lambda symbol:{"entryPrice":str(entry)}
    runtime._signed_position_qty = lambda pos:1. if side == "BUY" else -1.
    runtime._full_market_close_qty = lambda *args:(_ for _ in ()).throw(ReachedVerifiedQuantity())
    reason = "OPPOSITE_WILLIAMS_LEVEL_TRIGGERED"
    event = {"state":"TRIGGERED","known_at_ms":now-1000}
    proof = {"reason":reason,"symbol":"BTCUSDT","observed_at_ms":now-500,
        "opened_at_ms":now-7_200_000,"strategy_profile":TC2,
        "direction":"SHORT" if side == "BUY" else "LONG","signal_id":"divergent-quote",
        "trigger":trigger,"trigger_price_basis":"CONTRACT_PRICE","contract_price":crossed,"event":event}
    campaign = SimpleNamespace(symbol="BTCUSDT",side=side,position_qty=1.,average_entry_price=entry,
        state=CampaignState.TREND_ACTIVE,tags={"strategy_profile":TC2,"tc2_book_exit_proof":proof,
            "last_opposition_evidence":{"signal_id":"divergent-quote","event":event.copy()}})
    with pytest.raises(ReachedVerifiedQuantity if contract_triggered else RuntimeError,
                       match=None if contract_triggered else "TC2_EXIT_LIVE_CONTRACT_NO_LONGER_TRIGGERED"):
        runtime._exit_market(campaign,reason)
    assert "pending_exit_client_id" not in campaign.tags


@pytest.mark.parametrize("quote", [{}, {"symbol":"ETHUSDT","price":"100"},
    {"symbol":"BTCUSDT","price":"NaN"}, {"symbol":"BTCUSDT","price":"0"}])
def test_real_futures_client_never_relabels_missing_or_foreign_contract_quote(quote):
    from binance_futures_client import BinanceFuturesClient
    from binance_client import BinanceAPIError
    client = object.__new__(BinanceFuturesClient)
    client._request = lambda *args:quote
    with pytest.raises(BinanceAPIError):
        client.ticker_price("BTCUSDT")


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
def test_tc2_bar_stop_requires_known_opening_time_and_contiguous_h1(side):
    hour = 3_600_000
    bars = [SimpleNamespace(t=(i+1)*hour, h=105.+i, l=95.+i) for i in range(5)]
    old, mark = (80., 115.) if side == "LONG" else (120., 85.)
    args = (side, old, 100., None, mark, .1)
    assert plan_williams_stop(*args, hour, bars) is not None
    assert plan_williams_stop(*args, 0, bars) is None
    gapped = [SimpleNamespace(t=b.t+(hour if i>=2 else 0), h=b.h, l=b.l)
              for i,b in enumerate(bars)]
    assert plan_williams_stop(*args, hour, gapped) is None


@pytest.mark.parametrize("bad",["missing","stale","wrong_direction","wrong_book","untriggered","price_recovered"])
def test_tc2_opposite_close_cannot_reach_cancel_or_exchange_without_current_book_proof(exit_runtime,bad):
    import time
    now=int(time.time()*1000)
    runtime=exit_runtime
    calls=[]
    runtime.cancel_pending_entries=lambda *a:calls.append("cancel")
    runtime._position=lambda *a:calls.append("position")
    runtime.client=SimpleNamespace(time_offset_ms=0)
    proof={"reason":"OPPOSITE_WILLIAMS_LEVEL_TRIGGERED",
           "symbol":"BTCUSDT","observed_at_ms":now-1000,"opened_at_ms":now-2*3_600_000,
           "strategy_profile":TC2,"direction":"SHORT","signal_id":"wm1_short",
           "trigger":96.0,"trigger_price_basis":"CONTRACT_PRICE","contract_price":95.0,"event":{"state":"TRIGGERED","known_at_ms":now-60_000}}
    if bad=="stale":proof["observed_at_ms"]=now-130_000
    if bad=="wrong_direction":proof["direction"]="LONG"
    if bad=="wrong_book":proof["strategy_profile"]=NTD
    if bad=="untriggered":proof["event"]["state"]="WAITING"
    if bad=="price_recovered":proof["contract_price"]=99.0
    tags={"strategy_profile":TC2}
    if bad!="missing":
        tags["tc2_book_exit_proof"]=proof
        tags["last_opposition_evidence"]={"signal_id":"wm1_short","event":proof["event"].copy()}
    campaign=SimpleNamespace(symbol="BTCUSDT",side="BUY",state=CampaignState.ADD_ON_PENDING,tags=tags)
    with pytest.raises(RuntimeError,match="TC2_EXIT_BOOK"):
        runtime._exit_market(campaign,"OPPOSITE_WILLIAMS_LEVEL_TRIGGERED")
    assert calls==[]



@pytest.mark.parametrize("side", ["BUY", "SELL"])
@pytest.mark.parametrize("exchange_mark,expected", [
    (None, "TC2_EXIT_LIVE_CONTRACT_UNVERIFIED"),
    ("nan", "TC2_EXIT_LIVE_CONTRACT_UNVERIFIED"),
    ("wrong_symbol", "TC2_EXIT_LIVE_CONTRACT_UNVERIFIED"),
    ("price_recovered", "TC2_EXIT_LIVE_CONTRACT_NO_LONGER_TRIGGERED"),
    ("still_triggered", "VALIDATED_LIVE_MARK"),
])
def test_tc2_exit_rereads_exact_futures_contract_before_exit_intent(
        exit_runtime, side, exchange_mark, expected):
    """BOOK price trigger cannot authorize a later exit from an old contract quote.

    This test executes the real _exit_market preflight with an exchange
    observation; no pending order, EXIT_PENDING state or Binance POST is
    allowed if CONTRACT_PRICE has moved back across the trigger.
    """
    import time
    class ValidatedLiveMark(Exception):
        pass
    runtime=exit_runtime
    now=int(time.time()*1000)
    reason="OPPOSITE_WILLIAMS_LEVEL_TRIGGERED"
    level=96.0 if side=="BUY" else 104.0
    first_mark=95.0 if side=="BUY" else 105.0
    recovered=99.0 if side=="BUY" else 101.0
    calls=[]
    def mark(symbol):
        calls.append(("contract",symbol))
        if exchange_mark is None:
            raise OSError("Binance unavailable")
        if exchange_mark=="wrong_symbol":
            return {"symbol":"ETHUSDT","price":str(first_mark)}
        return {"symbol":"BTCUSDT","price":(
            "nan" if exchange_mark=="nan"
            else str(recovered if exchange_mark=="price_recovered" else first_mark))}
    runtime.client=SimpleNamespace(time_offset_ms=0,ticker_price=mark,
        mark_price=lambda symbol:{"symbol":symbol,"markPrice":str(first_mark)})
    live_entry=90.0 if side=="BUY" else 110.0
    runtime._position=lambda symbol: (calls.append(("position",symbol)), {"entryPrice":str(live_entry)})[1]
    runtime._signed_position_qty=lambda pos: 1.0 if side=="BUY" else -1.0
    runtime._set_state=lambda *args: calls.append(("state",*args))
    runtime._full_market_close_qty=lambda *args: (_ for _ in ()).throw(ValidatedLiveMark())
    proof={"reason":reason,"symbol":"BTCUSDT","observed_at_ms":now-1000,
           "opened_at_ms":now-2*3_600_000,"strategy_profile":TC2,
           "direction":"SHORT" if side=="BUY" else "LONG",
           "signal_id":"book_exit_case","trigger":level,"trigger_price_basis":"CONTRACT_PRICE","contract_price":first_mark,
           "event":{"state":"TRIGGERED","known_at_ms":now-60_000}}
    tags={"strategy_profile":TC2,"tc2_book_exit_proof":proof,
          "last_opposition_evidence":{"signal_id":"book_exit_case","event":proof["event"].copy()}}
    campaign=SimpleNamespace(symbol="BTCUSDT",side=side,position_qty=1.0,
                             average_entry_price=live_entry,
                             state=CampaignState.TREND_ACTIVE,tags=tags)
    if exchange_mark=="still_triggered":
        with pytest.raises(ValidatedLiveMark):
            runtime._exit_market(campaign,reason)
    else:
        with pytest.raises(RuntimeError,match=expected):
            runtime._exit_market(campaign,reason)
    assert ("contract","BTCUSDT") in calls
    assert campaign.state==CampaignState.TREND_ACTIVE
    assert "pending_exit_client_id" not in campaign.tags



@pytest.mark.parametrize("side,live_signed,recorded_qty,expected",[
    ("BUY",-1.0,1.0,"FUTURES_EXIT_LIVE_SIDE_MISMATCH"),
    ("SELL", 1.0,1.0,"FUTURES_EXIT_LIVE_SIDE_MISMATCH"),
    ("BUY", 1.4,1.0,"FUTURES_EXIT_LIVE_QUANTITY_OWNER_MISMATCH"),
    ("SELL",-1.4,1.0,"FUTURES_EXIT_LIVE_QUANTITY_OWNER_MISMATCH"),
    ("BUY",float("nan"),1.0,"FUTURES_EXIT_POSITION_QUANTITY_NOT_FINITE"),
])
def test_exit_never_claims_wrong_binance_signed_position(
        exit_runtime,side,live_signed,recorded_qty,expected):
    """No TC2/NTD/Course strategy close may mutate a different owner's exposure."""
    runtime=exit_runtime
    calls=[]
    runtime._position=lambda symbol: (calls.append("position"),object())[1]
    runtime._signed_position_qty=lambda obj: live_signed
    runtime._set_state=lambda symbol,state: calls.append("reconcile_required")
    runtime._full_market_close_qty=lambda *args: calls.append("close_qty")
    runtime.client=SimpleNamespace(time_offset_ms=0)
    campaign=SimpleNamespace(symbol="BTCUSDT",side=side,
        position_qty=recorded_qty,state=CampaignState.TREND_ACTIVE,
        tags={"strategy_profile":NTD})
    with pytest.raises(RuntimeError,match=expected):
        runtime._exit_market(campaign,"TEETH_CLOSE_EXIT")
    assert calls==["position","reconcile_required"]
    assert campaign.state==CampaignState.TREND_ACTIVE
    assert "pending_exit_client_id" not in campaign.tags



@pytest.mark.parametrize("side,entry,mark,buffer,expected", [
    ("BUY",100.0,95.0,.0035,"TC2_OPPOSITE_EXIT_NOT_PROFITABLE_AFTER_COST_RESERVE"),
    ("SELL",100.0,105.0,.0035,"TC2_OPPOSITE_EXIT_NOT_PROFITABLE_AFTER_COST_RESERVE"),
    ("BUY",100.0,100.34,.0035,"TC2_OPPOSITE_EXIT_NOT_PROFITABLE_AFTER_COST_RESERVE"),
    ("SELL",100.0,99.66,.0035,"TC2_OPPOSITE_EXIT_NOT_PROFITABLE_AFTER_COST_RESERVE"),
    ("BUY",100.0,100.36,.0035,None),
    ("SELL",100.0,99.64,.0035,None),
    ("BUY",100.0,float("nan"),.0035,"TC2_OPPOSITE_EXIT_PROFIT_EVIDENCE_UNVERIFIED"),
    ("SELL",0.0,98.0,.0035,"TC2_OPPOSITE_EXIT_PROFIT_EVIDENCE_UNVERIFIED"),
])
def test_tc2_contrary_wise_man_never_forces_a_losing_strategy_exit(
        side,entry,mark,buffer,expected):
    from williams_execution_policy import tc2_opposite_exit_profit_blocker
    assert tc2_opposite_exit_profit_blocker(side,entry,mark,buffer)==expected


@pytest.mark.parametrize("side,live_entry,live_mark,trigger",[
    ("BUY",100.0,95.0,96.0),("SELL",100.0,105.0,104.0),
])
def test_tc2_real_exit_door_keeps_stop_and_does_not_post_on_opposite_signal_at_loss(
        exit_runtime,side,live_entry,live_mark,trigger):
    """Same-bar evidence may be valid, but a red campaign stays exchange-protected."""
    import time
    now=int(time.time()*1000)
    runtime=exit_runtime
    calls=[]
    reason="OPPOSITE_WILLIAMS_LEVEL_TRIGGERED"
    runtime.client=SimpleNamespace(time_offset_ms=0,
        ticker_price=lambda symbol:{"symbol":symbol,"price":str(live_mark)},
        mark_price=lambda symbol: (calls.append(("mark",symbol)),
                                   {"symbol":symbol,"markPrice":str(live_mark)})[1])
    runtime._position=lambda symbol: (calls.append(("position",symbol)),
                                      {"entryPrice":str(live_entry)})[1]
    runtime._signed_position_qty=lambda pos: 1.0 if side=="BUY" else -1.0
    runtime._set_state=lambda *args: calls.append("state")
    runtime._full_market_close_qty=lambda *args: calls.append("exit_quantity")
    event={"state":"TRIGGERED","known_at_ms":now-60_000}
    proof={"reason":reason,"symbol":"BTCUSDT","observed_at_ms":now-1000,
        "opened_at_ms":now-2*3_600_000,"strategy_profile":TC2,
        "direction":"SHORT" if side=="BUY" else "LONG",
        "signal_id":"proof","trigger":trigger,"trigger_price_basis":"CONTRACT_PRICE","contract_price":live_mark,"event":event}
    tags={"strategy_profile":TC2,"tc2_book_exit_proof":proof,
          "last_opposition_evidence":{"signal_id":"proof","event":event.copy()}}
    campaign=SimpleNamespace(symbol="BTCUSDT",side=side,position_qty=1.0,
        average_entry_price=live_entry, state=CampaignState.TREND_ACTIVE,tags=tags)
    with pytest.raises(RuntimeError,match="TC2_OPPOSITE_EXIT_NOT_PROFITABLE_AFTER_COST_RESERVE"):
        runtime._exit_market(campaign,reason)
    assert calls==[("position","BTCUSDT"),("mark","BTCUSDT")]
    assert campaign.state==CampaignState.TREND_ACTIVE
    assert "pending_exit_client_id" not in campaign.tags



def test_tc2_valid_opposite_setup_does_not_cancel_unsettled_addition(exit_runtime):
    import time
    runtime=exit_runtime
    calls=[]
    runtime.cancel_pending_entries=lambda *a: calls.append("cancel")
    runtime._position=lambda *a: calls.append("position")
    now=int(time.time()*1000)
    event={"state":"TRIGGERED","known_at_ms":now-60_000}
    proof={"reason":"OPPOSITE_WILLIAMS_LEVEL_TRIGGERED","symbol":"BTCUSDT",
           "observed_at_ms":now-1000,"opened_at_ms":now-2*3_600_000,
           "strategy_profile":TC2,"direction":"SHORT","signal_id":"book_short",
           "trigger":96.0,"trigger_price_basis":"CONTRACT_PRICE","contract_price":95.0,"event":event}
    tags={"strategy_profile":TC2,"tc2_book_exit_proof":proof,
          "last_opposition_evidence":{"signal_id":"book_short","event":event.copy()}}
    campaign=SimpleNamespace(symbol="BTCUSDT",side="BUY",
        state=CampaignState.ADD_ON_PENDING,tags=tags)
    with pytest.raises(RuntimeError,match="TC2_OPPOSITE_EXIT_PENDING_ADDITION_NOT_RECONCILED"):
        runtime._exit_market(campaign,"OPPOSITE_WILLIAMS_LEVEL_TRIGGERED")
    assert calls==[]
    assert campaign.state==CampaignState.ADD_ON_PENDING



@pytest.mark.parametrize("symbol,side,entry,exit_mark",[
    ("ETHUSDT","SELL",2429.20,2441.01),
    ("SOLUSDT","SELL",105.57,105.89),
    ("XRPUSDT","SELL",1.31270,1.31180),
])
def test_19_oct_2026_short_campaigns_cannot_be_closed_at_net_loss_by_opposite_wm(
        symbol,side,entry,exit_mark):
    """Historical diagnostic 4.34: 19 Oct SOL and XRP had opposite EXIT intents.

    ETH's EXCHANGE_RECONCILED_CLOSE remains unclassified (possibly a real SL)
    and must not be equated with a discretionary WM exit.
    """
    from williams_execution_policy import tc2_opposite_exit_profit_blocker
    assert tc2_opposite_exit_profit_blocker(side,entry,exit_mark)==(
        "TC2_OPPOSITE_EXIT_NOT_PROFITABLE_AFTER_COST_RESERVE")
