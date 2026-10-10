import logging
import os
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass, field, replace
from typing import Dict, List, Optional, Tuple

import pandas as pd

from data import fetch_klines
from campaign_model import SignalRole
from strategy import calculate_indicators, config_from_env
from wave_engine import DIRECTION_NEUTRAL, MultiTimeframeWaveEngine
from feature_store import FeatureStore, build_market_feature_vector
from ai_shadow import ShadowDecisionEngine, journal_shadow_decision
from williams_signals import extract_long_signal_specs, extract_short_signal_specs
from shadow_execution import ShadowExecutionSimulator


log = logging.getLogger("williams-scanner")


def _normalize_interval(value):
    text = str(value or "").strip()
    return "1M" if text == "1M" else text.lower()


@dataclass
class Candidate:
    symbol: str
    score: float
    signal: bool

    # Strategy/setup information
    setup_score: float
    signal_strength: float
    breakout_distance_pct: float

    # Risk / market information
    risk_pct: float
    risk_reward: float
    atr_pct: float
    spread_pct: float

    htf_confirmed: bool

    # Human-readable state
    setup_state: str
    reason: str = ""
    wise_man_count: int = 0
    signal_family: str = "NONE"

    # Multi-timeframe wave context. `base_score` is the legacy Williams score;
    # `score` includes only a bounded wave adjustment and never changes signal.
    base_score: float = 0.0
    wave_score: float = 50.0
    wave_adjustment: float = 0.0
    wave_position: int = 0
    wave_phase: str = "UNKNOWN"
    wave_direction: str = DIRECTION_NEUTRAL
    wave_confidence: float = 0.0
    wave_exhaustion_risk: float = 0.0
    nested_w3: bool = False
    nested_w3_parent_w5: bool = False
    wave_path: str = ""
    wave_reason: str = ""
    wave_context: dict = field(default_factory=dict)
    wave_entry_allowed: bool = True
    wave_block_reason: str = ""
    wave_primary_count: str = ""
    wave_alternative_count: str = ""
    wave_abc_phase: str = ""
    wave_entry_score: float = 0.0
    wave_invalidation_price: float = 0.0
    wave_target_zone_low: float = 0.0
    wave_target_zone_high: float = 0.0
    wave_magic_bullets_count: int = 0
    wave_terminal_fractal: bool = False
    wave_scenario_primary: str = ""
    wave_scenario_alternative: str = ""
    wave_operative_interval: str = ""
    # Campaign entry contract. These fields describe a conditional entry,
    # not an instruction to submit a MARKET order.
    campaign_ready: bool = False
    direction: str = ""
    entry_signal_type: str = ""
    entry_trigger_price: float = 0.0
    entry_protective_reference: float = 0.0
    entry_signal_time_ms: int = 0
    campaign_signal_specs: list = field(default_factory=list)
    # Quant layer is advisory: it can rank candidates but never creates a signal.
    quant_rank_adjustment: float = 0.0
    quant_score: float = 0.0
    regime: str = "UNKNOWN"
    regime_score: float = 0.0
    obi: float = 0.0
    trade_flow_imbalance: float = 0.0
    dollar_bar_rate: float = 0.0
    volume_bar_rate: float = 0.0
    quant_features: dict = field(default_factory=dict)
    xai_factors: dict = field(default_factory=dict)
    shadow_intent: dict = field(default_factory=dict)

    def __post_init__(self):
        # Existing tests/integrations may construct Candidate(score=...) before
        # the Wave Engine fields existed. Treat that legacy score as base_score
        # unless a caller supplied a non-zero base_score explicitly.
        if self.base_score == 0.0 and self.score != 0.0:
            self.base_score = float(self.score)

    def to_dict(self):
        return asdict(self)


