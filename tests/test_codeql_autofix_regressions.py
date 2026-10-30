"""Security regressions for the reviewed CodeQL/Copilot Autofix changes.

These tests deliberately avoid importing server.py, which starts runtime state at import.
"""
import ast
from pathlib import Path


SERVER_SOURCE = Path(__file__).resolve().parents[1] / "server.py"


def _safe_status_helper():
    tree = ast.parse(SERVER_SOURCE.read_text(encoding="utf-8"))
    funcs = [node for node in tree.body if isinstance(node, ast.FunctionDef)
             and node.name == "_safe_runtime_error"]
    assert len(funcs) == 1
    namespace = {}
    exec(compile(ast.Module(body=funcs, type_ignores=[]), str(SERVER_SOURCE), "exec"),
         namespace)
    return namespace["_safe_runtime_error"]


def test_public_error_status_omits_secret_exception_details():
    formatter = _safe_status_helper()
    secret = "API_SECRET=not-for-public-json; signature=deadbeef"
    assert formatter("status-balance", RuntimeError(secret)) == "status-balance: RuntimeError"

    class HostileException(Exception):
        def __str__(self):
            raise AssertionError("public error status must not stringify exceptions")

    assert formatter("supervisor", HostileException(secret)) == "supervisor: HostileException"


def test_all_codeql_reported_status_paths_use_safe_error_formatter():
    source = SERVER_SOURCE.read_text(encoding="utf-8")
    for stage in (
        "supervisor probe",
        "supervisor",
        "position-status",
        "status-balance",
        "portfolio-position",
    ):
        assert source.count(f"_safe_runtime_error('{stage}', exc)") == 1
    assert "'last_error': state.last_error" in source
