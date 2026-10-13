#!/usr/bin/env python3
"""Fail-closed, bounded source-to-sink gate for Binance Spot mutation paths.

This gate deliberately does not claim whole-program proof for dynamic Python or Kotlin.
It verifies an explicit registry, exact Binance REST mutation endpoints, transport sink
containment, and the independent Android runtime lock. Unsupported mutation primitives
or unresolved dynamic request method/path values fail the build.
"""
from __future__ import annotations

import ast
import json
import sys
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parent
REGISTRY_PATH = ROOT / "mutation_registry.json"
EXCLUDED_PARTS = {
    ".git", ".gradle", "build", "__pycache__", ".venv", "venv",
}
TEST_PREFIXES = ("test_",)
TEST_SUFFIXES = ("_test.py",)
LOW_LEVEL_CALLS = {"request", "send", "urlopen"}
DIRECT_MUTATION_CALLS = {
    "post", "put", "patch", "delete", "create_order", "create_oco",
    "create_oco_sell", "cancel_order", "cancel_oco", "cancel_replace",
    "cancelReplace", "market_buy", "market_sell", "place_oco",
}
HTTP_METHODS = {"POST", "PUT", "PATCH", "DELETE"}


def load_registry(path: Path = REGISTRY_PATH) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise RuntimeError(f"cannot load mutation registry: {exc}") from exc
    if data.get("schema_version") != 1:
        raise RuntimeError("unsupported or missing mutation registry schema_version")
    if not isinstance(data.get("python_mutation_endpoints"), list):
        raise RuntimeError("registry python_mutation_endpoints must be a list")
    if not isinstance(data.get("python_transport_sink"), dict):
        raise RuntimeError("registry python_transport_sink must be an object")
    if not isinstance(data.get("android_lock_contract"), dict):
        raise RuntimeError("registry android_lock_contract must be an object")
    return data


def _call_name(node: ast.Call) -> str:
    fn = node.func
    if isinstance(fn, ast.Name):
        return fn.id
    if isinstance(fn, ast.Attribute):
        return fn.attr
    return "<dynamic-call>"


def _literal_string(node: ast.AST | None) -> str | None:
    return node.value if isinstance(node, ast.Constant) and isinstance(node.value, str) else None


