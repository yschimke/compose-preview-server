"""Local proof host: structured model -> JSON -> AndroidX compiler -> real .rc bytes."""
import base64
import json
import mimetypes
import os
import subprocess
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from generate import ROOT, lower, compose_source

def compile_model(model, density=1):
    authoring = json.dumps(lower(model, density), indent=2)
    with tempfile.TemporaryDirectory() as directory:
        source, output = Path(directory)/"source.json", Path(directory)/"output.rc"
        source.write_text(authoring)
        java_home = os.environ.get("POC_JAVA_HOME") or subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
        result = subprocess.run([str(Path(java_home)/"bin/java"), "-cp", (ROOT/"build/runtime-classpath.txt").read_text(), "poc.CompilerKt", str(source), str(output)], capture_output=True, text=True, timeout=15)
        if result.returncode: raise ValueError(result.stderr)
        reply = {"base64": base64.b64encode(output.read_bytes()).decode(), "authoringJson": authoring, "kotlin": compose_source(model)}
    return reply

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        route = self.path.split("?", 1)[0]
        if route == "/": path = ROOT / "index.html"
        elif route == "/model.json": path = ROOT / "model.json"
        elif route.startswith("/app/"):
            base = ROOT / "build/dist/wasmJs/productionExecutable"
            path = (base / route.removeprefix("/app/")).resolve()
            if not path.is_relative_to(base.resolve()): return self.send_error(404)
        else: return self.send_error(404)
        if not path.is_file(): return self.send_error(404)
        data = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", mimetypes.guess_type(path)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers(); self.wfile.write(data)

    def do_POST(self):
        if self.path == "/mcp": return self.mcp_post()
        if self.path != "/compile-model": return self.send_error(404)
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size < 100_000: raise ValueError("document: request must be below 100 KB")
            model = json.loads(self.rfile.read(size))
            density = float(self.headers.get("X-Preview-Density", "1"))
            reply = compile_model(model, density)
            code = 200
        except (ValueError, KeyError, TypeError, subprocess.SubprocessError) as e:
            code, reply = 422, {"error": str(e)}
        data = json.dumps(reply).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)


    def mcp_post(self):
        size = int(self.headers.get("Content-Length", "0"))
        if not 0 < size < 100_000: return self.send_error(413)
        request = json.loads(self.rfile.read(size))
        method = request.get("method")
        if "id" not in request:
            self.send_response(202); self.end_headers(); return
        if method == "initialize":
            result = {"protocolVersion": "2025-03-26", "capabilities": {"tools": {}},
                      "serverInfo": {"name": "interface-feasibility-proof", "version": "0.0.1"}}
        elif method == "tools/list":
            result = {"tools": [{"name": "compile_interface", "description": "Validate the bounded interface model and return real Remote Compose bytes, authoring JSON, and Compose Kotlin. Does not save or deploy anything.",
                "inputSchema": {"type": "object", "required": ["model"], "additionalProperties": False,
                                "properties": {"model": json.loads((ROOT / "model.schema.json").read_text()),
                                               "density": {"type": "number", "minimum": 0.5, "maximum": 4, "default": 1}}}}]}
        elif method == "tools/call":
            try:
                params = request["params"]
                if params["name"] != "compile_interface": raise ValueError("Unknown tool")
                args = params["arguments"]
                compiled = compile_model(args["model"], args.get("density", 1))
                result = {"content": [{"type": "text", "text": json.dumps(compiled)}], "isError": False}
            except (ValueError, KeyError, TypeError, subprocess.SubprocessError) as e:
                result = {"content": [{"type": "text", "text": str(e)}], "isError": True}
        elif method == "ping": result = {}
        else:
            result = None
        response = {"jsonrpc": "2.0", "id": request["id"]}
        if result is None: response["error"] = {"code": -32601, "message": "Method not found"}
        else: response["result"] = result
        data = json.dumps(response).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)

if __name__ == "__main__":
    print("Proof of concept: http://127.0.0.1:8765", flush=True)
    ThreadingHTTPServer(("127.0.0.1", 8765), Handler).serve_forever()
