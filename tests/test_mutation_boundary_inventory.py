from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def _kotlin_body(source: str, signature: str) -> str:
    match = re.search(signature, source)
    assert match, f"required boundary function missing: {signature}"
    opening = source.find("{", match.end())
    assert opening >= 0, f"function body missing: {signature}"
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1:index]
    raise AssertionError(f"unbalanced function body: {signature}")


def test_android_exchange_mutation_transport_is_contained_in_signed_sink():
    source_root = ROOT / "app/src/main"
    raw_call_sites = []
    for path in source_root.rglob("*.kt"):
        source = path.read_text(encoding="utf-8")
        for match in re.finditer(r"\.newCall\s*\(", source):
            line = source.count("\n", 0, match.start()) + 1
            raw_call_sites.append((path.relative_to(ROOT).as_posix(), line, source))
    assert raw_call_sites, "expected Android HTTP call inventory is empty"
    # MainActivity's backend API is a distinct dashboard/backend transport;
    # the two Binance REST calls must stay in StandaloneRuntime.
    exchange_calls = [
        (path, line) for path, line, source in raw_call_sites
        if path.endswith("StandaloneRuntime.kt")
    ]
    assert len(exchange_calls) == 2, f"Binance raw HTTP call-site inventory changed: {exchange_calls}"
    runtime = (ROOT / "app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text(encoding="utf-8")
    sink = _kotlin_body(runtime, r"private\s+fun\s+signedRequest\s*\(")
    raw_get = _kotlin_body(runtime, r"private\s+fun\s+signedRawGet\s*\(")
    assert ".newCall(request).execute()" in sink
    assert ".newCall(request).execute()" in raw_get
    assert 'Request.Builder()' in sink and 'mutating' in sink
    assert '.post(' in sink and '.delete()' in sink
    assert '.post(' not in raw_get and '.delete()' not in raw_get


def test_android_mutation_wrappers_delegate_to_durable_sink():
    source = (ROOT / "app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text(encoding="utf-8")
    assert re.search(r'private\s+fun\s+signedPost\s*\([\s\S]*?\)\s*:\s*JSONObject\s*=\s*signedRequest\("POST"', source)
    assert re.search(r'private\s+fun\s+signedDelete\s*\([\s\S]*?\)\s*:\s*JSONObject\s*=\s*signedRequest\("DELETE"', source)
    cancel_replace = _kotlin_body(source, r"private\s+fun\s+signedCancelReplace\s*\(")
    assert 'signedPost("/api/v3/order/cancelReplace"' in cancel_replace
    sink = _kotlin_body(source, r"private\s+fun\s+signedRequest\s*\(")
    for required in ("mutationLedger.prepare(", "claimForSubmission(", "State.SUBMITTING", "State.UNKNOWN"):
        assert required in sink, f"durable mutation boundary lost invariant: {required}"


def test_python_exchange_http_mutations_are_centralized_and_recovery_is_read_only():
    client = (ROOT / "binance_client.py").read_text(encoding="utf-8")
    lines = client.splitlines()
    request_line = next((i for i, line in enumerate(lines) if re.search(r"def\s+_request\s*\(", line)), None)
    assert request_line is not None
    assert any("self.session.request(" in line for line in lines[request_line:request_line + 120])

    pattern = re.compile(r"(?:requests|session|self\.session)\s*\.\s*(?:post|delete|put|patch)\s*\(", re.I)
    direct_mutations = []
    for path in ROOT.glob("*.py"):
        if path.name in {"binance_client.py", "telegram_bot.py"} or path.name.startswith("test_"):
            continue
        source = path.read_text(encoding="utf-8")
        for match in pattern.finditer(source):
            direct_mutations.append((path.name, source.count("\n", 0, match.start()) + 1))
    assert not direct_mutations, f"direct Python HTTP mutation bypass candidates: {direct_mutations}"
    recovery = (ROOT / "execution_recovery.py").read_text(encoding="utf-8")
    assert not re.search(r"\.\s*(?:submit_order|create_order|cancel_order|cancel_replace|order_oco)\s*\(", recovery), (
        "recovery worker appears to invoke a mutation method; reconciliation must be read-only"
    )
