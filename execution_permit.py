"""Context-local capability binding a Binance mutation to the admitted Spot Testnet client."""
from contextlib import contextmanager
from contextvars import ContextVar

_mutation_client = ContextVar("williams_binance_mutation_client", default=None)


@contextmanager
def mutation_permit(client):
    token = _mutation_client.set(client)
    try:
        yield
    finally:
        _mutation_client.reset(token)


def require_mutation_permit(client):
    if client is None or _mutation_client.get() is not client:
        raise RuntimeError("Binance order mutation blocked: matching ExecutionBarrier permit required")
    if getattr(client, "testnet", None) is not True:
        raise RuntimeError("Binance order mutation blocked: Spot Testnet client required")
    if getattr(client, "base_url", None) != "https://testnet.binance.vision":
        raise RuntimeError("Binance order mutation blocked: non-Testnet endpoint")
