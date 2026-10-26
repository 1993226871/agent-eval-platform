def test_verified_https_presigned_artifacts_and_fresh_restore(platform):
    outcome = platform.backup_restore()
    assert outcome["database_rows_equal"]
    assert outcome["object_bytes_equal"]
    assert outcome["private_data_restored"]


def test_capacity_fifty_workers_twenty_trials_ten_thousand_events(platform):
    outcome = platform.capacity()
    assert outcome["online_workers"] == 50
    assert outcome["running_trials"] == 20
    assert outcome["accepted_events"] == 10000
    assert outcome["unexpected_errors"] == 0
    assert outcome["fully_graded_attempts"] == 20
    assert outcome["complete_snapshots"] == 20


def test_experiment_deletion_scrubs_real_runner_cache_before_deleted(platform):
    import json
    import subprocess
    from uuid import uuid4
    from conftest import wait_for

    assert platform.runner_container, "NOT VERIFIED: qualified Linux/runsc Runner required"
    marker = "private-evidence-" + uuid4().hex
    code = 'def evaluate(context):\n    print(' + repr(marker) + ')\n    return {"status":"SCORED","score":1,"reason":"Checked","evidence_refs":["output"]}\n'
    resources = platform.resources("Runner privacy", types=("PYTHON",), python_code=code)
    platform.start_sdk("python", resources, label="runner-privacy")
    trial = platform.trial_report(resources)
    assert trial["criteria"][0]["status"] == "SCORED"
    experiment = resources["experiment"]
    job = platform.sql(f"select job_id from runner_executions where experiment_id='{experiment}'")
    assert str(__import__('uuid').UUID(job)) == job

    def cache():
        script = "import sqlite3,json,sys;db=sqlite3.connect(sys.argv[1]);print(json.dumps(db.execute('select response,evidence_deleted from runs where id=?',(sys.argv[2],)).fetchone()))"
        output = subprocess.check_output(["docker", "exec", platform.runner_container, "python", "-c", script, str(platform.root / "runner/runs.sqlite"), job], text=True)
        return json.loads(output)

    before = cache()
    assert before[1] == 0 and marker in before[0]
    platform.request(platform.user, "DELETE", "/experiments/" + experiment, {"idempotency_key": str(uuid4())})
    # Advance only this disposable fixture's cleanup grace; production keeps its grace.
    platform.sql(f"update experiment_deletions set not_before=now()-interval '1 second',next_cleanup_at=now()-interval '1 second' where experiment_id='{experiment}'")
    wait_for(lambda: platform.sql(f"select status from experiments where id='{experiment}'"), lambda status: status == "DELETED")
    assert cache() == [None, 1]
    assert platform.sql(f"select count(*) from runner_executions where experiment_id='{experiment}' and cleaned_at is not null") == "1"
