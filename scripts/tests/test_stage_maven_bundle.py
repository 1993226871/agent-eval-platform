"""Network-boundary tests: no real credentials or network requests."""
import hashlib
import importlib.util
from io import BytesIO
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("stage_maven_bundle", Path(__file__).resolve().parents[1] / "stage_maven_bundle.py")
stage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(stage)


class StageBundleTest(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(stage.os.environ, {"GITHUB_STEP_SUMMARY": ""})
        environment.start()
        self.addCleanup(environment.stop)
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.bundle = Path(self.scratch.name) / "bundle.zip"
        self.bundle.write_bytes(b"test bundle")
        self.digest = hashlib.sha256(self.bundle.read_bytes()).hexdigest()

    def test_user_managed_submission_waits_for_validation_without_publishing(self):
        responses = [BytesIO(b"11111111-2222-3333-4444-555555555555"), BytesIO(b'{"deploymentState":"PENDING"}'), BytesIO(b'{"deploymentState":"VALIDATED"}')]
        with patch.object(stage, "open_request", side_effect=responses) as network, patch.object(stage.time, "sleep"):
            result = stage.submit(self.bundle, self.digest, "test-user", "test-password")
        self.assertEqual(result, "11111111-2222-3333-4444-555555555555")
        urls = [call.args[0].full_url for call in network.call_args_list]
        self.assertEqual(urls[0], "https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED")
        self.assertTrue(all("/status?id=" in url for url in urls[1:]))
        self.assertEqual(network.call_args_list[0].args[0].data.count(b"test bundle"), 1)

    def test_uncertain_upload_is_not_retried(self):
        with patch.object(stage, "open_request", side_effect=TimeoutError) as network:
            with self.assertRaisesRegex(RuntimeError, "outcome may be unknown"):
                stage.submit(self.bundle, self.digest, "test-user", "test-password")
        self.assertEqual(network.call_count, 1)

    def test_modified_bundle_never_reaches_network(self):
        self.bundle.write_bytes(b"tampered bundle")
        with patch.object(stage, "open_request") as network:
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                stage.submit(self.bundle, self.digest, "test-user", "test-password")
        network.assert_not_called()

    def test_validation_failure_does_not_publish(self):
        responses = [BytesIO(b"11111111-2222-3333-4444-555555555555"), BytesIO(b'{"deploymentState":"FAILED"}')]
        with patch.object(stage, "open_request", side_effect=responses) as network:
            with self.assertRaisesRegex(RuntimeError, "FAILED"):
                stage.submit(self.bundle, self.digest, "test-user", "test-password")
        self.assertEqual(network.call_count, 2)


if __name__ == "__main__":
    unittest.main()
