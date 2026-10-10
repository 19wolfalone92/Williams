"""Context-local permit for Binance order mutations; granted by ExecutionBarrier only."""
from contextlib import contextmanager
from contextvars import ContextVar

_mutation_permitted = ContextVar("williams_binance_mutation_permitted", default=False)


@contextmanager
def mutation_permit():
    token = _mutation_permitted.set(True)
    try:
        yield
    finally:
        _mutation_permitted.reset(token)


def require_mutation_permit():
    if not _mutation_permitted.get():
        raise RuntimeError("Binance order mutation blocked: ExecutionBarrier permit required")
