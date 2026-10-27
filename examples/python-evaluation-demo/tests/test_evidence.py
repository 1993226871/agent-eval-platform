import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from agent_eval import span
from agent_eval.spool import FileSpool
from agent_eval.telemetry import TelemetrySession

from order_agent.agent import make_agent
from order_agent.demo import wait_report
from test_agent import task


class EvidenceTests(unittest.TestCase):
    def test_published_sdk_captures_tool_args_and_unmodified_intermediate_result(self):
        with tempfile.TemporaryDirectory() as directory, FileSpool(Path(directory) / 'spool.sqlite') as spool:
            trial = str(uuid4())
            with TelemetrySession(trial, uuid4().hex, spool).activate(), span('agent', 'agent.run') as root:
                result = make_agent('buggy')(task('A'))
                root.output = result.output
            events = spool.pending(trial)
            tool_start = next(e for e in events if e['name'] == 'query_order' and e['event_type'] == 'span.started')
            tool_end = next(e for e in events if e['name'] == 'query_order' and e['event_type'] == 'span.completed')
            self.assertEqual(tool_start['payload']['input']['args'], ['A'])
            self.assertEqual(tool_end['payload']['output']['amount'], 100)
            self.assertEqual(result.output['amount'], 1)
            self.assertIsNotNone(tool_start['parent_span_id'])

    def test_fail_verdict_does_not_end_wait_before_other_criteria_finish(self):
        class API:
            calls = 0

            def request(self, method, path):
                if path.endswith('limit=100'):
                    self.calls += 1
                    return {'items': [{'verdict': 'FAIL', 'evaluation_state':
                                       'RUNNING' if self.calls == 1 else 'COMPLETED'}]}
                return {'planned_attempts': 1}

        api = API()
        with patch('order_agent.demo.time.sleep'):
            _, trials = wait_report(api, 'test', 10)
        self.assertEqual(api.calls, 2)
        self.assertEqual(trials[0]['evaluation_state'], 'COMPLETED')


if __name__ == '__main__':
    unittest.main()
