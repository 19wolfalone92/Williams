"""Purged/embargoed combinatorial cross-validation for Williams research."""
from __future__ import annotations

from dataclasses import dataclass
from itertools import combinations
from statistics import mean
from typing import Callable, Iterable, Sequence


@dataclass(frozen=True)
class CPCVSplit:
    fold_id: int
    train: tuple[int, ...]
    test: tuple[int, ...]


@dataclass(frozen=True)
class CPCVResult:
    folds: int
    mean_score: float
    min_score: float
    max_score: float
    scores: tuple[float, ...]
    n_samples: int
    n_groups: int
    test_groups: int
    purge_bars: int
    embargo_bars: int


def cpcv_splits(
    n_samples: int,
    *,
    n_groups: int = 6,
    test_groups: int = 2,
    purge_bars: int = 10,
    embargo_bars: int = 10,
) -> list[CPCVSplit]:
    n_samples = int(n_samples)
    n_groups = int(n_groups)
    test_groups = int(test_groups)
    purge_bars = max(0, int(purge_bars))
    embargo_bars = max(0, int(embargo_bars))
    if n_samples <= 0:
        return []
    if n_groups < 2 or test_groups < 1 or test_groups >= n_groups:
        raise ValueError("require 2 <= n_groups and 1 <= test_groups < n_groups")
    group_size = max(1, n_samples // n_groups)
    groups: list[tuple[int, int]] = []
    for g in range(n_groups):
        start = g * group_size
        end = n_samples if g == n_groups - 1 else min(n_samples, (g + 1) * group_size)
        if start < end:
            groups.append((start, end))

    result: list[CPCVSplit] = []
    for fold_id, combo in enumerate(combinations(range(len(groups)), test_groups)):
        test_ranges = [groups[i] for i in combo]
        test = [i for a, b in test_ranges for i in range(a, b)]
        blocked = set()
        for a, b in test_ranges:
            blocked.update(range(max(0, a - purge_bars), min(n_samples, b + embargo_bars)))
        train = tuple(i for i in range(n_samples) if i not in blocked and i not in test)
        result.append(CPCVSplit(fold_id, train, tuple(test)))
    return result


def run_cpcv(
    samples: Sequence,
    scorer: Callable[[Sequence, Sequence], float],
    *,
    n_groups: int = 6,
    test_groups: int = 2,
    purge_bars: int = 10,
    embargo_bars: int = 10,
) -> CPCVResult:
    splits = cpcv_splits(
        len(samples),
        n_groups=n_groups,
        test_groups=test_groups,
        purge_bars=purge_bars,
        embargo_bars=embargo_bars,
    )
    scores: list[float] = []
    for split in splits:
        train = [samples[i] for i in split.train]
        test = [samples[i] for i in split.test]
        score = float(scorer(train, test))
        scores.append(score)
    if not scores:
        return CPCVResult(0, 0.0, 0.0, 0.0, tuple(), len(samples), n_groups, test_groups, purge_bars, embargo_bars)
    return CPCVResult(
        folds=len(scores),
        mean_score=mean(scores),
        min_score=min(scores),
        max_score=max(scores),
        scores=tuple(scores),
        n_samples=len(samples),
        n_groups=n_groups,
        test_groups=test_groups,
        purge_bars=purge_bars,
        embargo_bars=embargo_bars,
    )


__all__ = ["CPCVSplit", "CPCVResult", "cpcv_splits", "run_cpcv"]
