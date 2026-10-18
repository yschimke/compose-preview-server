#!/usr/bin/env python3
"""Bounded, loopback-only export probe. Never follows redirects or targets production."""
import argparse
import concurrent.futures
import collections
import ipaddress
import json
import math
import os
import time
import threading
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def local_origin(value):
    parsed = urllib.parse.urlsplit(value)
    try:
        loopback = ipaddress.ip_address(parsed.hostname or "").is_loopback
    except ValueError:
        loopback = False
    if (not loopback or parsed.scheme != "http" or parsed.username or parsed.password
            or parsed.path not in ("", "/") or parsed.query or parsed.fragment):
        raise argparse.ArgumentTypeError("use an explicit numeric loopback HTTP origin, e.g. http://127.0.0.1:18081")
    return value.rstrip("/")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True, type=local_origin)
    parser.add_argument("--design", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--modes", default="compose,png,svg,thumbnail,mcp-compose,mcp-png,tools,get,mixed")
    parser.add_argument("--levels", default="1,4,16,64,128")
    args = parser.parse_args()
    levels = [int(n) for n in args.levels.split(",")]
    if not levels or any(n < 1 or n > 128 for n in levels):
        parser.error("concurrency must be between 1 and 128")
    modes = args.modes.split(",")
    allowed = {"compose", "png", "svg", "thumbnail", "mcp-compose", "mcp-png", "tools", "get", "mixed", "native", "check", "catalog"}
    if set(modes) - allowed:
        parser.error("unknown mode")
    # The token is never a URL, output field or exception message.
    headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
    if os.environ.get("COMPOSE_PREVIEW_TOKEN"):
        headers["X-Compose-Preview-Token"] = os.environ["COMPOSE_PREVIEW_TOKEN"]
    design = urllib.parse.quote(args.design, safe="")

    def request(mode, index):
        if mode == "mixed":
            return [row for m in ("get", "mcp-compose", "mcp-png") for row in request(m, index)]
        payload = None
        path = f"/api/ui-builder/v1/designs/{design}/export.{mode}"
        if mode == "thumbnail":
            path = f"/api/ui-builder/v1/designs/{design}/thumbnail.png"
        tool = {"get": "ui_builder_get_design", "native": "ui_builder_render_native", "check": "ui_builder_check_design", "catalog": "catalog_render_preview"}.get(mode)
        if mode.startswith("mcp-") or tool or mode == "tools":
            path = "/mcp"
            arguments = {"designId": args.design}
            if mode.startswith("mcp-"):
                tool = "ui_builder_export"
                arguments["format"] = mode[4:]
            if mode == "check":
                arguments["rendered"] = True
            if mode == "catalog":
                # Optional fixture catalog preview; callers can omit this mode if none is served.
                arguments = {"catalog": "load-fixture", "previewId": "load-preview"}
            payload = json.dumps({"jsonrpc": "2.0", "id": index, "method": "tools/list" if mode == "tools" else "tools/call", "params": {"name": tool, "arguments": arguments}}).encode()
        start = time.monotonic()
        try:
            response = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect()).open(urllib.request.Request(args.server + path, data=payload, headers=headers), timeout=90)
        except urllib.error.HTTPError as error:
            response = error
        except (OSError, TimeoutError):
            return [{"mode": mode, "status": "transport_error", "ms": round((time.monotonic() - start) * 1000, 2)}]
        with response:
            body = response.read()
            result = {"mode": mode, "status": response.status, "ms": round((time.monotonic() - start) * 1000, 2), "retryAfter": response.headers.get("Retry-After")}
            error_body = response.status >= 400
            if payload:
                try:
                    envelope = json.loads(body)
                    tool_result = envelope.get("result", {})
                    structured = tool_result.get("structuredContent", {})
                    service_error = structured.get("response", {}).get("type") == "error"
                    result["toolError"] = bool(envelope.get("error") or tool_result.get("isError") or service_error)
                    error_body |= result["toolError"]
                except (ValueError, AttributeError):
                    result["toolError"] = True
                    error_body = True
            if error_body:
                # Preserve actual failure bodies; redact a credential if a broken server echoes it.
                text = body.decode(errors="replace")
                token = os.environ.get("COMPOSE_PREVIEW_TOKEN")
                result["body"] = text.replace(token, "[REDACTED]") if token else text
            return [result]

    report = []
    for mode in modes:
        for concurrency in ([50] if mode == "mixed" else levels) + [1]:
            started_at = int(time.time() * 1000)
            barrier = threading.Barrier(concurrency)
            def fire(index):
                barrier.wait(timeout=30)
                return request(mode, index)
            with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
                rows = [row for group in pool.map(fire, range(concurrency)) for row in group]
            latencies = sorted(row["ms"] for row in rows)
            summary = {"startedAtMillis": started_at, "mode": mode, "concurrency": concurrency, "recovery": concurrency == 1 and bool(report) and report[-1]["mode"] == mode, "statuses": dict(collections.Counter(str(row["status"]) for row in rows)), "toolErrors": sum(row.get("toolError", False) for row in rows), "p50_ms": latencies[math.ceil(len(rows)*.5)-1], "p99_ms": latencies[math.ceil(len(rows)*.99)-1]}
            print(json.dumps(summary), flush=True)
            report.append(dict(summary, responses=rows))
            with open(args.output, "w", encoding="utf-8") as output:
                json.dump(report, output, indent=2)


if __name__ == "__main__":
    main()
