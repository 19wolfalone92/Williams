"""Context-local capability binding a Binance mutation to the admitted Spot Testnet client."""
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass


@dataclass
class _MutationPermitState:
    client: object
    consumed: bool = False


_mutation_client = ContextVar("williams_binance_mutation_client", default=None)
_BARRIER_ADMISSION_TOKEN = object()


@contextmanager
def _mutation_permit(client, *, admission_token):
    if admission_token is not _BARRIER_ADMISSION_TOKEN:
        raise RuntimeError("Mutation permit can only be issued by ExecutionBarrier")
    state = _MutationPermitState(client=client)
    token = _mutation_client.set(state)
    try:
        yield
    finally:
        _mutation_client.reset(token)


def require_mutation_permit(client):
    state = _mutation_client.get()
    if client is None or state is None or state.client is not client:
        raise RuntimeError("Binance order mutation blocked: matching ExecutionBarrier permit required")
    if getattr(client, "testnet", None) is not True:
        raise RuntimeError("Binance order mutation blocked: Spot Testnet client required")
    if getattr(client, "base_url", None) != "https://testnet.binance.vision":
        raise RuntimeError("Binance order mutation blocked: non-Testnet endpoint")
    if state.consumed:
        raise RuntimeError("Binance order mutation blocked: one mutation per ExecutionBarrier permit")
    # Claim before the network call. Exceptions and timeouts do not make this
    # capability reusable; recovery must use a fresh admission after reconciliation.
    state.consumed = True
