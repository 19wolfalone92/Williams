import ast
from pathlib import Path


MUTATIONS = {
    "order",
    "order_safe",
    "cancel_order",
    "cancel_replace",
    "create_oco_sell",
    "create_oco_sell_safe",
    "cancel_oco",
}

ALLOWED_FILES = {"binance_client.py"}
APP_ROOTS = {Path("."), Path("app")}


def _parents(tree):
    parent = {}
    for node in ast.walk(tree):
        for child in ast.iter_child_nodes(node):
            parent[child] = node
    return parent


def _is_barrier_wrapped(node, parent):
    cur = node
    while cur in parent:
        cur = parent[cur]
        if isinstance(cur, ast.Call):
            fn = cur.func
            if isinstance(fn, ast.Name) and fn.id in {"_submit", "_barrier_legacy_mutation"}:
                return True
            if isinstance(fn, ast.Attribute) and fn.attr in {"execute"}:
                owner = fn.value
                if isinstance(owner, ast.Attribute) and owner.attr == "execution_barrier":
                    return True
    return False


def test_no_binance_mutation_bypasses_execution_gate():
    repo = Path(__file__).resolve().parent
    failures = []

    for path in repo.rglob("*.py"):
        if path.name.startswith("test_") or "/tests/" in str(path):
            continue
        if path.name in ALLOWED_FILES:
            continue
        try:
            tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        except SyntaxError as exc:
            failures.append(f"{path}: syntax error: {exc}")
            continue

        parent = _parents(tree)
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Attribute):
                continue
            if node.func.attr not in MUTATIONS:
                continue
            owner = node.func.value
            if not (isinstance(owner, ast.Attribute) and owner.attr == "client"):
                continue
            if not _is_barrier_wrapped(node, parent):
                failures.append(f"{path}:{getattr(node, 'lineno', '?')} -> client.{node.func.attr}")

    assert not failures, "Binance mutation bypass(es) detected:\n" + "\n".join(failures)
