import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('workspace', Path(__file__).resolve().parents[1] / 'workspace.py')
workspace = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(workspace)


class WorkspaceTest(unittest.TestCase):
    def validate(self, root, path='libs/example'):
        (root / 'sources.lock.json').write_text(json.dumps({
            'schemaVersion': 2, 'layout': 'monorepo', 'sources': [
                {'name': 'example', 'path': path, 'commit': 'a' * 40}]}))
        return workspace.validate_sources(root)

    def test_accepts_integrated_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'libs/example').mkdir(parents=True)
            self.assertEqual(len(self.validate(root)), 1)

    def test_rejects_missing_source(self):
        with tempfile.TemporaryDirectory() as directory, self.assertRaisesRegex(ValueError, 'Missing'):
            self.validate(Path(directory))

    def test_rejects_path_escape(self):
        with tempfile.TemporaryDirectory() as directory, self.assertRaisesRegex(ValueError, 'escapes'):
            self.validate(Path(directory), '../outside')

    def test_rejects_nested_git_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'libs/example/.git').mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, 'Nested'):
                self.validate(root)

    def test_actual_workspace_is_complete(self):
        self.assertEqual(len(workspace.validate_sources()), 22)
