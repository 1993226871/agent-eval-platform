def test_uncertain_sdk_execution_is_not_replayed_after_restart(platform):
    assert platform.crashed_execution_calls() == 1


def test_sent_unknown_judge_call_is_not_resent_or_double_charged(platform):
    outcome = platform.unknown_model_restart()
    assert outcome["provider_calls"] == 1
    assert outcome["consumed_delta"] == 15
    assert outcome["reserved_after"] == 0
