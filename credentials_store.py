import base64
import json
import os
from pathlib import Path
from threading import RLock

from cryptography.fernet import Fernet, InvalidToken


class CredentialStore:
    """Small encrypted-at-rest store for Binance credentials.

    The encryption key is preferably supplied by CREDENTIALS_ENCRYPTION_KEY.
    For a self-contained VPS install, if it is absent a random key is generated
    in data/credential_master.key with restrictive permissions and reused after restart.
    """

    def __init__(self, path="data/binance_credentials.enc", key_path="data/credential_master.key"):
        self.path = Path(path)
        self.key_path = Path(key_path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.key_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = RLock()
        self._fernet = Fernet(self._load_key())

    def _load_key(self) -> bytes:
        raw = os.getenv("CREDENTIALS_ENCRYPTION_KEY", "").strip()
        if raw:
            try:
                Fernet(raw.encode())
                return raw.encode()
            except Exception as exc:
                raise RuntimeError("CREDENTIALS_ENCRYPTION_KEY is invalid.") from exc

        if self.key_path.exists():
            key = self.key_path.read_bytes().strip()
            try:
                Fernet(key)
                return key
            except Exception as exc:
                raise RuntimeError("Stored credential encryption key is invalid.") from exc

        key = Fernet.generate_key()
        self.key_path.write_bytes(key + b"\n")
        try:
            os.chmod(self.key_path, 0o600)
        except OSError:
            pass
        return key

    def save(self, api_key: str, api_secret: str, testnet: bool) -> None:
        payload = json.dumps({
            "api_key": api_key,
            "api_secret": api_secret,
            "testnet": bool(testnet),
        }, separators=(",", ":")).encode()
        encrypted = self._fernet.encrypt(payload)
        tmp = self.path.with_suffix(self.path.suffix + ".tmp")
        with self._lock:
            tmp.write_bytes(encrypted + b"\n")
            try:
                os.chmod(tmp, 0o600)
            except OSError:
                pass
            tmp.replace(self.path)
            try:
                os.chmod(self.path, 0o600)
            except OSError:
                pass

    def load(self):
        with self._lock:
            if not self.path.exists():
                return None
            try:
                payload = self._fernet.decrypt(self.path.read_bytes().strip())
                data = json.loads(payload.decode())
                if not data.get("api_key") or not data.get("api_secret"):
                    return None
                return {
                    "api_key": str(data["api_key"]),
                    "api_secret": str(data["api_secret"]),
                    "testnet": bool(data.get("testnet", True)),
                }
            except (InvalidToken, ValueError, json.JSONDecodeError) as exc:
                raise RuntimeError("Encrypted Binance credentials cannot be decrypted.") from exc

    def clear(self) -> None:
        with self._lock:
            try:
                self.path.unlink()
            except FileNotFoundError:
                pass
