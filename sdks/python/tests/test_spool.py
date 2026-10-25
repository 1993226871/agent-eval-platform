from agent_eval.spool import FileSpool


def test_ack_then_delete_and_record_overflow(tmp_path):
    path = tmp_path / "queue.sqlite"
    with FileSpool(path, max_bytes=1024) as spool:
        assert spool.append("trial", {"event_id": "one", "payload": "safe"})
        assert [e["event_id"] for e in spool.pending("trial")] == ["one"]
    with FileSpool(path, max_bytes=1024) as spool:
        assert [e["event_id"] for e in spool.pending("trial")] == ["one"]
        assert not spool.append("trial", {"event_id": "two", "payload": "x" * 2000})
        assert spool.stats("trial").dropped_count == 1
        spool.acknowledge("trial", ["unknown"])
        assert len(spool.pending("trial")) == 1
        spool.acknowledge("trial", ["one"])
        assert spool.pending("trial") == []
        assert spool.stats("trial").event_count == 1


def test_execution_marker_survives_restart_without_storing_token(tmp_path):
    path = tmp_path / "queue.sqlite"
    with FileSpool(path) as spool:
        assert spool.mark_execution("trial", "sensitive-attempt")
        assert not spool.mark_execution("trial", "sensitive-attempt")
    with FileSpool(path) as spool:
        assert not spool.mark_execution("trial", "sensitive-attempt")
    assert b"sensitive-attempt" not in path.read_bytes()
