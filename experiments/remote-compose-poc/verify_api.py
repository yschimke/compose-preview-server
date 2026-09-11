"""Run against serve.py: verify the browser and MCP paths return identical artifacts."""
import json
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from generate import ROOT

def post(route, data):
    request = Request("http://127.0.0.1:8765" + route, data=json.dumps(data).encode(),
                      headers={"Content-Type": "application/json", "Accept": "application/json"})
    with urlopen(request, timeout=20) as response:
        return json.load(response)

def rpc(method, params, request_id):
    return post("/mcp", {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})["result"]

model = json.loads((ROOT / "model.json").read_text())
initial = rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
                            "clientInfo": {"name": "proof-smoke", "version": "1"}}, 1)
assert initial["serverInfo"]["name"] == "interface-feasibility-proof"
assert rpc("tools/list", {}, 2)["tools"][0]["name"] == "compile_interface"
http = post("/compile-model", model)
mcp = rpc("tools/call", {"name": "compile_interface", "arguments": {"model": model}}, 3)
assert not mcp["isError"]
assert http == json.loads(mcp["content"][0]["text"])
model["repeat"]["count"] = 0
failure = rpc("tools/call", {"name": "compile_interface", "arguments": {"model": model}}, 4)
assert failure["isError"] and "repeat.count" in failure["content"][0]["text"]
try:
    post("/compile-model", model)
    raise AssertionError("Invalid model was accepted")
except HTTPError as error:
    assert error.code == 422
    assert "repeat.count" in json.load(error)["error"]
print("PASS: MCP handshake, discovery, identical HTTP/MCP artifacts, and located errors")
