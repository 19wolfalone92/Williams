import os
import logging
from dataclasses import dataclass, asdict
from typing import List, Optional

from data import fetch_klines
from strategy import calculate_indicators, config_from_env


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

    def to_dict(self):
        return asdict(self)


class MarketScanner:
    """
    Finds and ranks long setups across configured USDT symbols.

    IMPORTANT:
    - This component NEVER places orders.
    - long_signal remains the strict entry condition.
    - SETUP_READY candidates are informational/ranking candidates only.
    """

    def __init__(self, client, symbols=None, interval=None):
        self.client = client

        self.interval = interval or os.getenv(
            "INTERVAL",
            "1h",
        )

        self.symbols = symbols or self._load_symbols()

        self.atr_period = int(
            os.getenv("ATR_PERIOD", "14")
        )

        self.max_atr_pct = float(
            os.getenv("MAX_ATR_PCT", "0.08")
        )

        self.max_spread_pct = float(
            os.getenv("MAX_SPREAD_PCT", "0.0015")
        )
        self.require_htf_confirmation = (
            os.getenv(
                "REQUIRE_HTF_CONFIRMATION",
                "true"
            ).lower() == "true"
        )
        self.min_risk_reward = float(
            os.getenv("MIN_RISK_REWARD", "1.5")
        )

        self.stop_pct = float(
            os.getenv("STOP_LOSS_PCT", "0.02")
        )

        self.target_pct = float(
            os.getenv("TAKE_PROFIT_PCT", "0.04")
        )

        self.min_rr = float(
            os.getenv("MIN_RISK_REWARD", "1.5")
        )

        self.htf_interval = os.getenv(
            "HTF_INTERVAL",
            "4h",
        )

    def _load_symbols(self):
        raw = os.getenv(
            "SCAN_SYMBOLS",
            "BTCUSDT,ETHUSDT,SOLUSDT,BNBUSDT,XRPUSDT,"
            "ADAUSDT,DOGEUSDT,AVAXUSDT,LINKUSDT,DOTUSDT",
        )

        return [
            x.strip().upper()
            for x in raw.split(",")
            if x.strip()
        ]

    @staticmethod
    def _atr(df, period):
        prev = df["close"].shift(1)

        import pandas as pd

        tr = pd.concat(
            [
                df["high"] - df["low"],
                (df["high"] - prev).abs(),
                (df["low"] - prev).abs(),
            ],
            axis=1,
        ).max(axis=1)

        return float(
            tr.rolling(period).mean().iloc[-1]
        )

    def _spread(self, symbol):
        book = self.client.book_ticker(symbol)

        bid = float(book["bidPrice"])
        ask = float(book["askPrice"])

        mid = (bid + ask) / 2

        if mid <= 0:
            return 1.0

        return (ask - bid) / mid

    def _symbol_is_valid(self, symbol):
        info = self.client.exchange_info(symbol)
        symbols = info.get("symbols", [])

        if len(symbols) != 1:
            return False

        item = symbols[0]

        if item.get("status") != "TRADING":
            return False

        if item.get("quoteAsset") != "USDT":
            return False

        return True

    def _htf_confirmation(self, symbol):
        if (
            os.getenv(
                "REQUIRE_HTF_CONFIRMATION",
                "true",
            ).lower()
            != "true"
        ):
            return True

        htf = fetch_klines(
            self.client,
            symbol,
            self.htf_interval,
            limit=160,
        )

        if len(htf) < 80:
            return False

        ind = calculate_indicators(
            htf.iloc[:-1].copy(),
            config_from_env(),
        )

        last = ind.iloc[-1]

        return bool(
            last.get("bullish_alligator", False)
        ) and float(
            last.get("ao", 0) or 0
        ) > 0

    @staticmethod
    def _bool(value):
        return bool(value)

    def _setup_state(self, last):
        """
        Informational setup classification.

        STRONG_SIGNAL:
            All strict entry conditions are satisfied.

        SETUP_READY:
            Directional conditions are satisfied and the market is
            close to/at a breakout setup, but strict entry is not yet
            triggered.

        WATCHING:
            Some bullish conditions exist, but the setup is incomplete.

        NONE:
            No meaningful bullish setup.
        """

        strict_signal = bool(
            last.get("long_signal", False)
        )

        if strict_signal:
            return "STRONG_SIGNAL"

        bullish = bool(
            last.get("long_bullish", False)
        )

        awake = bool(
            last.get("long_awake", False)
        )

        ao = bool(
            last.get("long_ao_positive", False)
        )

        ac = bool(
            last.get("long_ac_positive", False)
        )

        fractal = bool(
            last.get("long_fractal_ready", False)
        )

        if bullish and awake and ao and ac and fractal:
            return "SETUP_READY"

        if (
            bullish
            or (ao and ac)
            or (
                bullish
                and awake
            )
        ):
            return "WATCHING"

        return "NONE"

    def analyse(self, symbol) -> Optional[Candidate]:
        symbol = symbol.upper()

        try:
            if not self._symbol_is_valid(symbol):
                return None

            df = fetch_klines(
                self.client,
                symbol,
                self.interval,
                limit=250,
            )

            if len(df) < 100:
                return None

            # Never analyse an unfinished candle.
            closed = df.iloc[:-1].copy()

            indicators = calculate_indicators(
                closed,
                config_from_env(),
            )

            last = indicators.iloc[-1]

            setup_state = self._setup_state(last)

            # We still need a meaningful directional setup.
            if setup_state == "NONE":
                return None

            price = float(last["close"])

            if price <= 0:
                return None

            atr = self._atr(
                closed,
                self.atr_period,
            )

            if atr <= 0:
                return None

            atr_pct = atr / price

            if atr_pct > self.max_atr_pct:
                return None

            spread_pct = self._spread(symbol)

            if spread_pct > self.max_spread_pct:
                return None

            rr = (
                self.target_pct
                / max(self.stop_pct, 1e-9)
            )

            if rr < self.min_rr:
                return None

            htf = self._htf_confirmation(symbol)

            # HTF confirmation remains required for an actual
            # STRONG_SIGNAL, but SETUP_READY candidates can still
            # be observed/ranked.
            strict_signal = bool(
                last.get("long_signal", False)
            )

            if strict_signal and not htf:
                return None

            setup_score = float(
                last.get("long_setup_score", 0.0)
            )

            breakout_distance_pct = float(
                last.get(
                    "long_breakout_distance_pct",
                    0.0,
                )
            )

            # Normalized signal strength:
            #
            # 1.0 = strict signal
            # 0.8 = full directional setup, waiting for breakout
            # lower = incomplete setup
            if strict_signal:
                signal_strength = 1.0
            elif setup_state == "SETUP_READY":
                signal_strength = 0.8
            elif setup_state == "WATCHING":
                signal_strength = 0.5
            else:
                signal_strength = 0.0

            risk_pct = self.stop_pct * 100.0

            risk_score = max(
                0.0,
                min(
                    20.0,
                    20.0
                    * (
                        0.02
                        / max(
                            self.stop_pct,
                            0.0001,
                        )
                    ),
                ),
            )

            rr_score = min(
                20.0,
                20.0 * (rr / 3.0),
            )

            htf_score = 15.0 if htf else 0.0

            atr_score = max(
                0.0,
                10.0
                * (
                    1.0
                    - atr_pct
                    / max(
                        self.max_atr_pct,
                        1e-9,
                    )
                ),
            )

            spread_score = max(
                0.0,
                5.0
                * (
                    1.0
                    - spread_pct
                    / max(
                        self.max_spread_pct,
                        1e-9,
                    )
                ),
            )

            # Strategy quality contributes the largest ranking
            # component. This differentiates SOL/BNB/AVAX style
            # setups instead of assigning every signal the same 30.
            strategy_score = (
                30.0
                * (setup_score / 100.0)
            )

            # Strict breakout receives the final confirmation bonus.
            breakout_bonus = (
                15.0
                if strict_signal
                else 0.0
            )

            score = min(
                100.0,
                strategy_score
                + risk_score
                + rr_score
                + htf_score
                + atr_score
                + spread_score
                + breakout_bonus,
            )

            if strict_signal:
                reason = (
                    "strict long signal passed "
                    "strategy and scanner filters"
                )
            elif setup_state == "SETUP_READY":
                reason = (
                    "bullish setup ready; "
                    "waiting for strict fractal breakout"
                )
            else:
                reason = (
                    "bullish setup being monitored"
                )

            return Candidate(
                symbol=symbol,
                score=round(score, 2),
                signal=strict_signal,
                setup_score=round(
                    setup_score,
                    2,
                ),
                signal_strength=round(
                    signal_strength,
                    3,
                ),
                breakout_distance_pct=round(
                    breakout_distance_pct,
                    4,
                ),
                risk_pct=round(
                    risk_pct,
                    4,
                ),
                risk_reward=round(
                    rr,
                    3,
                ),
                atr_pct=round(
                    atr_pct,
                    6,
                ),
                spread_pct=round(
                    spread_pct,
                    6,
                ),
                htf_confirmed=htf,
                setup_state=setup_state,
                reason=reason,
            )

        except Exception as exc:
            log.warning(
                "Scanner skipped %s: %s",
                symbol,
                exc,
            )
            return None

    @staticmethod
    def _ranking_key(candidate):
        """
        Strict signals always outrank merely watched setups.
        Within the same state, quality/risk determine the winner.
        """

        return (
            1 if candidate.signal else 0,
            candidate.score,
            candidate.setup_score,
            candidate.risk_reward,
            -candidate.risk_pct,
            -candidate.spread_pct,
        )

    def scan(self) -> List[Candidate]:
        candidates = []

        for symbol in self.symbols:
            candidate = self.analyse(symbol)

            if candidate is not None:
                candidates.append(candidate)

        candidates.sort(
            key=self._ranking_key,
            reverse=True,
        )

        return candidates

    def best(self) -> Optional[Candidate]:
        candidates = self.scan()

        if not candidates:
            return None

        return max(
            candidates,
            key=self._ranking_key,
        )
