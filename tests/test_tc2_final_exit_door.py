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
            "reason":reason, "observed_at_ms":now-1000,
            "opened_at_ms":now-2*3_600_000, "strategy_profile":TC2,
            "direction":"SHORT","signal_id":"book_opposite_1",
            "trigger":96.0,"mark":95.0,
            "event":{"state":"TRIGGERED","known_at_ms":now-60_000}}
    campaign = SimpleNamespace(symbol="BTCUSDT",side="BUY",state=CampaignState.TREND_ACTIVE,
                               tags=tags)
    with pytest.raises(ReachedLivePreflight):
        runtime._exit_market(campaign, reason, risk_emergency=emergency)


def test_unknown_edition_cannot_authorize_a_strategy_exit():
    assert market_exit_blocker("UNKNOWN", "STRATEGY_EXIT") == "EXIT_STRATEGY_OWNER_UNVERIFIED"


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
           "observed_at_ms":now-1000,"opened_at_ms":now-2*3_600_000,
           "strategy_profile":TC2,"direction":"SHORT","signal_id":"wm1_short",
           "trigger":96.0,"mark":95.0,"event":{"state":"TRIGGERED","known_at_ms":now-60_000}}
    if bad=="stale":proof["observed_at_ms"]=now-130_000
    if bad=="wrong_direction":proof["direction"]="LONG"
    if bad=="wrong_book":proof["strategy_profile"]=NTD
    if bad=="untriggered":proof["event"]["state"]="WAITING"
    if bad=="price_recovered":proof["mark"]=99.0
    tags={"strategy_profile":TC2}
    if bad!="missing":tags["tc2_book_exit_proof"]=proof
    campaign=SimpleNamespace(symbol="BTCUSDT",side="BUY",state=CampaignState.ADD_ON_PENDING,tags=tags)
    with pytest.raises(RuntimeError,match="TC2_EXIT_BOOK"):
        runtime._exit_market(campaign,"OPPOSITE_WILLIAMS_LEVEL_TRIGGERED")
    assert calls==[]
