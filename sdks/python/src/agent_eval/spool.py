"""A process-safe SQLite queue. Event IDs and counters survive acknowledged deletion."""
import hashlib
import json
import os
import sqlite3
import threading
from dataclasses import dataclass
from pathlib import Path


def encode(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")


@dataclass(frozen=True)
class SpoolStats:
    final_sequence: int
    event_count: int
    dropped_count: int


class FileSpool:
    def __init__(self, path, max_bytes=100 * 1024 * 1024):
        if max_bytes < 1:
            raise ValueError("Invalid spool capacity")
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.max_bytes = max_bytes
        self._lock = threading.RLock()
        self._db = sqlite3.connect(self.path, check_same_thread=False, timeout=5, isolation_level=None)
        os.chmod(self.path, 0o600)
        self._db.executescript("""
            PRAGMA journal_mode=DELETE;
            PRAGMA synchronous=FULL;
            CREATE TABLE IF NOT EXISTS counters(trial TEXT PRIMARY KEY, sequence INTEGER NOT NULL DEFAULT 0, accepted INTEGER NOT NULL DEFAULT 0, dropped INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY,trial TEXT NOT NULL,sequence INTEGER NOT NULL,body BLOB NOT NULL);
            CREATE INDEX IF NOT EXISTS events_trial ON events(trial,sequence);
            CREATE TABLE IF NOT EXISTS executions(trial TEXT PRIMARY KEY,token_hash TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS payloads(id TEXT PRIMARY KEY,trial TEXT NOT NULL,body BLOB NOT NULL);
            CREATE TABLE IF NOT EXISTS payload_links(id TEXT PRIMARY KEY,trial TEXT NOT NULL,artifact_id TEXT NOT NULL);
        """)

    def _transaction(self, callback):
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                result = callback()
                self._db.execute("COMMIT")
                return result
            except BaseException:
                self._db.execute("ROLLBACK")
                raise

    def append(self, trial, event):
        def write():
            self._db.execute("INSERT OR IGNORE INTO counters(trial) VALUES(?)", (trial,))
            old = self._db.execute("SELECT body FROM events WHERE id=?", (event["event_id"],)).fetchone()
            if old:
                return True
            sequence = self._db.execute("SELECT sequence+1 FROM counters WHERE trial=?", (trial,)).fetchone()[0]
            record = dict(event)
            if "sequence" in record:
                record["sequence"] = sequence
            body = encode(record)
            used = self._used_bytes()
            accepted = used + len(body) <= self.max_bytes and sequence <= 10000
            self._db.execute("UPDATE counters SET sequence=?,accepted=accepted+?,dropped=dropped+? WHERE trial=?", (sequence, int(accepted), int(not accepted), trial))
            if accepted:
                self._db.execute("INSERT INTO events(id,trial,sequence,body) VALUES(?,?,?,?)", (record["event_id"], trial, sequence, body))
            return accepted
        return self._transaction(write)

    def _used_bytes(self):
        return self._db.execute("SELECT (SELECT coalesce(sum(length(body)),0) FROM events)+(SELECT coalesce(sum(length(body)),0) FROM payloads)").fetchone()[0]

    def store_payload(self, trial, content):
        identity = hashlib.sha256(trial.encode() + b":" + content).hexdigest()
        def store():
            if self._db.execute("SELECT id FROM payloads WHERE id=?", (identity,)).fetchone():
                return identity
            if self._used_bytes() + len(content) > self.max_bytes:
                return None
            self._db.execute("INSERT INTO payloads(id,trial,body) VALUES(?,?,?)", (identity, trial, content))
            return identity
        return self._transaction(store)

    def pending_payloads(self, trial):
        with self._lock:
            return self._db.execute("SELECT id,body FROM payloads WHERE trial=? ORDER BY id LIMIT 1", (trial,)).fetchall()

    def acknowledge_payload(self, trial, identity, artifact_id):
        def acknowledge():
            self._db.execute("INSERT OR IGNORE INTO payload_links(id,trial,artifact_id) VALUES(?,?,?)", (identity, trial, artifact_id))
            self._db.execute("DELETE FROM payloads WHERE trial=? AND id=?", (trial, identity))
        self._transaction(acknowledge)

    def artifact_ids(self, trial):
        with self._lock:
            return [row[0] for row in self._db.execute("SELECT artifact_id FROM payload_links WHERE trial=? ORDER BY id", (trial,))]

    def record_drop(self, trial):
        def drop():
            self._db.execute("INSERT OR IGNORE INTO counters(trial) VALUES(?)", (trial,))
            self._db.execute("UPDATE counters SET sequence=sequence+1,dropped=dropped+1 WHERE trial=?", (trial,))
        self._transaction(drop)

    def pending(self, trial, limit=100, max_bytes=1024 * 1024):
        with self._lock:
            rows = self._db.execute("SELECT body FROM events WHERE trial=? ORDER BY sequence LIMIT ?", (trial, min(limit, 100))).fetchall()
        # Include the actual JSON envelope and commas in the request budget.
        result, size = [], len(b'{"events":[]}')
        for (body,) in rows:
            added = len(body) + int(bool(result))
            if size + added > max_bytes:
                break
            result.append(json.loads(body))
            size += added
        return result

    def acknowledge(self, trial, event_ids):
        def delete():
            self._db.executemany("DELETE FROM events WHERE trial=? AND id=?", ((trial, event_id) for event_id in event_ids))
        self._transaction(delete)

    def stats(self, trial):
        with self._lock:
            row = self._db.execute("SELECT sequence,accepted,dropped FROM counters WHERE trial=?", (trial,)).fetchone()
        return SpoolStats(*(row or (0, 0, 0)))

    def mark_execution(self, trial, token):
        digest = hashlib.sha256(token.encode()).hexdigest()
        return self._transaction(lambda: self._db.execute("INSERT OR IGNORE INTO executions(trial,token_hash) VALUES(?,?)", (trial, digest)).rowcount == 1)

    def close(self):
        with self._lock:
            self._db.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()
