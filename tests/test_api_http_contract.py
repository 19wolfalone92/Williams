"""Isolated FastAPI wire-contract tests; no app lifespan or exchange calls."""
import sqlite3
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
import server


class FakeDb:
    def __init__(self):
        self.conn = sqlite3.connect(":memory:")
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("CREATE TABLE trades (id INTEGER, symbol TEXT)")
        self.conn.executemany(
            "INSERT INTO trades (id, symbol) VALUES (?, ?)",
            [(1, "BTCUSDT"), (2, "ETHUSDT"), (3, "SOLUSDT")],
        )


class ApiHttpContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(server.app)

    def setUp(self):
        self.token = "isolated-test-token-" + ("x" * 40)
        self.token_patch = patch.object(server, "API_TOKEN", self.token)
        self.token_patch.start()
        self.db = FakeDb()
        self.db_patch = patch.object(server, "db", return_value=self.db)
        self.db_patch.start()

    def tearDown(self):
        self.db_patch.stop()
        self.token_patch.stop()
        self.db.conn.close()

    def test_unknown_route_is_404_json(self):
        response = self.client.get("/__contract_test_unknown__")
        self.assertEqual(response.status_code, 404)
        self.assertIn("detail", response.json())

    def test_sensitive_route_rejects_missing_authorization(self):
        response = self.client.get("/api/v1/trades")
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json().get("detail"), "Unauthorized")

    def test_invalid_limit_is_422(self):
        response = self.client.get(
            "/api/v1/trades?limit=not-an-integer",
            headers={"Authorization": "Bearer " + self.token},
        )
        self.assertEqual(response.status_code, 422)
        self.assertIn("detail", response.json())

    def test_trades_limit_and_duplicate_scalar_query_use_last_value(self):
        response = self.client.get(
            "/api/v1/trades?limit=1&limit=2",
            headers={"Authorization": "Bearer " + self.token},
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.json()), 2)
        self.assertEqual([row["id"] for row in response.json()], [3, 2])


if __name__ == "__main__":
    unittest.main()
