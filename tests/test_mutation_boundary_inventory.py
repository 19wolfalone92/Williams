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


def _python_body(source: str, signature: str) -> str:
    lines = source.splitlines()
    for index, line in enumerate(lines):
        if re.search(signature, line):
            indent = len(line) - len(line.lstrip())
            result = []
            for candidate in lines[index + 1:]:
                if candidate.strip() and len(candidate) - len(candidate.lstrip()) <= indent:
                    break
                result.append(candidate)
            return "\n".join(result)
    raise AssertionError(f"required Python boundary function missing: {signature}")


def test_android_raw_http_execution_is_contained_in_signed_request_sink():
    source_root = ROOT / "app/src/main"
    raw_call_sites = []
    for path in source_root.rglob("*.kt"):
        source = path.read_text(encoding="utf-8")
        for match in re.finditer(r"\.newCall\s*\(", source):
            raw_call_sites.append((path.relative_to(ROOT).as_posix(), source.count("\n", 0, match.start()) + 1))
    assert raw_call_sites, "expected Android HTTP call inventory is empty"
    assert all(path == "app/src/main/java/com/williamsbot/StandaloneRuntime.kt" for path, _ in raw_call_sites), raw_call_sites
    runtime = (ROOT / "app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text(encoding="utf-8")
    sink = _kotlin_body(runtime, r"private\s+fun\s+signedRequest\s*\(")
    assert ".newCall(" in sink, "raw HTTP transport moved outside the approved signedRequest sink"


def test_android_mutation_wrappers_delegate_to_single_durable_sink():
    source = (ROOT / "app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text(encoding="utf-8")
    for signature in (
        r"private\s+fun\s+signedPost\s*\(",
        r"private\s+fun\s+signedDelete\s*\(",
        r"private\s+fun\s+signedCancelReplace\s*\(",
    ):
        body = _kotlin_body(source, signature)
        assert "signedRequest(" in body, f"mutation wrapper bypasses durable sink: {signature}"
    sink = _kotlin_body(source, r"private\s+fun\s+signedRequest\s*\(")
    for required in ("mutationLedger.prepare(", "claimForSubmission(", "State.SUBMITTING", "State.UNKNOWN"):
        assert required in sink, f"durable mutation boundary lost invariant: {required}"


def test_python_rest_transport_is_centralized_and_recovery_is_read_only():
    client = (ROOT / "binance_client.py").read_text(encoding="utf-8")
    request_body = _python_body(client, r"def\s+_request\s*\(")
    assert "self.session.request(" in request_body
    production_files = [
        p for p in ROOT.glob("*.py")
        if p.name not in {"binance_client.py"} and not p.name.startswith("test_")
    ]
    direct_mutations = []
    pattern = re.compile(r"(?:requests|session|self\.session)\s*\.\s*(?:post|delete|put|patch)\s*\(", re.I)
    for path in production_files:
        content = path.read_text(encoding="utf-8")
        for match in pattern.finditer(content):
            direct_mutations.append((path.name, content.count("\n", 0, match.start()) + 1))
    assert not direct_mutations, f"direct Python HTTP mutation bypass candidates: {direct_mutations}"
    recovery = (ROOT / "execution_recovery.py").read_text(encoding="utf-8")
    assert not re.search(r"\.\s*(?:submit_order|create_order|cancel_order|cancel_replace|order_oco)\s*\(", recovery), (
        "recovery worker appears to invoke a mutation method; reconciliation must be read-only"
    )
