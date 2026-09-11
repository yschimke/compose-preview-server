"""Staging must never select a failed build or silently drop a KMP target publication."""

import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "stage_local", Path(__file__).resolve().parents[1] / "stage-local-dependency.py"
)
stage_local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage_local)


class StageTest(unittest.TestCase):
    def test_stages_actual_publications_and_preserves_previous_selection_on_failure(self):
        with tempfile.TemporaryDirectory(prefix="local dependencies ") as directory:
            root = Path(directory)
            checkout = root / "checkout"
            checkout.mkdir()
            (checkout / "gradlew").touch()
            output = root / "staging"
            artifact = "ui-builder-protocol"

            def publish(command, **kwargs):
                if command[0] == "git":
                    return subprocess.CompletedProcess(command, 0, stdout="fixture\n")
                version = kwargs["env"]["PLUGIN_VERSION"]
                self.assertEqual(version, kwargs["env"]["CORE_LINE_VERSION"])
                self.assertEqual(kwargs["cwd"], checkout.resolve())
                self.assertEqual(
                    command[-1], f":{artifact}:publishAllPublicationsToUiBuilderLocalRepository"
                )
                repository = Path(next(c.split("=", 1)[1] for c in command if c.startswith("-P")))
                published = [artifact + suffix for suffix in ("", "-jvm", "-wasm-js")]
                if artifact == "screen-model":
                    self.assertIn(
                        ":gradle-plugin:preview-discovery:publishAllPublicationsToUiBuilderLocalRepository",
                        command,
                    )
                    published.append("preview-discovery")
                for module in published:
                    target = repository / "ee/schimke/composeai" / module / version
                    target.mkdir(parents=True)
                    # Snapshot POM filenames are timestamped; their identity remains -SNAPSHOT.
                    (target / f"{module}-20260910.100000-1.pom").write_text(
                        '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                        f"<groupId>ee.schimke.composeai</groupId><artifactId>{module}</artifactId>"
                        f"<version>{version}</version></project>"
                    )
                return subprocess.CompletedProcess(command, 0)

            with patch.object(stage_local.subprocess, "run", side_effect=publish):
                manifest = stage_local.stage(checkout, [f":{artifact}"], output)
                first = manifest.read_text()
                self.assertIn(":ui-builder-protocol-wasm-js:", first)
                artifact = "screen-model"
                (checkout / "gradle-plugin/preview-discovery").mkdir(parents=True)
                stage_local.stage(checkout, [f":{artifact}"], output)
                second = manifest.read_text()
                self.assertIn(":ui-builder-protocol-wasm-js:", second)
                self.assertIn(":screen-model-wasm-js:", second)
                self.assertIn(":preview-discovery:", second)
                stage_local.stage(checkout, [f":{artifact}"], output)
                third = manifest.read_text()
                self.assertNotEqual(second, third)
                self.assertEqual(third.count(":screen-model-wasm-js:"), 1)

            with patch.object(stage_local.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "gradlew")):
                with self.assertRaises(subprocess.CalledProcessError):
                    stage_local.stage(checkout, [":screen-model"], output)
            self.assertEqual(third, manifest.read_text())

    def test_missing_publications_do_not_create_a_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "gradlew").touch()
            with patch.object(stage_local.subprocess, "run"):
                with self.assertRaisesRegex(ValueError, "without staging any"):
                    stage_local.stage(root, [":screen-model"], root / "staging")
            self.assertFalse((root / "staging/local-dependencies.properties").exists())


if __name__ == "__main__":
    unittest.main()
