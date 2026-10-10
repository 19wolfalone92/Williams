import pandas as pd

from strategy import calculate_indicators, config_from_env


def frame(prices, volumes=None):
    volumes = volumes or [1000.0] * len(prices)
    rows=[]
    for p,v in zip(prices,volumes):
        rows.append({
            'open': p - 0.2,
            'high': p + 0.5,
            'low': p - 0.5,
            'close': p,
            'volume': v,
        })
    return pd.DataFrame(rows)


def test_profitunity_mfi_proxy_windows():
    # Alternating range/volume values create all four relative states.
    df = frame([100,101,100.5,101.5,101], [1000,2000,1000,2000,1000])
    # Make the range changes explicit so MFI direction is controlled.
    df.loc[1, 'high'], df.loc[1, 'low'] = 102.0, 100.0
    df.loc[2, 'high'], df.loc[2, 'low'] = 101.0, 100.5
    df.loc[3, 'high'], df.loc[3, 'low'] = 103.0, 101.0
    df.loc[4, 'high'], df.loc[4, 'low'] = 101.5, 100.5
    ind = calculate_indicators(df, config_from_env())
    assert 'mfi_proxy' in ind
    assert set(ind['profitunity_window'].dropna().unique()) <= {'GREEN','FADING','FAKE','SQUAT','STABLE'}
    assert 'squatting_bar' in ind


def test_ao_green_streak_drives_super_ao():
    # A monotonic rising series produces consecutive green AO bars after the
    # 34-bar warmup; the three-bar Super AO condition must then become true.
    prices = [100 + (i * 0.05) ** 2 for i in range(80)]
    ind = calculate_indicators(frame(prices), config_from_env())
    assert int(ind['ao_green_streak'].iloc[-1]) >= 3
    assert bool(ind['super_ao_long'].iloc[-1]) is True
    assert bool(ind['long_ao_positive'].iloc[-1]) is True


def test_wise_men_columns_are_present_and_signal_is_conservative():
    prices = [120 - i * 0.5 for i in range(60)] + [90 + i * 1.2 for i in range(30)]
    ind = calculate_indicators(frame(prices), config_from_env())
    required = [
        'bullish_reversal_bar', 'long_reversal_signal',
        'super_ao_long', 'long_super_ao_signal',
        'long_fractal_signal', 'long_wise_man_count',
        'long_signal_family', 'long_signal',
    ]
    for col in required:
        assert col in ind.columns
    assert ind['long_wise_man_count'].astype(int).between(0, 3).all()


def test_config_defaults_keep_countertrend_disabled():
    cfg = config_from_env({})
    assert cfg['super_ao_bars'] == 3
    assert cfg['min_wise_men_confirmations'] == 2
    assert cfg['allow_countertrend_wise_man'] is False


def test_momentum_fading_and_volume_diagnostics_are_boolean():
    prices = [100 + (i * 0.05) ** 2 for i in range(80)]
    ind = calculate_indicators(frame(prices), config_from_env())
    assert ind["ao_momentum_falling"].dtype == bool
    assert ind["squatting_bar"].dtype == bool
    assert ind["profitunity_window"].iloc[-1] in {"GREEN", "FADING", "FAKE", "SQUAT", "STABLE"}

def test_reversal_bar_needs_followup_extreme_breakout():
    import pandas as pd
    from strategy import calculate_indicators, config_from_env

    idx = pd.date_range('2026-01-01', periods=90, freq='h')
    close = pd.Series([100 + i * 0.05 for i in range(90)], index=idx)
    high = close + 1.0
    low = close - 1.0
    open_ = close.copy()
    volume = pd.Series(1000.0, index=idx)
    # Create a bullish reversal bar, then a later breakout beyond its high.
    low.iloc[70] = low.iloc[69] - 2.0
    high.iloc[70] = close.iloc[70] + 1.0
    close.iloc[70] = low.iloc[70] + 0.75 * (high.iloc[70] - low.iloc[70])
    high.iloc[71] = high.iloc[70] + 0.2
    close.iloc[71] = high.iloc[70] + 0.1

    df = pd.DataFrame({'open': open_, 'high': high, 'low': low, 'close': close, 'volume': volume})
    out = calculate_indicators(df, config_from_env())
    assert bool(out['bullish_reversal_bar'].iloc[70]) is True
    assert bool(out['long_wise_reversal_entry'].iloc[70]) is False


def test_wm2_long_signal_is_independent_of_fractal_outside_flag():
    prices = [100 + (i * 0.05) ** 2 for i in range(80)]
    ind = calculate_indicators(frame(prices), config_from_env())
    third = ind["ao_green_streak"].eq(3)
    assert third.any()
    assert ind.loc[third, "long_super_ao_signal"].all()
    assert (~ind["long_fractal_outside"].shift(1).fillna(False).astype(bool) & third).any()


def test_wm2_short_signal_is_independent_of_fractal_outside_flag():
    prices = [200 - (i * 0.05) ** 2 for i in range(80)]
    ind = calculate_indicators(frame(prices), config_from_env())
    third = ind["ao_red_streak"].eq(3)
    assert third.any()
    assert ind.loc[third, "short_super_ao_signal"].all()
    assert (~ind["short_fractal_outside"].shift(1).fillna(False).astype(bool) & third).any()


def test_live_futures_parameter_guard_rejects_noncanonical_book_constants():
    from strategy import canonical_williams_parameter_blockers, config_from_env

    assert canonical_williams_parameter_blockers(config_from_env({})) == []
    changed = config_from_env({"JAW_SHIFT": "7", "FRACTAL_RIGHT": "3", "SUPER_AO_BARS": "4"})
    blockers = canonical_williams_parameter_blockers(changed)
    assert len(blockers) == 3
    assert any("jaw_shift=7" in item for item in blockers)
    assert any("fractal_right=3" in item for item in blockers)
    assert any("super_ao_bars=4" in item for item in blockers)
