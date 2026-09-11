#!/usr/bin/env python3
"""Exercise the installed standalone MCP process against a real local UI-builder server."""
import base64
import hashlib
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading

root = Path(__file__).resolve().parent.parent
evidence = root / 'docs/design/evidence/ui-builder-unsaved-remote-preview'
url = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:5626'
token = os.environ['UI_BUILDER_TEST_TOKEN']
env = dict(os.environ, COMPOSE_PREVIEW_UI_BUILDER_TOKEN=token)
log = open('/tmp/ui-builder-standalone-document-stderr.log', 'w')
process = subprocess.Popen(
    [str(root / 'mcp/build/install/compose-preview-mcp/bin/compose-preview-mcp'),
     '--ui-builder-url', url],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, text=True, env=env,
)
messages = queue.Queue()
def read_messages():
    for line in process.stdout:
        try:
            messages.put(json.loads(line))
        except ValueError:
            messages.put({'invalid_stdout': line})
    messages.put({'eof': True})
threading.Thread(target=read_messages, daemon=True).start()
sequence = 0

def rpc(method, params):
    global sequence
    sequence += 1
    process.stdin.write(json.dumps({'jsonrpc': '2.0', 'id': sequence, 'method': method, 'params': params}) + '\n')
    process.stdin.flush()
    while True:
        response = messages.get(timeout=60)
        assert 'eof' not in response and 'invalid_stdout' not in response, response
        if response.get('id') == sequence:
            assert 'error' not in response, response
            return response['result']

def call(name, arguments):
    return rpc('tools/call', {'name': name, 'arguments': arguments})

try:
    rpc('initialize', {'protocolVersion': '2024-11-05', 'capabilities': {}, 'clientInfo': {'name': 'supplied-document-proof', 'version': '1'}})
    process.stdin.write(json.dumps({'jsonrpc': '2.0', 'method': 'notifications/initialized'}) + '\n')
    process.stdin.flush()
    tool_names = [tool['name'] for tool in rpc('tools/list', {})['tools']]
    assert 'export_document' in tool_names, tool_names
    document = json.loads((evidence / 'document.json').read_text())
    artifacts = {}
    for format in ['json', 'rc']:
        result = call('export_document', {'document': document, 'format': format})
        assert not result.get('isError'), result
        artifact = json.loads(result['content'][0]['text'])
        content = base64.b64decode(artifact['content']) if artifact['encoding'] == 'base64' else artifact['content'].encode()
        expected = (evidence / f'local.{format}').read_bytes()
        assert content == expected, format
        digest = hashlib.sha256(content).hexdigest()
        assert digest == artifact['contentDigest'], artifact
        artifacts[format] = {'bytes': len(content), 'sha256': digest, 'matchesBrowserDownload': True, 'diagnostics': artifact['diagnostics']}
    refused_document = json.loads(json.dumps(document))
    refused_document['environment']['layoutDirection'] = 'rtl'
    result = call('export_document', {'document': refused_document, 'format': 'rc'})
    assert result.get('isError'), result
    refusal = json.loads(result['content'][0]['text'])
    assert any(d['severity'] == 'error' and 'environment.layoutDirection' in d['message'] for d in refusal['diagnostics']), refusal
    assert not refusal['content'], refusal
    opened = call('open_design', {'designId': document['id']})
    assert opened.get('isError'), opened
    assert 'Design API returned HTTP 404' in opened['content'][0]['text'], opened
    output = {'designId': document['id'], 'transport': 'standalone MCP stdio -> Design API HTTP', 'artifacts': artifacts, 'refusal': refusal['diagnostics'], 'savedOnServer': False}
    (evidence / 'standalone-verification.json').write_text(json.dumps(output, indent=2) + '\n')
    print(json.dumps(output))
finally:
    process.stdin.close()
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.terminate()
        process.wait(timeout=10)
    log.close()
