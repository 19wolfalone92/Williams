"""Independent market passports use their own real closed bar durations."""
from williams_market_reading import market_reading, FRAME_MS
from williams_wave_hierarchy import WaveBar


def test_all_frames_are_observations_without_order_authority():
    for frame in ("1d", "6h", "4h", "1h", "15m"):
        ms=FRAME_MS[frame]
        bars=[WaveBar((i+1)*ms, 101+i*.1, 102+i*.1,100+i*.1,101.5+i*.1,100)
              for i in range(46)]
        phase={"frames":[{"frame":frame,"state":"UNRESOLVED","hypotheses":[]}]}
        result=market_reading(bars,phase,frame)
        assert result["state"]=="OBSERVED"
        assert result["frame"]==frame
        assert result["as_of_close_ms"]==bars[-1].t+ms
        assert result["wave_phase"]=="UNRESOLVED"
        assert not result["can_place_orders"] and not result["can_close"]
        gapped=list(bars)
        b=gapped[21]
        gapped[21]=WaveBar(b.t+ms//2,b.o,b.h,b.l,b.c,b.v)
        assert market_reading(gapped,phase,frame)["state"]=="HISTORY_UNVERIFIED"


def test_unsupported_frame_cannot_trade():
    result=market_reading([],{},"2d")
    assert result["state"]=="UNSUPPORTED_FRAME"
    assert not result["can_place_orders"]
