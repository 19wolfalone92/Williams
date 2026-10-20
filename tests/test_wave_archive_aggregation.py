"""Replay may aggregate genuine candles but must never manufacture a missing one."""
from tools.replay_wave_archive import aggregate_closed_groups


def test_aggregation_requires_every_constituent_and_preserves_real_extrema():
    rows = [[i*900_000, 10+i, 12+i, 9+i, 11+i, 2.0] for i in range(8)]
    hourly = aggregate_closed_groups(rows, 900_000, 3_600_000)
    assert hourly == [[0, 10.0, 15.0, 9.0, 14.0, 8.0],
                      [3_600_000, 14.0, 19.0, 13.0, 18.0, 8.0]]
    assert aggregate_closed_groups(rows[:2]+rows[3:], 900_000, 3_600_000) == hourly[1:]
    assert aggregate_closed_groups(rows[:4]+[rows[1]], 900_000, 3_600_000) == []


def test_partial_first_and_last_higher_candles_remain_absent():
    rows = [[i*900_000, 10, 12, 9, 11, 2.0] for i in range(1, 7)]
    assert aggregate_closed_groups(rows, 900_000, 3_600_000) == []