class MarketScanner:
    """Find and rank long setups across the Binance Spot USDT universe.

    Safety boundary:
    - this component never places orders;
    - `Candidate.signal` remains the existing strict Williams entry signal;
    - Wave Engine only changes ranking/context and cannot create a BUY signal;
    - the Trader/RiskEngine remain the final execution gates.

    Scan strategy:
    1. Discover valid Spot/USDT symbols (or use an explicit configured list).
    2. Run the existing Williams setup on the universe.
    3. Run the more expensive MTF Wave Engine for every strict signal and for a
       limited number of best watch candidates.
    4. Re-rank using the legacy score plus a bounded wave adjustment.
    """

    def __init__(self, client, symbols=None, interval=None):
        self.client = client

        self.interval = _normalize_interval(interval or os.getenv("INTERVAL", "1h"))

        # `None` means resolve the configured/default universe. An explicit []
        # remains a genuine empty test universe and does not fall back to all.
        self.explicit_symbols = symbols is not None
        self.symbols = (
            [str(x).upper().strip() for x in symbols if str(x).strip()]
            if symbols is not None
            else self._load_symbols()
        )

        self.scan_all_usdt = (
            os.getenv("SCAN_ALL_USDT", "true").lower() == "true"
        )  # dynamically discover Spot/USDT pairs
        self.scan_max_symbols = max(0, int(os.getenv("SCAN_MAX_SYMBOLS", "0")))
        self.exclude_leveraged_tokens = (
            os.getenv("EXCLUDE_LEVERAGED_TOKENS", "true").lower() == "true"
        )
        self.scan_workers = max(1, int(os.getenv("SCAN_WORKERS", "4")))
        self.liquidity_preselect = max(
            0,
            int(os.getenv("LIQUIDITY_PRESELECT", "0")),
        )
        self.scan_kline_limit = max(120, int(os.getenv("SCAN_KLINE_LIMIT", "220")))
        self.wave_scan_workers = max(1, int(os.getenv("WAVE_SCAN_WORKERS", "6")))
        self.kline_cache_seconds = max(5, int(os.getenv("KLINE_CACHE_SECONDS", "45")))
        self._kline_cache = {}
        self._spread_map = {}
        self.universe_cache_seconds = max(30, int(os.getenv("SCAN_UNIVERSE_CACHE_SECONDS", "300")))
        self.wave_top_n = max(0, int(os.getenv("WAVE_SCAN_TOP_N", "10")))
        self.max_wave_exhaustion_for_entry = max(0.0, min(100.0, float(os.getenv("MAX_WAVE_EXHAUSTION_FOR_ENTRY", "80"))))
        self._universe_cache: List[str] = []
        self._universe_metadata: Dict[str, dict] = {}
        self._universe_cache_time = 0.0

        self.atr_period = int(os.getenv("ATR_PERIOD", "14"))
        self.max_atr_pct = float(os.getenv("MAX_ATR_PCT", "0.08"))
        self.max_spread_pct = float(os.getenv("MAX_SPREAD_PCT", "0.0015"))
        self.require_htf_confirmation = (
            os.getenv("REQUIRE_HTF_CONFIRMATION", "true").lower() == "true"
        )
        self.min_risk_reward = float(os.getenv("MIN_RISK_REWARD", "1.5"))
        self.stop_pct = float(os.getenv("STOP_LOSS_PCT", "0.02"))
        self.target_pct = float(os.getenv("TAKE_PROFIT_PCT", "0.04"))
        self.min_rr = self.min_risk_reward
        self.htf_interval = _normalize_interval(os.getenv("HTF_INTERVAL", "4h"))

        self.wave_engine = MultiTimeframeWaveEngine(
            self.client,
            base_interval=self.interval,
            include_micro=(
                os.getenv("WAVE_MICRO_ENABLED", "false").lower() == "true"
            ),
        )
        self.quant_enabled = os.getenv("FEATURE_STORE_ENABLED", "true").lower() == "true"
        self.quant_ranking_enabled = os.getenv(
            "QUANT_RANKING_ENABLED",
            "true" if os.getenv("DRY_RUN", "true").lower() == "true" else "false",
        ).lower() == "true"
        self.feature_store = FeatureStore() if self.quant_enabled else None
        self.shadow_engine = ShadowDecisionEngine()
        self.ai_shadow_enabled = os.getenv("AI_SHADOW_ENABLED", "true").lower() == "true"
        self.shadow_execution_enabled = os.getenv("SHADOW_EXECUTION_ENABLED", "true").lower() == "true"
        self.shadow_execution = ShadowExecutionSimulator(
            fee_pct=float(os.getenv("SHADOW_FEE_PCT", "0.001")),
            tick_pct=float(os.getenv("SHADOW_TICK_PCT", "0.0001")),
        )

    def _load_symbols(self):
        raw = os.getenv("SCAN_SYMBOLS", "").strip()
        # An explicit AUTO/ALL/* selection means dynamic Spot/USDT discovery.
        # Otherwise an explicit list remains supported for deterministic tests.
        if raw.upper() in {"ALL", "AUTO", "*"}:
            return []
        if raw:
            return [str(x).upper().strip() for x in raw.split(",") if str(x).strip()]
        return []
    def _atr(df, period):
        prev = df["close"].shift(1)
        tr = pd.concat(
            [
                df["high"] - df["low"],
                (df["high"] - prev).abs(),
                (df["low"] - prev).abs(),
            ],
            axis=1,
        ).max(axis=1)
        return float(tr.rolling(period).mean().iloc[-1])

    def _refresh_spreads(self, symbols):
        try:
            rows = self.client.book_ticker()
            self._spread_map = {
                str(x.get("symbol","")).upper(): (
                    (float(x["askPrice"]) - float(x["bidPrice"])) /
                    ((float(x["askPrice"]) + float(x["bidPrice"])) / 2.0)
                )
                for x in rows
                if float(x.get("bidPrice",0) or 0) > 0 and float(x.get("askPrice",0) or 0) > 0
            }
        except Exception as exc:
            log.debug("Bulk spread refresh unavailable: %s", exc)

    def _spread(self, symbol):
        cached = self._spread_map.get(str(symbol).upper())
        if cached is not None:
            return float(cached)
        book = self.client.book_ticker(symbol)
        bid = float(book["bidPrice"])
        ask = float(book["askPrice"])
        mid = (bid + ask) / 2
        if mid <= 0:
            return 1.0
        return (ask - bid) / mid

    def _symbol_is_valid(self, symbol, metadata=None):
        if metadata is None:
            info = self.client.exchange_info(symbol)
            symbols = info.get("symbols", [])
            if len(symbols) != 1:
                return False
            item = symbols[0]
        else:
            item = metadata.get(symbol)
            if item is None:
                return False

        if item.get("status") != "TRADING":
            return False
        if item.get("quoteAsset") != "USDT":
            return False

        # USDⓈ-M Futures exchangeInfo uses contractType/marginAsset and does
        # not promise Spot permissions. Do not apply the Spot gate to Futures.
        if bool(getattr(self.client, "is_usdm_futures", False)):
            return (
                str(item.get("contractType", "")).upper() == "PERPETUAL"
                and str(item.get("marginAsset", "USDT")).upper() == "USDT"
            )

        if item.get("isSpotTradingAllowed") is False:
            return False

        permissions = item.get("permissions")
        if isinstance(permissions, list) and permissions and "SPOT" not in permissions:
            return False

        if self.exclude_leveraged_tokens:
            base = str(item.get("baseAsset", "")).upper()
            if base.endswith(("UP", "DOWN", "BULL", "BEAR")):
                return False

        return True

    def _discover_usdt_symbols(self):
        if not self.scan_all_usdt:
            return list(self.symbols)

        import time

        now = time.monotonic()
        if self._universe_cache and now - self._universe_cache_time < self.universe_cache_seconds:
            return list(self._universe_cache)

        payload = self.client.exchange_info()
        items = payload.get("symbols", [])
        result = []
        metadata = {}

        for item in items:
            symbol = str(item.get("symbol", "")).upper()
            if not symbol or item.get("quoteAsset") != "USDT":
                continue
            if self._symbol_is_valid(symbol, {symbol: item}):
                result.append(symbol)
                metadata[symbol] = item

        result = sorted(set(result))
        # Two-stage universe selection: exchangeInfo gives correctness; the
        # bulk 24h ticker selects the most liquid symbols for expensive candle
        # analysis. This keeps broad coverage without spending latency on
        # inactive pairs.
        if self.liquidity_preselect:
            try:
                tickers = self.client.ticker_24hr()
                volume = {
                    str(x.get("symbol", "")).upper(): float(x.get("quoteVolume", 0) or 0)
                    for x in tickers if isinstance(x, dict)
                }
                result.sort(key=lambda s: volume.get(s, 0.0), reverse=True)
                result = result[: self.liquidity_preselect]
            except Exception as exc:
                log.warning("Liquidity preselect unavailable: %s", exc)

        if self.scan_max_symbols:
            result = result[: self.scan_max_symbols]

        self._universe_cache = result
        self._universe_metadata = {symbol: metadata[symbol] for symbol in result if symbol in metadata}
        self._universe_cache_time = now
        self.symbols = list(result)
        return result

    def _resolve_symbols(self):
        if self.explicit_symbols:
            return list(self.symbols)

        configured = self._load_symbols()
        if configured:
            self.symbols = list(configured)
            return list(configured)

        if self.scan_all_usdt:
            return self._discover_usdt_symbols()

        return list(self.symbols)

    def _htf_confirmation(self, symbol, direction="LONG"):
        if not self.require_htf_confirmation:
            return True

        direction = str(direction or "LONG").upper()
        if direction not in {"LONG", "SHORT"}:
            return False

        htf = fetch_klines(
            self.client,
            symbol,
            self.htf_interval,
            limit=160,
        )
        # A signal is evaluated only on a fully closed candle.
        if len(htf) > 1:
            htf = htf.iloc[:-1].copy()
        if len(htf) < 80:
            return False

        ind = calculate_indicators(
            htf,
            config_from_env(),
        )
        last = ind.iloc[-1]
        ao = float(last.get("ao", 0) or 0)
        if direction == "LONG":
            return bool(last.get("bullish_alligator", False)) and ao > 0
        return bool(last.get("bearish_alligator", False)) and ao < 0

    def _setup_state(self, last, direction="LONG"):
        direction = str(direction or "LONG").upper()
        prefix = "short" if direction == "SHORT" else "long"
        strict_signal = bool(last.get(f"{prefix}_signal", False))
        if strict_signal:
            return "STRONG_SIGNAL"

        directional = bool(
            last.get("short_bearish" if prefix == "short" else "long_bullish", False)
        )
        awake = bool(last.get(f"{prefix}_awake", False))
        ao = bool(
            last.get("short_ao_negative" if prefix == "short" else "long_ao_positive", False)
        )
        ac = bool(
            last.get("short_ac_negative" if prefix == "short" else "long_ac_positive", False)
        )
        fractal = bool(
            last.get("short_fractal_ready" if prefix == "short" else "long_fractal_ready", False)
        )

        if directional and awake and ao and ac and fractal:
            return "SETUP_READY"
        if directional or (ao and ac) or (directional and awake):
            return "WATCHING"
        return "NONE"

    @staticmethod
    def _clamp(value, low, high):
        return max(low, min(high, value))

    def _analyse_base(self, symbol, metadata=None) -> Optional[Tuple[Candidate, pd.DataFrame]]:
        symbol = str(symbol).upper()
        try:
            if not self._symbol_is_valid(symbol, metadata):
                return None

            import time
            cached = self._kline_cache.get(symbol)
            if cached and time.monotonic() - cached[0] < self.kline_cache_seconds:
                df = cached[1].copy()
            else:
                df = fetch_klines(self.client, symbol, self.interval, limit=self.scan_kline_limit)
                self._kline_cache[symbol] = (time.monotonic(), df.copy())
            if len(df) < 100:
                return None

            closed = df.iloc[:-1].copy()
            if len(closed) < 100:
                return None

            indicators = calculate_indicators(closed, config_from_env())
            last = indicators.iloc[-1]

            if metadata is not None and symbol in metadata:
                filters = {
                    f.get("filterType"): f
                    for f in metadata[symbol].get("filters", [])
                    if isinstance(f, dict)
                }
            else:
                info = self.client.exchange_info(symbol)
                rows = info.get("symbols", [])
                filters = {
                    f.get("filterType"): f
                    for f in (rows[0].get("filters", []) if rows else [])
                    if isinstance(f, dict)
                }
            tick_size = float(
                (filters.get("PRICE_FILTER") or {}).get("tickSize", "0") or 0.0
            )
            if tick_size <= 0:
                return None

            long_specs = extract_long_signal_specs(
                symbol,
                indicators,
                timeframe=self.interval,
                tick_size=tick_size,
                htf_confirmed=False,
            )
            short_specs = extract_short_signal_specs(
                symbol,
                indicators,
                timeframe=self.interval,
                tick_size=tick_size,
                htf_confirmed=False,
            )
            now_ms = int(time.time() * 1000)
            campaign_specs = sorted(
                (
                    spec for spec in long_specs + short_specs
                    if int(spec.expires_at_ms or 0) > now_ms
                ),
                key=lambda s: (
                    int(getattr(s, "confirmation_time_ms", 0) or s.signal_bar_time_ms),
                    s.created_at_ms,
                    s.direction,
                    s.signal_type.value,
                ),
            )
            # Signal extraction keeps WM2/WM3 tagged ADD_ON for an existing
            # campaign. If no campaign exists, the runtime may promote the first
            # valid Wise-Man signal to the initial entry, matching the campaign
            # contract: whichever valid Wise Man appears first starts the campaign.
            initial_entry_specs = [
                spec for spec in campaign_specs if spec.role == SignalRole.ENTRY
            ]
            primary_signal_spec = campaign_specs[0] if campaign_specs else None
            # Pick the active direction from the earliest valid signal.
            # If no trigger exists yet, surface the stronger directional watch
            # state without treating either watch state as an entry command.
            if campaign_specs:
                primary_direction = str(campaign_specs[0].direction).upper()
            else:
                long_state = self._setup_state(last, "LONG")
                short_state = self._setup_state(last, "SHORT")
                long_rank = {"NONE": 0, "WATCHING": 1, "SETUP_READY": 2, "STRONG_SIGNAL": 3}
                short_rank = {"NONE": 0, "WATCHING": 1, "SETUP_READY": 2, "STRONG_SIGNAL": 3}
                primary_direction = (
                    "SHORT"
                    if short_rank.get(short_state, 0) > long_rank.get(long_state, 0)
                    else "LONG"
                )
            setup_state = self._setup_state(last, primary_direction)
            if setup_state == "NONE" and not campaign_specs:
                return None

            price = float(last["close"])
            if price <= 0:
                return None

            atr = self._atr(closed, self.atr_period)
            if atr <= 0:
                return None
            atr_pct = atr / price
            if atr_pct > self.max_atr_pct:
                return None

            spread_pct = self._spread(symbol)
            if spread_pct > self.max_spread_pct:
                return None

            rr = self.target_pct / max(self.stop_pct, 1e-9)
            if rr < self.min_rr:
                return None

            legacy_strict_signal = bool(
                last.get("short_signal" if primary_direction == "SHORT" else "long_signal", False)
            )
            campaign_signal = bool(campaign_specs)
            setup_score = float(
                last.get("short_setup_score" if primary_direction == "SHORT" else "long_setup_score", 0.0)
                or 0.0
            )
            breakout_distance_pct = float(
                last.get(
                    "short_breakout_distance_pct" if primary_direction == "SHORT" else "long_breakout_distance_pct",
                    0.0,
                )
                or 0.0
            )

            if campaign_signal:
                signal_strength = 1.0
            elif setup_state == "SETUP_READY":
                signal_strength = 0.8
            else:
                signal_strength = 0.5

            risk_pct = self.stop_pct * 100.0
            risk_score = self._clamp(
                20.0 * (0.02 / max(self.stop_pct, 0.0001)),
                0.0,
                20.0,
            )
            rr_score = min(20.0, 20.0 * (rr / 3.0))
            atr_score = max(
                0.0,
                10.0 * (1.0 - atr_pct / max(self.max_atr_pct, 1e-9)),
            )
            spread_score = max(
                0.0,
                5.0 * (1.0 - spread_pct / max(self.max_spread_pct, 1e-9)),
            )
            strategy_score = 30.0 * (setup_score / 100.0)
            breakout_bonus = 15.0 if legacy_strict_signal else 0.0

            base_score = self._clamp(
                strategy_score
                + risk_score
                + rr_score
                + atr_score
                + spread_score
                + breakout_bonus,
                0.0,
                100.0,
            )

            if campaign_signal:
                reason = "Williams campaign signal detected; conditional entry candidate"
            elif setup_state == "SETUP_READY":
                reason = f"{primary_direction.lower()} setup ready; waiting for valid price trigger"
            else:
                reason = f"{primary_direction.lower()} setup being monitored"

            candidate = Candidate(
                symbol=symbol,
                score=round(base_score, 2),
                signal=campaign_signal,
                setup_score=round(setup_score, 2),
                signal_strength=round(signal_strength, 3),
                breakout_distance_pct=round(breakout_distance_pct, 4),
                risk_pct=round(risk_pct, 4),
                risk_reward=round(rr, 3),
                atr_pct=round(atr_pct, 6),
                spread_pct=round(spread_pct, 6),
                # This is populated authoritatively by Wave Engine. Keep it
                # false here rather than performing another duplicate HTF call.
                htf_confirmed=False,
                setup_state=setup_state,
                reason=reason,
                wise_man_count=int(
                    last.get("short_wise_man_count" if primary_direction == "SHORT" else "long_wise_man_count", 0)
                    or 0
                ),
                signal_family=str(
                    last.get("short_signal_family" if primary_direction == "SHORT" else "long_signal_family", "NONE")
                    or "NONE"
                ),
                base_score=round(base_score, 2),
                campaign_ready=campaign_signal,
                direction=(
                    str(getattr(primary_signal_spec, "direction", "") or "").upper()
                    if primary_signal_spec is not None else ""
                ),
                entry_signal_type=(
                    primary_signal_spec.signal_type.value if primary_signal_spec is not None else ""
                ),
                entry_trigger_price=(
                    float(primary_signal_spec.trigger_price) if primary_signal_spec is not None else 0.0
                ),
                entry_protective_reference=(
                    float(primary_signal_spec.protective_reference) if primary_signal_spec is not None else 0.0
                ),
                entry_signal_time_ms=(
                    int(primary_signal_spec.signal_bar_time_ms) if primary_signal_spec is not None else 0
                ),
                campaign_signal_specs=[s.to_dict() for s in campaign_specs],
            )
            return candidate, closed

        except Exception as exc:
            log.warning("Scanner skipped %s: %s", symbol, exc)
            return None

    def analyse(self, symbol) -> Optional[Candidate]:
        """Backward-compatible single-symbol analysis entry point."""
        result = self._analyse_base(symbol)
        if result is None:
            return None
        candidate, closed = result
        try:
            enriched = self._apply_wave(candidate, closed)
            if candidate.signal and self.require_htf_confirmation and not enriched.htf_confirmed:
                return None
            if candidate.signal and not enriched.wave_entry_allowed:
                return None
            return enriched
        except Exception as exc:
            log.warning("Wave analysis unavailable for %s: %s", candidate.symbol, exc)
            if candidate.signal and os.getenv("NO_TRADE_WHEN_UNCERTAIN", "true").lower() == "true":
                return None
            if candidate.signal and self.require_htf_confirmation:
                if not self._htf_confirmation(candidate.symbol):
                    return None
            return replace(
                candidate,
                wave_reason=f"Wave analysis unavailable: {type(exc).__name__}: {exc}",
            )

    def _apply_wave(self, candidate: Candidate, closed: pd.DataFrame) -> Candidate:
        report = self.wave_engine.analyse(
            candidate.symbol,
            cache={self.interval: closed},
        )

        setup = report.frames.get(self.interval)
        wave_direction = setup.direction if setup else report.overall_direction
        wave_position = int(setup.position if setup else report.setup_position)
        wave_phase = setup.phase if setup else report.setup_phase
        wave_confidence = float(setup.confidence if setup else 0.0)

        # The adjustment is deliberately bounded. At wave_score=50 the legacy
        # score is unchanged; the maximum impact is +/-10 points.
        wave_adjustment = self._clamp(
            (float(report.wave_score) - 50.0) * 0.20,
            -10.0,
            10.0,
        )

        # Quant layer: one immutable feature vector combines OHLCV-derived bars,
        # L2, trade flow, Williams indicators and MTF Wave context.
        quant_adjustment = 0.0
        vector = None
        shadow = None
        try:
            ind = calculate_indicators(closed, config_from_env())
            book = self.client.depth(candidate.symbol, limit=20) if hasattr(self.client, "depth") else None
            trades = self.client.agg_trades(candidate.symbol, limit=50) if hasattr(self.client, "agg_trades") else None
            vector = build_market_feature_vector(
                candidate.symbol,
                self.interval,
                ind,
                wave_report=report,
                order_book=book,
                trades=trades,
            )
            if self.quant_ranking_enabled:
                if vector.regime == "TRENDING_EXPANSION":
                    quant_adjustment += 2.0 * float(vector.regime_score)
                elif vector.regime == "HIGH_NOISE_WASH":
                    quant_adjustment -= 2.0 * float(vector.regime_score)
                quant_adjustment += self._clamp(vector.obi * 1.5, -1.5, 1.5)

            shadow = None
            if self.ai_shadow_enabled:
                shadow = self.shadow_engine.evaluate(
                    vector,
                    williams_signal=bool(candidate.signal),
                    htf_confirmed=bool(report.htf_confirmed),
                )
            if self.feature_store is not None:
                self.feature_store.save_feature(vector)
                if shadow is not None:
                    journal_shadow_decision(self.feature_store, shadow, vector)
                    if self.shadow_execution_enabled:
                        expected_reward = max(0.0, float(candidate.risk_reward) * max(float(candidate.risk_pct), 0.0) / 100.0)
                        fill = self.shadow_execution.simulate(
                            shadow,
                            reference_price=vector.price,
                            expected_reward_pct=expected_reward,
                        )
                        self.feature_store.save_shadow_execution(fill.to_dict())
        except Exception as exc:
            log.warning("Quant enrichment unavailable for %s: %s", candidate.symbol, exc)

        final_score = self._clamp(
            float(candidate.base_score) + wave_adjustment + quant_adjustment,
            0.0,
            100.0,
        )

        htf_confirmed = bool(report.htf_confirmed)
        reason = candidate.reason
        if report.reason:
            reason += f"; wave: {report.reason}"
        if report.wave_path:
            reason += f"; path={report.wave_path}"
        if vector is not None:
            reason += f"; regime={vector.regime}"
            if vector.wave_nested_w3_parent_w5:
                reason += "; nested-W3-inside-W5"
        if shadow is not None:
            reason += f"; shadow={shadow.direction}:{shadow.confidence:.2f}"

        enriched_signal_specs = []
        from campaign_model import SignalRole, SignalSpec, SignalType
        frame_setup = report.frames.get(self.interval)
        direction_htf: dict[str, bool] = {}
        for raw in candidate.campaign_signal_specs:
            raw_direction = str(raw.get("direction", "") or "").upper()
            if raw_direction not in {"LONG", "SHORT"}:
                raw_direction = {
                    "BUY": "LONG", "LONG": "LONG",
                    "SELL": "SHORT", "SHORT": "SHORT",
                }.get(str(raw.get("side", "")).upper(), "")
            if raw_direction in {"LONG", "SHORT"} and raw_direction not in direction_htf:
                try:
                    direction_htf[raw_direction] = self._htf_confirmation(
                        candidate.symbol,
                        direction=raw_direction,
                    )
                except Exception as exc:
                    log.warning(
                        "Directional HTF confirmation failed for %s/%s: %s",
                        candidate.symbol, raw_direction, exc,
                    )
                    direction_htf[raw_direction] = False
        candidate_direction = str(candidate.direction or "").upper()
        directional_htf_confirmed = (
            direction_htf.get(candidate_direction, bool(report.htf_confirmed))
        )
        for raw in candidate.campaign_signal_specs:
            try:
                spec = SignalSpec(
                    signal_id=str(raw["signal_id"]),
                    symbol=str(raw["symbol"]),
                    side=str(raw["side"]),
                    signal_type=SignalType(str(raw["signal_type"])),
                    direction=str(raw.get("direction", "") or ""),
                    role=SignalRole(str(raw["role"])),
                    timeframe=str(raw["timeframe"]),
                    signal_bar_time_ms=int(raw["signal_bar_time_ms"]),
                    trigger_price=float(raw["trigger_price"]),
                    protective_reference=float(raw["protective_reference"]),
                    trigger_buffer_ticks=int(raw.get("trigger_buffer_ticks", 1) or 1),
                    invalidation_price=float(frame_setup.invalidation_price if frame_setup else raw.get("invalidation_price", 0.0) or 0.0),
                    teeth_at_detection=float(raw.get("teeth_at_detection", 0.0) or 0.0),
                    alligator_bullish=bool(raw.get("alligator_bullish", False)),
                    alligator_bearish=bool(raw.get("alligator_bearish", False)),
                    alligator_awake=bool(raw.get("alligator_awake", False)),
                    angulation_score=float(raw.get("angulation_score", 0.0) or 0.0),
                    wave_confidence=float(report.wave_score),
                    wave_exhaustion_risk=float(report.exhaustion_risk),
                    htf_confirmed=bool(
                        direction_htf.get(
                            str(raw.get("direction", "") or "").upper(),
                            bool(htf_confirmed),
                        )
                    ),
                    context_versions=dict(raw.get("context_versions", {}) or {}),
                    reason=str(raw.get("reason", "")),
                    created_at_ms=int(raw.get("created_at_ms", 0) or 0),
                    expires_at_ms=int(raw.get("expires_at_ms", 0) or 0),
                    source_candle_index=(
                        -1 if raw.get("source_candle_index", -1) is None
                        else int(raw.get("source_candle_index", -1))
                    ),
                )
                enriched_signal_specs.append(spec.to_dict())
            except Exception:
                enriched_signal_specs.append(raw)

        return replace(
            candidate,
            score=round(final_score, 2),
            htf_confirmed=directional_htf_confirmed,
            reason=reason,
            base_score=round(float(candidate.base_score), 2),
            quant_rank_adjustment=round(float(quant_adjustment), 4),
            quant_score=round(self._clamp(float(final_score), 0.0, 100.0), 2),
            wave_entry_allowed=bool(
                (not candidate.signal)
                or bool(report.entry_allowed)
            ),
            wave_block_reason=(
                report.entry_block_reason
                if candidate.signal and not report.entry_allowed
                else ""
            ),
            wave_score=round(float(report.wave_score), 2),
            wave_adjustment=round(float(wave_adjustment), 2),
            wave_position=wave_position,
            wave_phase=wave_phase,
            wave_direction=wave_direction,
            wave_confidence=round(wave_confidence, 2),
            wave_exhaustion_risk=round(float(report.exhaustion_risk), 2),
            nested_w3=bool(report.nested_w3),
            nested_w3_parent_w5=bool(report.nested_w3_parent_w5),
            wave_path=report.wave_path,
            wave_reason=report.reason,
            wave_context=report.to_dict(),
            wave_primary_count=report.primary_count,
            wave_alternative_count=report.alternative_count,
            wave_abc_phase=(setup.abc_phase if setup else ""),
            wave_entry_score=round(float(report.entry_score), 2),
            wave_invalidation_price=round(float(setup.invalidation_price if setup else 0.0), 12),
            wave_target_zone_low=round(float(setup.target_zone_low if setup else 0.0), 12),
            wave_target_zone_high=round(float(setup.target_zone_high if setup else 0.0), 12),
            wave_magic_bullets_count=int(setup.magic_bullets_count if setup else 0),
            wave_terminal_fractal=bool(setup.terminal_fractal if setup else False),
            wave_scenario_primary=(setup.scenario_primary if setup else ""),
            wave_scenario_alternative=(setup.scenario_alternative if setup else ""),
            wave_operative_interval=report.operative_interval,
            campaign_ready=bool(candidate.campaign_ready and htf_confirmed),
            entry_signal_type=(
                enriched_signal_specs[0].get("signal_type", "") if enriched_signal_specs else candidate.entry_signal_type
            ),
            entry_trigger_price=(
                float(enriched_signal_specs[0].get("trigger_price", 0.0) or 0.0)
                if enriched_signal_specs else candidate.entry_trigger_price
            ),
            entry_protective_reference=(
                float(enriched_signal_specs[0].get("protective_reference", 0.0) or 0.0)
                if enriched_signal_specs else candidate.entry_protective_reference
            ),
            entry_signal_time_ms=(
                int(enriched_signal_specs[0].get("signal_bar_time_ms", 0) or 0)
                if enriched_signal_specs else candidate.entry_signal_time_ms
            ),
            campaign_signal_specs=enriched_signal_specs,
            regime=(vector.regime if vector is not None else "UNKNOWN"),
            regime_score=(round(float(vector.regime_score), 4) if vector is not None else 0.0),
            obi=(round(float(vector.obi), 6) if vector is not None else 0.0),
            trade_flow_imbalance=(round(float(vector.trade_flow_imbalance), 6) if vector is not None else 0.0),
            dollar_bar_rate=(round(float(vector.dollar_bar_rate), 6) if vector is not None else 0.0),
            volume_bar_rate=(round(float(vector.volume_bar_rate), 6) if vector is not None else 0.0),
            quant_features=(vector.to_dict() if vector is not None else {}),
            xai_factors=(
                dict(shadow.feature_attribution) if shadow is not None else {}
            ),
            shadow_intent=(shadow.to_dict() if shadow is not None else {}),
        )

    @staticmethod
    def _ranking_key(candidate):
        """Strict signals outrank watch-only setups; quality breaks ties."""
        return (
            1 if candidate.signal else 0,
            candidate.score,
            candidate.wave_score,
            candidate.quant_score if candidate.quant_score else candidate.score,
            candidate.wave_confidence,
            -candidate.wave_exhaustion_risk,
            candidate.wise_man_count,
            candidate.setup_score,
            candidate.risk_reward,
            -candidate.risk_pct,
            -candidate.spread_pct,
        )

    def scan(self) -> List[Candidate]:
        symbols = self._resolve_symbols()
        # Do not silently collapse the autonomous universe to five symbols.\n        # Discovery already applies liquidity/scan limits; every selected symbol\n        # gets the cheap base pass, while Wave/MTF analysis remains staged below.\n        self._refresh_spreads(symbols)
        candidates: List[Candidate] = []
        frames: Dict[str, pd.DataFrame] = {}

        log.info(
            "AUTO-SCAN START: symbols=%d interval=%s wave_chain=%s",
            len(symbols),
            self.interval,
            ",".join(self.wave_engine.intervals),
        )

        # Build a metadata map only in full-universe mode. Explicit symbol lists
        # continue to use the existing per-symbol exchangeInfo validation.
        metadata = self._universe_metadata if (not self.explicit_symbols and self.scan_all_usdt) else None

        def run_one(symbol):
            return symbol, self._analyse_base(symbol, metadata)

        if len(symbols) <= 1 or self.scan_workers == 1:
            results = [run_one(symbol) for symbol in symbols]
        else:
            results = []
            with ThreadPoolExecutor(max_workers=min(self.scan_workers, len(symbols))) as pool:
                futures = [pool.submit(run_one, symbol) for symbol in symbols]
                for future in as_completed(futures):
                    results.append(future.result())

        for symbol, result in results:
            if result is None:
                log.info("AUTO-SCAN SKIP: %s", symbol)
                continue
            candidate, closed = result
            candidates.append(candidate)
            frames[candidate.symbol] = closed
            log.info(
                "AUTO-SCAN BASE: %s state=%s strict=%s score=%.2f",
                candidate.symbol,
                candidate.setup_state,
                candidate.signal,
                candidate.score,
            )

        # Strict signals are the actual execution candidates, so all of them
        # receive Wave analysis. If there are no strict signals, enrich only the
        # best watch candidates so the mobile scanner remains useful without
        # exploding REST traffic across the entire universe.
        strict = [c for c in candidates if c.signal]
        watch = [c for c in candidates if not c.signal]
        watch.sort(
            key=lambda c: (
                c.base_score,
                c.setup_score,
                c.wise_man_count,
                -c.spread_pct,
                -c.atr_pct,
            ),
            reverse=True,
        )
        # Concentrate expensive Wave/MTF analysis on the best setups instead
        # of spreading it across the whole market: fewer pairs, deeper analysis.
        wave_targets = strict + watch[: self.wave_top_n]
        log.info(
            "AUTO-SCAN DEEP FILTER: base_candidates=%d strict=%d watch=%d deep_wave_targets=%d",
            len(candidates),
            len(strict),
            len(watch),
            len(wave_targets),
        )

        enriched: List[Candidate] = []
        blocked_symbols = set()

        def enrich_one(candidate):
            frame = frames.get(candidate.symbol)
            if frame is None:
                return candidate, None, None
            try:
                return candidate, self._apply_wave(candidate, frame), None
            except Exception as exc:
                return candidate, None, exc

        if wave_targets:
            with ThreadPoolExecutor(max_workers=min(self.wave_scan_workers, len(wave_targets))) as pool:
                wave_results = list(pool.map(enrich_one, wave_targets))
        else:
            wave_results = []

        for candidate, enriched_candidate, exc in wave_results:
            if enriched_candidate is not None:
                if candidate.signal and self.require_htf_confirmation and not enriched_candidate.htf_confirmed:
                    log.info("AUTO-SCAN HTF BLOCK: %s %s strict signal lacks directional HTF confirmation", candidate.symbol, candidate.direction or "UNKNOWN")
                    blocked_symbols.add(candidate.symbol)
                    continue
                if candidate.signal and not enriched_candidate.wave_entry_allowed:
                    log.info("AUTO-SCAN WAVE BLOCK: %s %s", candidate.symbol, enriched_candidate.wave_block_reason)
                    blocked_symbols.add(candidate.symbol)
                    continue
                enriched.append(enriched_candidate)
                continue

            log.warning("Wave analysis failed for %s: %s", candidate.symbol, exc)
            if candidate.signal and os.getenv("NO_TRADE_WHEN_UNCERTAIN", "true").lower() == "true":
                blocked_symbols.add(candidate.symbol)
                continue
            neutral = replace(
                candidate,
                htf_confirmed=(
                    self._htf_confirmation(
                        candidate.symbol,
                        direction=str(candidate.direction or "LONG").upper(),
                    )
                    if candidate.signal and self.require_htf_confirmation
                    else False
                ),
                wave_reason=f"Wave analysis unavailable: {type(exc).__name__}: {exc}",
            )
            if candidate.signal and self.require_htf_confirmation and not neutral.htf_confirmed:
                blocked_symbols.add(candidate.symbol)
                continue
            enriched.append(neutral)

        enriched_by_symbol = {c.symbol: c for c in enriched}
        final = []
        for candidate in candidates:
            if candidate.symbol in blocked_symbols:
                continue
            final.append(enriched_by_symbol.get(candidate.symbol, candidate))

        final.sort(key=self._ranking_key, reverse=True)

        log.info(
            "AUTO-SCAN END: universe=%d candidates=%d strict=%d selected=%s",
            len(symbols),
            len(final),
            sum(1 for c in final if c.signal),
            final[0].symbol if final else "NONE",
        )

        return final

    def best(self) -> Optional[Candidate]:
        candidates = self.scan()
        if not candidates:
            return None
        return max(candidates, key=self._ranking_key)
