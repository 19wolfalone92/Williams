import logging
import os
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass, field, replace
from typing import Dict, List, Optional, Tuple

import pandas as pd

from data import fetch_klines
from strategy import calculate_indicators, config_from_env
from wave_engine import DIRECTION_NEUTRAL, MultiTimeframeWaveEngine


log = logging.getLogger("williams-scanner")


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

        self.interval = (interval or os.getenv("INTERVAL", "1h")).lower()

        # `None` means resolve the configured/default universe. An explicit []
        # remains a genuine empty test universe and does not fall back to all.
        self.explicit_symbols = symbols is not None
        self.symbols = (
            [str(x).upper().strip() for x in symbols if str(x).strip()]
            if symbols is not None
            else self._load_symbols()
        )

        self.scan_all_usdt = True  # dynamically discover liquid Spot/USDT pairs
        self.scan_max_symbols = max(0, int(os.getenv("SCAN_MAX_SYMBOLS", "50")))
        self.exclude_leveraged_tokens = (
            os.getenv("EXCLUDE_LEVERAGED_TOKENS", "true").lower() == "true"
        )
        self.scan_workers = max(1, int(os.getenv("SCAN_WORKERS", "12")))
        self.liquidity_preselect = max(0, int(os.getenv("LIQUIDITY_PRESELECT", "50")))
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
        self.htf_interval = os.getenv("HTF_INTERVAL", "4h").lower()

        self.wave_engine = MultiTimeframeWaveEngine(
            self.client,
            base_interval=self.interval,
            include_micro=True,
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

    def _htf_confirmation(self, symbol):
        if not self.require_htf_confirmation:
            return True

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
            htf.iloc[:-1].copy(),
            config_from_env(),
        )
        last = ind.iloc[-1]
        return bool(last.get("bullish_alligator", False)) and float(last.get("ao", 0) or 0) > 0

    def _setup_state(self, last):
        strict_signal = bool(last.get("long_signal", False))
        if strict_signal:
            return "STRONG_SIGNAL"

        bullish = bool(last.get("long_bullish", False))
        awake = bool(last.get("long_awake", False))
        ao = bool(last.get("long_ao_positive", False))
        ac = bool(last.get("long_ac_positive", False))
        fractal = bool(last.get("long_fractal_ready", False))

        if bullish and awake and ao and ac and fractal:
            return "SETUP_READY"
        if bullish or (ao and ac) or (bullish and awake):
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
            setup_state = self._setup_state(last)
            if setup_state == "NONE":
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

            strict_signal = bool(last.get("long_signal", False))
            setup_score = float(last.get("long_setup_score", 0.0))
            breakout_distance_pct = float(last.get("long_breakout_distance_pct", 0.0))

            if strict_signal:
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
            breakout_bonus = 15.0 if strict_signal else 0.0

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

            if strict_signal:
                reason = "strict long signal passed strategy and scanner filters"
            elif setup_state == "SETUP_READY":
                reason = "bullish setup ready; waiting for strict fractal breakout"
            else:
                reason = "bullish setup being monitored"

            candidate = Candidate(
                symbol=symbol,
                score=round(base_score, 2),
                signal=strict_signal,
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
                wise_man_count=int(last.get("long_wise_man_count", 0) or 0),
                signal_family=str(last.get("long_signal_family", "NONE") or "NONE"),
                base_score=round(base_score, 2),
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
        final_score = self._clamp(
            float(candidate.base_score) + wave_adjustment,
            0.0,
            100.0,
        )

        htf_confirmed = bool(report.htf_confirmed)
        reason = candidate.reason
        if report.reason:
            reason += f"; wave: {report.reason}"

        if report.wave_path:
            reason += f"; path={report.wave_path}"

        return replace(
            candidate,
            score=round(final_score, 2),
            htf_confirmed=htf_confirmed,
            reason=reason,
            base_score=round(float(candidate.base_score), 2),
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
        )

    @staticmethod
    def _ranking_key(candidate):
        """Strict signals outrank watch-only setups; quality breaks ties."""
        return (
            1 if candidate.signal else 0,
            candidate.score,
            candidate.wave_score,
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
                    log.info("AUTO-SCAN HTF BLOCK: %s strict signal has no bullish HTF confirmation", candidate.symbol)
                    blocked_symbols.add(candidate.symbol)
                    continue
                if candidate.signal and not enriched_candidate.wave_entry_allowed:
                    log.info("AUTO-SCAN WAVE BLOCK: %s %s", candidate.symbol, enriched_candidate.wave_block_reason)
                    blocked_symbols.add(candidate.symbol)
                    continue
                enriched.append(enriched_candidate)
                continue

            log.warning("Wave analysis failed for %s: %s", candidate.symbol, exc)
            neutral = replace(
                candidate,
                htf_confirmed=(
                    self._htf_confirmation(candidate.symbol)
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
