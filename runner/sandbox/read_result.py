"""Read a bounded regular result file through an independent docker-exec channel."""
import os
from pathlib import Path
import stat
import sys

path = Path("/output/result.json")
try:
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as source:
        if not stat.S_ISREG(os.fstat(source.fileno()).st_mode):
            raise ValueError()
        raw = source.read(65537)
        if len(raw) > 65536:
            raise ValueError()
    sys.stdout.buffer.write(raw)
    sys.stdout.buffer.flush()
    Path("/output/.ack").touch()
except (OSError, ValueError):
    raise SystemExit(3)
