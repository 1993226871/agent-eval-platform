import unittest
from datetime import datetime, timedelta, timezone
from threading import Event

from agent_eval import TaskContext
from order_agent.agent import make_agent, query_order
from order_agent.catalog import dataset, evaluator_definitions, suite_content


def task(order_id):
    return TaskContext('test', '查询订单', {'order_id': order_id},
                       datetime.now(timezone.utc) + timedelta(seconds=30), Event())


class AgentTests(unittest.TestCase):
    def test_all_reference_cases(self):
        for case in dataset()['cases']:
            with self.subTest(case=case['input']):
                self.assertEqual(make_agent('correct')(task(case['input']['order_id'])).output,
                                 case['expected'])

    def test_bug_changes_amount_but_not_status(self):
        result = make_agent('buggy')(task('A')).output
        self.assertEqual(result['amount'], 1)
        self.assertEqual(result['status'], 'paid')

    def test_tool_returns_copy(self):
        query_order('A')['amount'] = -1
        self.assertEqual(query_order('A')['amount'], 100)

    def test_cancellation_is_honored(self):
        # Actual SDK context receives a caller-owned cancellation Event.
        cancelled = Event(); cancelled.set()
        context = TaskContext('test', '', {'order_id': 'A'}, datetime.now(timezone.utc), cancelled)
        self.assertEqual(make_agent('correct')(context).output, {'status': 'cancelled'})

    def test_invalid_variant_is_rejected(self):
        with self.assertRaises(ValueError):
            make_agent('typo')


class CatalogTests(unittest.TestCase):
    def test_gold_is_not_exposed_as_agent_input(self):
        for case in dataset()['cases']:
            self.assertEqual(set(case['input']), {'order_id'})
            self.assertNotIn('expected', case['input'])

    def test_outcome_compares_with_platform_gold(self):
        definitions = evaluator_definitions()
        self.assertEqual(definitions[0]['config'], {
            'operator': 'eq', 'selector': '/output', 'expected_selector': '/expected'})
        self.assertIn('expected', definitions[0]['required_evidence'])

    def test_tool_arguments_match_task_input(self):
        definition = evaluator_definitions()[2]
        self.assertEqual(definition['config']['selector'], '/args/0')
        self.assertEqual(definition['config']['expected_selector'], '/task_input/order_id')

    def test_suite_requires_all_three_dimensions_to_pass(self):
        entries = suite_content(['one', 'two', 'three'])['entries']
        self.assertEqual(len(entries), 3)
        self.assertTrue(all(entry['required_to_pass'] for entry in entries))
        self.assertEqual([entry['dimension'] for entry in entries], ['outcome', 'process', 'process'])


if __name__ == '__main__':
    unittest.main()
