"""Bootstrap contract checks without Docker or a network connection."""
import io
import json
import os
from pathlib import Path
import runpy
import shutil
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError


class BootstrapTest(unittest.TestCase):
    def test_first_install_and_rerun_preserve_password_and_sites(self):
        password = "umami"
        websites = []
        updates = []

        def request(req, timeout):
            nonlocal password
            path = req.full_url.removeprefix("http://127.0.0.1:3001/api/")
            data = json.loads(req.data) if req.data else None
            if path == "auth/login":
                if data["password"] != password:
                    raise HTTPError(req.full_url, 401, "Unauthorized", {}, None)
                response = {"token": password, "user": {"id": "admin-id"}}
            else:
                self.assertEqual(req.headers["Authorization"], "Bearer " + password)
                if path == "users/admin-id":
                    password = data["password"]
                    updates.append(password)
                    response = {}
                elif path.startswith("websites?"):
                    response = {"data": websites}
                else:
                    self.assertEqual(path, "websites")
                    response = {**data, "id": str(len(websites) + 1)}
                    websites.append(response)
            return io.BytesIO(json.dumps(response).encode())

        original_cwd = Path.cwd()
        original_umask = os.umask(0o077)
        try:
            with tempfile.TemporaryDirectory() as directory:
                script = Path(directory) / "bootstrap.py"
                shutil.copy(Path(__file__).with_name("bootstrap.py"), script)
                with patch("urllib.request.urlopen", request):
                    runpy.run_path(str(script), run_name="__main__")
                    ids = (Path(directory) / "websites.json").read_text()
                    runpy.run_path(str(script), run_name="__main__")
                    self.assertEqual((Path(directory) / "websites.json").read_text(), ids)
                self.assertEqual(len(websites), 2)
                self.assertEqual(len(updates), 1)
                secret = Path(directory) / "admin-password"
                self.assertEqual(secret.stat().st_mode & 0o777, 0o600)
                self.assertEqual(secret.read_text().strip(), password)
        finally:
            os.chdir(original_cwd)
            os.umask(original_umask)


if __name__ == "__main__":
    unittest.main()
