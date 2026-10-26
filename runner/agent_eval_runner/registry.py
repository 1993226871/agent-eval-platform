import json
import sqlite3
from contextlib import closing
from pathlib import Path
from .validation import RunnerValidationError


class RunRegistry:
    def __init__(self, path):
        self.path = Path(path)
        with closing(self._connection()) as db:
            db.execute("CREATE TABLE IF NOT EXISTS runs(id TEXT PRIMARY KEY,request_hash TEXT NOT NULL,state TEXT NOT NULL,response TEXT)")
            if "evidence_deleted" not in {row[1] for row in db.execute("PRAGMA table_info(runs)")}:
                db.execute("ALTER TABLE runs ADD COLUMN evidence_deleted INTEGER NOT NULL DEFAULT 0")

    def _connection(self):
        db = sqlite3.connect(self.path, isolation_level=None, timeout=5)
        db.execute("PRAGMA synchronous=FULL")
        db.execute("PRAGMA secure_delete=ON")
        return db

    def lookup(self, identity, digest):
        with closing(self._connection()) as db:
            row = db.execute("SELECT request_hash,state,response,evidence_deleted FROM runs WHERE id=?", (identity,)).fetchone()
        if row is None:
            return None
        if row[3]:
            return {"job_id": identity, "state": "ERROR", "code": "EVIDENCE_DELETED", "result": None}
        if row[0] != digest:
            raise RunnerValidationError("RUN_KEY_CONFLICT")
        if row[2] is not None:
            return json.loads(row[2])
        return {"job_id": identity, "state": "IN_PROGRESS", "code": "RUN_IN_PROGRESS", "result": None}

    def reserve(self, identity, digest):
        with closing(self._connection()) as db:
            return db.execute("INSERT OR IGNORE INTO runs(id,request_hash,state) VALUES(?,?,'RUNNING')", (identity, digest)).rowcount == 1

    def complete(self, identity, response):
        with closing(self._connection()) as db:
            db.execute("UPDATE runs SET state='COMPLETED',response=? WHERE id=? AND state='RUNNING' AND evidence_deleted=0", (json.dumps(response, ensure_ascii=False, allow_nan=False), identity))

    def scrub(self, identity):
        with closing(self._connection()) as db:
            db.execute("INSERT INTO runs(id,request_hash,state,response,evidence_deleted) VALUES(?,'','DELETED',NULL,1) ON CONFLICT(id) DO UPDATE SET state='DELETED',response=NULL,evidence_deleted=1", (identity,))

    def recover_unknown(self):
        with closing(self._connection()) as db:
            rows = db.execute("SELECT id FROM runs WHERE state='RUNNING'").fetchall()
            for (identity,) in rows:
                response = {"job_id": identity, "state": "ERROR", "code": "EXECUTION_UNKNOWN", "result": None}
                db.execute("UPDATE runs SET state='COMPLETED',response=? WHERE id=? AND state='RUNNING'", (json.dumps(response), identity))
        return [identity for (identity,) in rows]
