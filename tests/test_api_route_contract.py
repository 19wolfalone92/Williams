"""Static API route contract checks; no app/server startup or exchange calls required."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
PYTHON_SOURCE = (ROOT / "server.py").read_text(encoding="utf-8")
ANDROID_SOURCE = (
    ROOT / "app/src/main/java/com/williamsbot/StandaloneRuntime.kt"
).read_text(encoding="utf-8")


def python_routes():
    # Match simple and multiline FastAPI decorators, keeping method/path only.
    routes = set()
    for match in re.finditer(
        r"@app\.(get|post|put|patch|delete|websocket)\s*\((.*?)\)",
        PYTHON_SOURCE,
        re.IGNORECASE | re.DOTALL,
    ):
        method = match.group(1).upper()
        path = re.search(r"""['"]([^'"]+)['"]""", match.group(2))
        if path:
            routes.add((method, path.group(1)))
    return routes


def android_routes():
    # The embedded dispatcher uses explicit method/path predicates, plus a
    # small number of method-agnostic paths handled by its local control flow.
    routes = set()
    for match in re.finditer(
        r'method\s*==\s*"(GET|POST|PUT|PATCH|DELETE)"\s*&&\s*'
        r'path\s*==\s*"([^"]+)"',
        ANDROID_SOURCE,
    ):
        routes.add((match.group(1), match.group(2)))
    for match in re.finditer(
        r'path\s*==\s*"([^"]+)"',
        ANDROID_SOURCE,
    ):
        path = match.group(1)
        # These dispatch branches intentionally accept their verb via the
        # surrounding request handling, so capture the verb from nearby source.
        context = ANDROID_SOURCE[max(0, match.start() - 90):match.start()]
        method = re.search(r'method\s*==\s*"(GET|POST|PUT|PATCH|DELETE)"', context)
        if method:
            routes.add((method.group(1), path))
    return routes


class ApiRouteContractTests(unittest.TestCase):
    def test_python_required_routes_are_registered(self):
        routes = python_routes()
        expected = {
            ("GET", "/api/v1/health"),
            ("GET", "/api/v1/status"),
            ("GET", "/api/v1/orders"),
            ("POST", "/api/v1/control/panic"),
            ("DELETE", "/api/v1/config/binance"),
        }
        self.assertTrue(expected <= routes, f"Missing Python routes: {expected - routes}")

    def test_android_shared_routes_are_registered(self):
        routes = android_routes()
        expected = {
            ("GET", "/api/v1/health"),
            ("GET", "/api/v1/status"),
            ("GET", "/api/v1/trades"),
            ("POST", "/api/v1/control/kill"),
            ("DELETE", "/api/v1/config/binance"),
        }
        self.assertTrue(expected <= routes, f"Missing Android routes: {expected - routes}")

    def test_known_differences_are_explicit_not_silently_assumed_parity(self):
        py = python_routes()
        android = android_routes()
        self.assertIn(("GET", "/api/v1/orders"), py)
        self.assertNotIn(("GET", "/api/v1/orders"), android)
        self.assertIn(("POST", "/api/v1/control/panic"), py)
        self.assertNotIn(("POST", "/api/v1/control/panic"), android)
        self.assertIn(("GET", "/api/v1/quant/health"), py)
        self.assertNotIn(("GET", "/api/v1/quant/health"), android)

    def test_android_has_no_unsupported_order_mutation_route(self):
        # The local dispatcher must not grow an order-submission endpoint as
        # an incidental result of parity work.
        routes = android_routes()
        self.assertNotIn(("POST", "/api/v1/orders"), routes)
        self.assertNotIn(("POST", "/api/v1/order"), routes)

    def test_auth_contract_is_declared_for_sensitive_python_routes(self):
        # Slice each decorator by the next function definition rather than
        # stopping at the first ')' (Depends(auth) contains nested parens).
        for path in (
            "/api/v1/orders",
            "/api/v1/control/panic",
            "/api/v1/config/binance",
            "/api/v1/control/sell",
        ):
            path_index = PYTHON_SOURCE.find(path)
            self.assertNotEqual(path_index, -1, f"Route path missing: {path}")
            decorator_start = PYTHON_SOURCE.rfind("@app.", 0, path_index)
            function_start = PYTHON_SOURCE.find("\ndef ", path_index)
            self.assertNotEqual(decorator_start, -1, f"Route decorator missing: {path}")
            self.assertNotEqual(function_start, -1, f"Route function missing: {path}")
            decorator = PYTHON_SOURCE[decorator_start:function_start]
            self.assertIn("Depends(auth)", decorator, f"Auth missing for {path}")



if __name__ == "__main__":
    unittest.main()
