import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('release_version', Path(__file__).with_name('next-release-version.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseVersionTest(unittest.TestCase):
    source = 'val defaultVersionName = "1.0.6-test"'

    def test_first_release_uses_source_baseline(self):
        self.assertEqual(('1.0.7-test', 1000007, 'v1.0.7-test'), release.next_version(self.source, []))

    def test_historical_and_current_tag_names_are_compared_numerically(self):
        tags = ['1.0.2_test', 'LumenJot_1.0.3_test', 'v1.0.9-test', 'v1.0.10-test', 'unrelated']
        self.assertEqual('1.0.11-test', release.next_version(self.source, tags)[0])

    def test_source_baseline_can_advance_major_minor(self):
        self.assertEqual('2.1.1-test', release.next_version('val defaultVersionName = "2.1.0-test"', ['v1.9.9'])[0])

    def test_rollover_preserves_increasing_android_code(self):
        name, code, _ = release.next_version(self.source, ['v1.2.999-test'])
        self.assertEqual(('1.3.0-test', 1003000), (name, code))
        self.assertGreater(code, 1002999)

    def test_missing_baseline_and_android_overflow_fail_before_publication(self):
        with self.assertRaises(ValueError):
            release.next_version('', [])
        with self.assertRaises(ValueError):
            release.next_version(self.source, ['v2100.0.0-test'])


if __name__ == '__main__':
    unittest.main()
