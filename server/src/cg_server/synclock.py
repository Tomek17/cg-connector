"""Inter-process sync lock: `<data_dir>/sync.lock`, held with `fcntl.flock`.

The scheduler (inside the server) and `cg-server sync` (CLI, a separate process) must never
sync at the same time: both would talk to Garmin, and a new run would mark the other's
`running` run as interrupted. The lock is released by the OS if the process dies.
"""

import fcntl
import os
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

LOCK_FILE = "sync.lock"


class SyncBusy(Exception):
    """Another sync (other process or thread) is running right now."""


@contextmanager
def sync_lock(data_dir: Path) -> Iterator[None]:
    """Hold the sync lock or raise SyncBusy at once (never waits)."""
    data_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd = os.open(data_dir / LOCK_FILE, os.O_RDWR | os.O_CREAT, 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise SyncBusy("Synchronizacja już trwa") from None
        try:
            yield
        finally:
            fcntl.flock(fd, fcntl.LOCK_UN)
    finally:
        os.close(fd)
