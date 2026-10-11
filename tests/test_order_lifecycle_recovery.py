"""Cancel/replace safety tests against application code, with no network calls."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

import requests

from binance_client import BinanceAPIError, BinanceSpotClient
from db import Database


class CancelReplaceApplicationTests(unittest.TestCase):
    def setUp(self):
        self.client = BinanceSpotClient("TEST_KEY", "TEST_SECRET")
        self.client._request = Mock()

    def test_cancel_replace_uses_stop_on_failure_and_stable_new_client_id(self):
        self.client._request.return_value = {
            "cancelResult": "SUCCESS",
            "newOrderResult": "FAILURE",
            "cancelResponse": {"status": "CANCELED", "orderId": 10},
            "newOrderResponse": {"code": -1013, "msg": "filter failure"},
        }
        result = self.client.cancel_replace(
            "BTCUSDT", 10, "SELL", "LIMIT", quantity="0.001",
            price="100", time_in_force="GTC", new_client_order_id="W4R_TEST_1",
        )
        args, kwargs = self.client._request.call_args
        self.assertEqual(args[:2], ("POST", "/api/v3/order/cancelReplace"))
        self.assertTrue(kwargs.get("signed", args[3] if len(args) > 3 else False))
        params = args[2] if len(args) > 2 else kwargs.get("params")
        self.assertEqual(params["cancelReplaceMode"], "STOP_ON_FAILURE")
        self.assertEqual(params["newClientOrderId"], "W4R_TEST_1")
        self.assertEqual(result["cancelResult"], "SUCCESS")
        self.assertEqual(result["newOrderResult"], "FAILURE")

    def test_cancel_replace_unknown_execution_propagates_without_retry(self):
        self.client._request.side_effect = BinanceAPIError(
            "timeout after request submission", unknown_execution=True
        )
        with self.assertRaises(BinanceAPIError) as ctx:
            self.client.cancel_replace(
                "BTCUSDT", 10, "SELL", "LIMIT", quantity="0.001",
                price="100", time_in_force="GTC", new_client_order_id="W4R_TEST_2",
            )
        self.assertTrue(ctx.exception.unknown_execution)
        self.client._request.assert_called_once()

    def test_duplicate_new_client_order_id_is_not_retried_automatically(self):
        self.client._request.side_effect = BinanceAPIError(
            "duplicate client order id", unknown_execution=True
        )
        for _ in range(1):
            with self.assertRaises(BinanceAPIError):
                self.client.cancel_replace(
                    "BTCUSDT", 10, "SELL", "LIMIT", quantity="0.001",
                    price="100", time_in_force="GTC", new_client_order_id="W4R_DUPLICATE",
                )
        self.client._request.assert_called_once()

    def test_database_write_error_is_visible_to_caller(self):
        with tempfile.TemporaryDirectory() as tmp:
            db = Database(str(Path(tmp) / "orders.sqlite3"))
            try:
                db.conn.execute("DROP TABLE orders")
                db.conn.commit()
                with self.assertRaises(Exception):
                    db.save_order({
                        "symbol": "BTCUSDT", "side": "SELL", "orderId": 10,
                        "clientOrderId": "W4R_DB_FAILURE", "status": "NEW",
                    })
            finally:
                db.conn.close()


if __name__ == "__main__":
    unittest.main()
