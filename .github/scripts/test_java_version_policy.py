"""Contract fixtures for the Java-specific Renovate rules; no external packages."""

import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
RULES = json.loads((ROOT / 'renovate.json').read_text())['packageRules']


def matches(pattern, value):
    if pattern.startswith('/') and pattern.endswith('/'):
        return re.search(pattern[1:-1], value) is not None
    return pattern == value


def allowed(datasource, package, current, candidate):
    # Exercise only Java-specific rules. Maven compatibility floors are checked
    # separately by checkServerJvmFloor; these fixtures cover Renovate patterns.
    constraint = None
    for rule in RULES:
        if datasource not in rule.get('matchDatasources', []):
            continue
        if not any(matches(p, package) for p in rule.get('matchPackageNames', [])):
            continue
        if 'matchCurrentValue' in rule and not matches(rule['matchCurrentValue'], current):
            continue
        constraint = rule.get('allowedVersions', constraint)
    return constraint is None or matches(constraint, candidate)


class JavaVersionPolicyTest(unittest.TestCase):
    def test_each_ci_lane_stays_on_its_selected_major(self):
        for package in ['java-jdk', 'java-jre']:
            for current in ['17', '17.0.20+101', '21', '21.0.12+101.0.LTS']:
                major = current.split('.')[0]
                for candidate in [major, major + '.0.99+102.0.LTS']:
                    with self.subTest(package=package, current=current, candidate=candidate):
                        self.assertTrue(allowed('java-version', package, current, candidate))
                for candidate in ['22', '25', '25.0.4+101.0.LTS', '26', major + '0.0.1']:
                    with self.subTest(package=package, current=current, candidate=candidate):
                        self.assertFalse(allowed('java-version', package, current, candidate))

    def test_java17_lane_does_not_silently_move_to_21(self):
        self.assertFalse(allowed('java-version', 'java-jdk', '17', '21.0.12+101.0.LTS'))

    def test_container_keeps_java21_maintenance_and_tag_flavors(self):
        current = '21.0.12_8-jdk'
        for candidate in ['21', '21-jdk', '21-jre', '21.0.99_8-jdk', '21.0.99_8-jdk-noble']:
            self.assertTrue(allowed('docker', 'eclipse-temurin', current, candidate), candidate)
        for candidate in ['17-jdk', '25-jdk', '25.0.4_8-jdk', '210-jdk', 'latest']:
            self.assertFalse(allowed('docker', 'eclipse-temurin', current, candidate), candidate)

    def test_other_dependencies_are_outside_this_policy(self):
        self.assertTrue(allowed('github-tags', 'actions/setup-java', '6', '7'))
        self.assertTrue(allowed('docker', 'node', '24', '25'))
        self.assertTrue(allowed('maven', 'example:java-jdk', '21', '25'))


if __name__ == '__main__':
    unittest.main()
