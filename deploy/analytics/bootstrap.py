#!/usr/bin/env python3
"""Initialize a dedicated Umami instance; preserve credentials and website IDs on reruns."""
import json
import os
from pathlib import Path
import secrets
from urllib.error import HTTPError
from urllib.request import Request, urlopen

os.chdir(Path(__file__).parent)
os.umask(0o077)
password_file = Path("admin-password")
if not password_file.exists():
    password_file.write_text(secrets.token_urlsafe(32) + "\n")
password = password_file.read_text().strip()

def api(path, data=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request("http://127.0.0.1:3001/api/" + path,
                      data=json.dumps(data).encode() if data is not None else None,
                      headers=headers)
    with urlopen(request, timeout=30) as response:
        return json.load(response)

try:
    login = api("auth/login", {"username": "admin", "password": password})
except HTTPError as error:
    if error.code != 401:
        raise
    error.close()
    login = api("auth/login", {"username": "admin", "password": "umami"})
    api("users/" + login["user"]["id"], {"password": password}, login["token"])
    # Changing the password invalidates the login token.
    login = api("auth/login", {"username": "admin", "password": password})
token = login["token"]
existing = api("websites?pageSize=100", token=token)["data"]
websites = {}
for name, domain in [("Compose Preview", "preview.coo.ee"),
                     ("Compose AI Tools docs", "yschimke.github.io")]:
    site = next((site for site in existing if site["name"] == name and site["domain"] == domain), None)
    if site is None:
        site = api("websites", {"name": name, "domain": domain}, token)
    websites[domain] = site["id"]
Path("websites.json").write_text(json.dumps(websites, indent=2) + "\n")
print("Umami initialized. Credentials: admin-password (mode 600). Website IDs: websites.json.")
