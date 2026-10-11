from __future__ import annotations

import json
from pathlib import Path

from mutation_registry_gate import analyze_python_sources, load_registry


ROOT = Path(__file__).resolve().parent
REGISTRY = load_registry()


def analyze(source: str, path: str = "feature.py", *, extra=None):
    sources = {path: source}
    if extra:
        sources.update(extra)
    return analyze_python_sources(sources, REGISTRY)


def test_positive_registered_client_transport_and_endpoint_are_accepted():
    source = '''
class BinanceSpotClient:
    def _request(self, method, path):
        return self.session.request(method, self.base_url + path)
    def order(self):
        return self._request("POST", "/api/v3/order")
'''
    import copy
    registry = copy.deepcopy(REGISTRY)
    registry["python_mutation_endpoints"] = [
        item for item in registry["python_mutation_endpoints"]
        if item["method"] == "POST" and item["endpoint"] == "/api/v3/order"
    ]
    errors = analyze_python_sources({"binance_client.py": source}, registry)
    assert errors == []


def test_negative_01_direct_requests_post_is_rejected():
    assert any("direct network mutation primitive" in e for e in analyze("import requests\ndef enter(): requests.post('/api/v3/order')"))


def test_negative_02_wrapper_cannot_hide_direct_mutation():
    src = "import requests\ndef helper(url): requests.post(url)\ndef enter(): helper('/api/v3/order')"
    assert any("direct network mutation primitive" in e for e in analyze(src))


def test_negative_03_alias_to_network_mutator_is_rejected():
    src = "import requests\nsubmit = requests.post\ndef enter(url): submit(url)"
    errors = analyze(src)
    assert errors, "alias/dynamic mutation path must fail closed"


def test_negative_04_alternative_http_client_is_rejected():
    src = "import httpx\ndef enter(url): httpx.post(url)"
    assert any("direct network mutation primitive" in e for e in analyze(src))


def test_negative_05_execution_barrier_bypass_is_rejected_at_transport():
    src = "import requests\ndef direct(): requests.Session().request('POST', '/api/v3/order')"
    assert any("raw/dynamic HTTP request outside registered transport sink" in e for e in analyze(src))


def test_negative_06_identity_bypass_direct_transport_is_rejected():
    src = "import requests\ndef mutate_without_identity(): requests.post('/api/v3/order')"
    assert any("direct network mutation primitive" in e for e in analyze(src))


def test_negative_07_dynamic_dispatch_is_rejected():
    src = "def enter(client, method, endpoint): return client._request(method, endpoint)"
    assert any("direct _request call bypasses registered Binance client" in e for e in analyze(src))


def test_negative_08_unknown_mutation_endpoint_is_rejected():
    src = '''
class BinanceSpotClient:
    def order(self):
        return self._request("POST", "/api/v3/newUnknownMutation")
'''
    assert any("unregistered Binance mutation endpoint" in e for e in analyze(src, "binance_client.py"))


def test_negative_09_new_sink_not_in_registry_is_rejected():
    src = '''
class BinanceSpotClient:
    def _request(self, method, path):
        return self.session.request(method, self.base_url + path)
    def replace_everything(self):
        return self._request("PUT", "/api/v3/order")
'''
    assert any("unregistered Binance mutation endpoint" in e for e in analyze(src, "binance_client.py"))


def test_negative_10_registry_entry_without_evidence_is_not_accepted():
    broken = json.loads(json.dumps(REGISTRY))
    broken["python_mutation_endpoints"][0]["evidence_tests"] = []
    # Missing-evidence entries are excluded from the accepted set, so the call fails.
    source = 'class BinanceSpotClient:\n def order(self): return self._request("POST", "/api/v3/order")'
    errors = analyze_python_sources({"binance_client.py": source}, broken)
    assert errors


def test_negative_11_sink_moved_to_other_production_module_is_rejected():
    src = '''
class BinanceSpotClient:
    def order(self):
        return self._request("POST", "/api/v3/order")
'''
    assert any("direct _request call bypasses registered Binance client" in e for e in analyze(src, "alternate_exchange.py"))


def test_negative_12_wrapper_with_dynamic_endpoint_is_rejected():
    src = '''
class BinanceSpotClient:
    def order(self, endpoint):
        return self._request("POST", endpoint)
'''
    assert any("dynamic mutation method/path is unresolved" in e for e in analyze(src, "binance_client.py"))


def test_android_execution_lock_is_still_explicitly_blocked():
    text = (ROOT / REGISTRY["android_lock_contract"]["path"]).read_text(encoding="utf-8")
    assert all(x in text for x in REGISTRY["android_lock_contract"]["required_literals"])
    assert "private val androidDurableMutationContractVerified = true" not in text
