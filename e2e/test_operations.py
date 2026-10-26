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