def _function_name(node: ast.AST, parents: dict[ast.AST, ast.AST]) -> str:
    parts: list[str] = []
    current: ast.AST | None = node
    while current is not None:
        if isinstance(current, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            parts.append(current.name)
        current = parents.get(current)
    return ".".join(reversed(parts))


def _parents(tree: ast.AST) -> dict[ast.AST, ast.AST]:
    result: dict[ast.AST, ast.AST] = {}
    for parent in ast.walk(tree):
        for child in ast.iter_child_nodes(parent):
            result[child] = parent
    return result


def _is_test_file(path: str) -> bool:
    p = Path(path)
    return (
        p.name.startswith(TEST_PREFIXES)
        or p.name.endswith(TEST_SUFFIXES)
        or "tests" in p.parts
        or p.name in {"mutation_registry_gate.py", "full_safety_audit.py"}
    )


def analyze_python_sources(
    sources: dict[str, str], registry: dict
) -> list[str]:
    """Analyze supplied source map; exported for negative fixture tests."""
    errors: list[str] = []
    approved = {
        (str(item.get("method", "")).upper(), str(item.get("endpoint", "")))
        for item in registry["python_mutation_endpoints"]
        if item.get("status") == "approved"
        and item.get("evidence_tests")
        and item.get("reason")
    }
    if not approved:
        errors.append("registry has no evidenced approved Python mutation endpoints")

    external_sinks = {
        (str(item.get("path", "")), str(item.get("symbol", "")), str(item.get("method", "")).lower())
        for item in registry.get("external_non_exchange_sinks", [])
        if item.get("status") == "approved" and item.get("reason") and item.get("evidence_tests")
    }

    transport = registry["python_transport_sink"]
    transport_path = transport.get("path")
    transport_symbol = transport.get("symbol")
    if not transport.get("evidence_tests") or not transport.get("reason"):
        errors.append("Python transport sink lacks required evidence_tests/reason")

    for path, source in sorted(sources.items()):
        if _is_test_file(path) or not path.endswith(".py"):
            continue
        try:
            tree = ast.parse(source, filename=path)
        except SyntaxError as exc:
            errors.append(f"{path}:{exc.lineno}: production Python cannot be parsed: {exc.msg}")
            continue
        parents = _parents(tree)
        network_aliases: set[str] = set()
        for assignment in ast.walk(tree):
            if isinstance(assignment, ast.Assign) and isinstance(assignment.value, ast.Attribute):
                if assignment.value.attr in DIRECT_MUTATION_CALLS | LOW_LEVEL_CALLS:
                    for target in assignment.targets:
                        if isinstance(target, ast.Name):
                            network_aliases.add(target.id)
            elif isinstance(assignment, ast.NamedExpr) and isinstance(assignment.value, ast.Attribute):
                if assignment.value.attr in DIRECT_MUTATION_CALLS | LOW_LEVEL_CALLS and isinstance(assignment.target, ast.Name):
                    network_aliases.add(assignment.target.id)

        for node in ast.walk(tree):
            if not isinstance(node, ast.Call):
                continue
            name = _call_name(node)
            enclosing = _function_name(node, parents)
            if name in network_aliases:
                errors.append(f"{path}:{node.lineno}: alias-based network mutation call {name}() is unresolved")

            # The only permitted raw HTTP transport is the registered generic
            # Binance client transport. Its method/path are parameters, so the
            # function itself is a sink and must remain in the registered symbol.
            if name == "request":
                is_binance_transport = (
                    path == transport_path
                    and enclosing == transport_symbol
                    and isinstance(node.func, ast.Attribute)
                    and node.func.attr == "request"
                )
                if not is_binance_transport:
                    errors.append(
                        f"{path}:{node.lineno}: raw/dynamic HTTP request outside registered "
                        f"transport sink ({enclosing or '<module>'})"
                    )

            # Any raw requests.post/put/patch/delete, httpx mutator, urllib
            # opener or equivalent is forbidden outside the registered transport.
            if name in {"post", "put", "patch", "delete", "urlopen", "send"}:
                base = node.func.value if isinstance(node.func, ast.Attribute) else None
                base_name = (
                    base.id if isinstance(base, ast.Name)
                    else base.attr if isinstance(base, ast.Attribute)
                    else ""
                )
                looks_like_network = base_name in {
                    "requests", "httpx", "session", "client", "Session",
                    "AsyncClient", "Client", "urlopen",
                } or name in {"urlopen"}
                if looks_like_network and not (
                    (path == transport_path and enclosing == transport_symbol and name == "request")
                    or (path, enclosing, name) in external_sinks
                ):
                    errors.append(
                        f"{path}:{node.lineno}: direct network mutation primitive {name}() "
                        f"is not an approved transport sink"
                    )

            # Calls to the generic Binance _request API outside its defining
            # module/class bypass the wrapper inventory and are rejected.
            if name == "_request":
                if path != transport_path or not enclosing.startswith("BinanceSpotClient."):
                    # Read-only GETs through the existing client are allowed for legacy
                    # history loaders; any mutation or dynamic call remains forbidden.
                    method = _literal_string(node.args[0]) if len(node.args) >= 2 else None
                    endpoint = _literal_string(node.args[1]) if len(node.args) >= 2 else None
                    if method is None or endpoint is None or method.upper() != "GET":
                        errors.append(
                            f"{path}:{node.lineno}: direct _request call bypasses registered Binance client"
                        )
                elif len(node.args) < 2:
                    errors.append(
                        f"{path}:{node.lineno}: _request method/path cannot be proven"
                    )
                else:
                    method = _literal_string(node.args[0])
                    endpoint = _literal_string(node.args[1])
                    if method is None or endpoint is None:
                        errors.append(
                            f"{path}:{node.lineno}: dynamic mutation method/path is unresolved"
                        )
                    elif method.upper() in HTTP_METHODS and (method.upper(), endpoint) not in approved:
                        errors.append(
                            f"{path}:{node.lineno}: unregistered Binance mutation endpoint "
                            f"{method.upper()} {endpoint}"
                        )

            # Explicitly reject dynamic execution that could hide a sink.
            if name in {"eval", "exec"}:
                errors.append(f"{path}:{node.lineno}: dynamic code execution is outside analysis model")

    # Every registry endpoint must be represented in the actual client source.
    client_source = sources.get(transport_path or "", "")
    for item in registry["python_mutation_endpoints"]:
        if item.get("status") != "approved":
            continue
        method, endpoint = item.get("method"), item.get("endpoint")
        if not item.get("evidence_tests") or not item.get("reason"):
            errors.append(f"registry endpoint {method} {endpoint} lacks evidence_tests/reason")
        if not isinstance(method, str) or not isinstance(endpoint, str):
            errors.append("registry endpoint has malformed method/endpoint")
            continue
        # Endpoint literals can be assembled by the wrapper, so verify the
        # endpoint string exists; callsite verification above validates _request.
        if endpoint not in client_source:
            errors.append(f"registry endpoint {method} {endpoint} is absent from {transport_path}")

    return errors


def _production_python_sources(root: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for path in root.rglob("*.py"):
        rel = path.relative_to(root).as_posix()
        if any(part in EXCLUDED_PARTS for part in path.parts) or _is_test_file(rel):
            continue
        result[rel] = path.read_text(encoding="utf-8", errors="replace")
    return result


def check_android_lock(root: Path, registry: dict) -> list[str]:
    contract = registry["android_lock_contract"]
    path = root / str(contract.get("path", ""))
    if not path.is_file():
        return [f"Android execution lock source missing: {contract.get('path')}"]
    text = path.read_text(encoding="utf-8", errors="replace")
    errors: list[str] = []
    for needle in contract.get("required_literals", []):
        if needle not in text:
            errors.append(f"Android lock contract missing required literal: {needle}")
    for needle in contract.get("forbidden_literals", []):
        if needle in text:
            errors.append(f"Android lock contract contains forbidden literal: {needle}")
    if not contract.get("reason") or not contract.get("evidence_tests"):
        errors.append("Android lock registry entry lacks reason/evidence_tests")
    return errors


def main() -> int:
    try:
        registry = load_registry()
    except Exception as exc:
        print(f"[FAIL] {exc}")
        return 2

    errors = analyze_python_sources(_production_python_sources(ROOT), registry)
    errors.extend(check_android_lock(ROOT, registry))

    print("WILLIAMS ENFORCED MUTATION REGISTRY — bounded static analysis")
    print(f"Registry: {REGISTRY_PATH.relative_to(ROOT)}")
    print("Scope: production Python AST + explicit Binance endpoint registry + Android lock literals")
    print("Limitations: not whole-program proof; reflective/dynamic dispatch and runtime-generated URLs are unsupported and must fail closed.")
    if errors:
        for error in errors:
            print(f"[FAIL] {error}")
        print(f"RESULT: BLOCKED ({len(errors)} unresolved finding(s))")
        return 1
    print(f"[PASS] {len(registry['python_mutation_endpoints'])} registered endpoint records")
    print("[PASS] no unregistered raw Python HTTP mutation transport found")
    print("[PASS] Android autonomous execution lock remains present")
    print("RESULT: PASS WITH BOUNDED MODEL — independent review still required")
    return 0


if __name__ == "__main__":
    sys.exit(main())
