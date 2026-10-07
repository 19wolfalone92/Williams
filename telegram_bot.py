import logging
import queue
import threading

import requests

log = logging.getLogger("williams-telegram")


class Telegram:
    """Non-blocking Telegram notifier.

    Trading code only enqueues a message. Network I/O happens on a dedicated
    daemon worker so Telegram latency/outages can never block order execution.
    """

    def __init__(self, token, chat_id, max_queue=100):
        self.token = str(token or "")
        self.chat_id = str(chat_id or "")
        self.base = f"https://api.telegram.org/bot{self.token}"
        self._queue = queue.Queue(maxsize=max(1, int(max_queue)))
        self._stop = threading.Event()
        self._worker = None
        self._worker_lock = threading.Lock()

    def _ensure_worker(self):
        if self._worker is not None and self._worker.is_alive():
            return
        with self._worker_lock:
            if self._worker is None or not self._worker.is_alive():
                self._worker = threading.Thread(
                    target=self._worker_loop,
                    name="williams-telegram",
                    daemon=True,
                )
                self._worker.start()

    def _worker_loop(self):
        while not self._stop.is_set():
            try:
                text = self._queue.get(timeout=0.5)
            except queue.Empty:
                continue
            try:
                r = requests.post(
                    self.base + "/sendMessage",
                    data={"chat_id": self.chat_id, "text": text},
                    timeout=15,
                )
                r.raise_for_status()
            except Exception as exc:
                # Notification failure is intentionally isolated from trading.
                log.error("Telegram notification failed: %s", exc)
            finally:
                self._queue.task_done()

    def send(self, text):
        if not self.token or not self.chat_id:
            return False
        self._ensure_worker()
        try:
            self._queue.put_nowait(str(text))
            return True
        except queue.Full:
            # Drop the oldest notification instead of blocking the trading path.
            try:
                self._queue.get_nowait()
                self._queue.task_done()
            except queue.Empty:
                pass
            try:
                self._queue.put_nowait(str(text))
                return True
            except queue.Full:
                return False

    def close(self):
        self._stop.set()
