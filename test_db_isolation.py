import os
import sqlite3
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REAL_DB = ROOT / "data" / "trader.sqlite3"

def clean_database():
    fd, path = tempfile.mkstemp(prefix="williams_test_", suffix=".sqlite3")
    os.close(fd)

    p = Path(path)

    # Make sure tests using the normal Database() constructor
    # can be redirected to this isolated database.
    os.environ["WILLIAMS_DB_PATH"] = str(p)

    return p

def remove_database(path):
    try:
        Path(path).unlink(missing_ok=True)
    except Exception:
        pass

if __name__ == "__main__":
    p = clean_database()
    print(p)
    remove_database(p)
