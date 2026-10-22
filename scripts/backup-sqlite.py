"""Create a consistent SQLite online backup, including committed WAL data.

Usage: python3 backup-sqlite.py SOURCE.sqlite NEW-BACKUP.sqlite
Run as the database owner. The destination must not already exist.
"""
import os
from contextlib import closing
from pathlib import Path
import sqlite3
import sys


def backup(source_name, destination_name):
    source = Path(source_name).resolve(strict=True)
    destination = Path(destination_name).absolute()
    if source == destination.resolve():
        raise ValueError("Source and destination must differ")
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    os.close(descriptor)
    try:
        with closing(sqlite3.connect(source.as_uri() + "?mode=ro", uri=True)) as original:
            with closing(sqlite3.connect(destination)) as snapshot:
                original.backup(snapshot, pages=100, sleep=0.1)
                result = snapshot.execute("PRAGMA integrity_check").fetchall()
                if result != [("ok",)]:
                    raise RuntimeError("Backup integrity check failed")
        print(f"Verified SQLite backup: {destination}")
    except BaseException:
        destination.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    backup(sys.argv[1], sys.argv[2])
