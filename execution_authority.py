"""Capability scope proving a Spot BUY is inside ExecutionBarrier submission."""
from contextlib import contextmanager
from contextvars import ContextVar

_entry_submission_authorized = ContextVar(
    "williams_entry_submission_authorized",
    default=False,
)


def entry_submission_authorized() -> bool:
    """Return whether this execution context is inside the final entry barrier."""
    return bool(_entry_submission_authorized.get())


@contextmanager
def entry_submission_scope():
    """Temporarily authorize one synchronous entry submit callback."""
    token = _entry_submission_authorized.set(True)
    try:
        yield
    finally:
        _entry_submission_authorized.reset(token)
