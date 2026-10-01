// Evidence for compose-ui-builder#364 (server side): `design_open` and the UI Builder MCP App,
// driven through THIS repository's MCP server, in a fake MCP App host.
//
// Adapted from compose-ui-builder's `scripts/ui-builder-web-smoke/mcp-app-host.mjs` (#366). That
// harness serves the editor from its own Node asset server and fills in the shell itself; this one
// asks the real `compose-preview-mcp` process for everything the server owns:
//   1. `tools/list` must carry `design_open` with a `.uid` file entrypoint;
//   2. `resources/read ui://compose-ui-builder/editor` gives the shell, with the asset base filled
//      in, and `_meta.ui.csp` — the sandbox frame's CSP is derived from that, as a host derives it;
//   3. every editor file (Wasm, scripts, fonts, catalogs) loads from the server's loopback origin;
//   4. `tools/call design_open` with the FileInput the host would send.
// The host side (FileInput, `resources/read` / `openai/resources/write` on `host-resource://`,
// model context) is the same fake as #366's: a real ChatGPT/Codex desktop check is #1236's.
//
// Usage:
//   COMPOSE_PREVIEW_UI_BUILDER_WEB=<editor archive .zip> \
//     node docs/evidence/ui-builder-design-open/design-open-host.mjs \
//       mcp/build/install/compose-preview-mcp/bin/compose-preview-mcp <design.uid> [screenshot dir]

import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdir, readFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { chromium } from '/opt/node22/lib/node_modules/playwright/index.mjs';

const [binary, designArg, shotsArg] = process.argv.slice(2);
const designPath = resolve(designArg);
const shots = shotsArg ? resolve(shotsArg) : null;
const design = await readFile(designPath, 'utf8');
const fileName = designPath.split('/').pop();
const failures = [];
const expect = (condition, message) => {
  if (!condition) failures.push(message);
};

// ---- The MCP server, over stdio ------------------------------------------------------------
const mcp = spawn(binary, [], { stdio: ['pipe', 'pipe', 'pipe'] });
const stderr = [];
mcp.stderr.on('data', (chunk) => stderr.push(String(chunk)));
const pending = new Map();
let buffered = '';
mcp.stdout.on('data', (chunk) => {
  buffered += chunk;
  let newline;
  while ((newline = buffered.indexOf('\n')) >= 0) {
    const line = buffered.slice(0, newline).trim();
    buffered = buffered.slice(newline + 1);
    if (!line) continue;
    const message = JSON.parse(line);
    if (message.id != null && pending.has(message.id)) {
      pending.get(message.id)(message);
      pending.delete(message.id);
    }
  }
});
let nextId = 1;
function rpc(method, params) {
  const id = nextId++;
  mcp.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
  return new Promise((done, fail) => {
    const timer = setTimeout(() => fail(new Error(`${method} timed out`)), 60_000);
    pending.set(id, (message) => {
      clearTimeout(timer);
      if (message.error) fail(new Error(`${method}: ${JSON.stringify(message.error)}`));
      else done(message.result);
    });
  });
}

await rpc('initialize', {
  protocolVersion: '2025-06-18',
  capabilities: {},
  clientInfo: { name: 'fake-mcp-app-host', version: '1' },
});
mcp.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');

const tools = (await rpc('tools/list', {})).tools;
const tool = tools.find((it) => it.name === 'design_open');
expect(tool, 'design_open is not listed');
expect(
  JSON.stringify(tool?._meta?.['openai/ui']?.entrypoints) ===
    JSON.stringify([{ type: 'file', extensions: ['.uid'] }]),
  `design_open entrypoints: ${JSON.stringify(tool?._meta?.['openai/ui'])}`,
);
const resourceUri = tool?._meta?.ui?.resourceUri;
expect(resourceUri === 'ui://compose-ui-builder/editor', `resourceUri ${resourceUri}`);

const read = await rpc('resources/read', { uri: resourceUri });
const content = read.contents[0];
expect(content.mimeType === 'text/html;profile=mcp-app', `mimeType ${content.mimeType}`);
const shell = content.text;
expect(!shell.includes('__COMPOSE_UI_BUILDER_ASSET_BASE__'), 'the asset base was not filled in');
const assetBase = /<base href="([^"]+)"/.exec(shell)?.[1];
const csp = content._meta?.ui?.csp ?? {};
const resourceDomains = csp.resourceDomains ?? [];
const connectDomains = csp.connectDomains ?? [];
expect(resourceDomains.length === 1 && assetBase?.startsWith(resourceDomains[0] + '/'),
  `resourceDomains ${JSON.stringify(resourceDomains)} does not cover ${assetBase}`);
console.log(`asset base ${assetBase}; csp ${JSON.stringify(csp)}`);
console.log(`display ${JSON.stringify(content._meta?.['openai/ui'])}`);

const opened = await rpc('tools/call', {
  name: 'design_open',
  arguments: { file: { name: fileName, resourceUri: 'host-resource://design' } },
});
expect(!opened.isError, `design_open failed: ${JSON.stringify(opened)}`);
console.log(`design_open -> ${opened.content?.[0]?.text}`);

// ---- The host ------------------------------------------------------------------------------
function listen(handler) {
  const server = createServer(handler);
  return new Promise((ready) =>
    server.listen(0, '127.0.0.1', () => ready({ server, port: server.address().port })),
  );
}

// The sandbox frame, with a CSP derived from `_meta.ui.csp` as MCP Apps hosts derive it. Only the
// server's domains are allowed: anything the editor tried to load from elsewhere would fail.
const frameCsp = [
  "default-src 'none'",
  `script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval' ${resourceDomains.join(' ')}`,
  `style-src 'self' 'unsafe-inline' ${resourceDomains.join(' ')}`,
  `img-src 'self' data: blob: ${resourceDomains.join(' ')}`,
  `font-src 'self' data: ${resourceDomains.join(' ')}`,
  `connect-src 'self' ${connectDomains.join(' ')}`,
].join('; ');
const sandbox = await listen((request, response) => {
  response
    .writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'content-security-policy': frameCsp })
    .end(shell);
});

const hostPage = `<!doctype html><html><head><meta charset="utf-8"><title>Fake MCP App host</title>
<style>
  body { margin: 0; display: grid; grid-template-columns: 420px 1fr; height: 100vh;
         font: 12px/1.4 system-ui, sans-serif; background: #f3f0f7; }
  aside { overflow: auto; padding: 10px; border-right: 1px solid #ccc; }
  h2 { font-size: 13px; margin: 10px 0 4px; }
  pre { white-space: pre-wrap; background: #fff; padding: 6px; border-radius: 4px; margin: 0;
        font: 11px/1.35 ui-monospace, monospace; max-height: 30vh; overflow: auto; }
  iframe { border: 0; width: 100%; height: 100%; background: #fff; }
</style></head><body>
<aside>
  <div><b>Fake MCP App host</b> &middot; compose-preview-mcp &middot; <code>design_open</code></div>
  <h2>From the MCP server</h2><pre>${[
    `tools/call design_open -> ${opened.content?.[0]?.text}`,
    `resources/read ${resourceUri}`,
    `  <base href> ${assetBase}`,
    `  csp.resourceDomains ${JSON.stringify(resourceDomains)}`,
    `  csp.connectDomains ${JSON.stringify(connectDomains)}`,
    `  openai/ui ${JSON.stringify(content._meta?.['openai/ui'])}`,
  ].join('\n').replace(/</g, '&lt;')}</pre>
  <h2>Host log</h2><pre id="log"></pre>
  <h2>Editor files loaded from the asset origin</h2><pre id="assets"></pre>
  <h2>Model context (ui/update-model-context)</h2><pre id="context">(none)</pre>
</aside>
<iframe id="app" sandbox="allow-scripts allow-same-origin" src="http://127.0.0.1:${sandbox.port}/"></iframe>
<script>
  const uri = 'host-resource://design';
  const fileName = ${JSON.stringify(fileName)};
  const host = globalThis.fakeHost = {
    text: ${JSON.stringify(design)}, version: 1, calls: [], writes: [], contexts: [], errors: [],
  };
  const frame = document.getElementById('app');
  const log = (line) => {
    host.calls.push(line);
    document.getElementById('log').textContent = host.calls.slice(-14).join('\\n');
  };
  const send = (message) => frame.contentWindow.postMessage({ jsonrpc: '2.0', ...message }, '*');
  const etag = () => 'v' + host.version;
  const handlers = {
    'ui/initialize': () => ({
      protocolVersion: '2026-01-26',
      hostInfo: { name: 'fake-mcp-app-host', version: '1' },
      hostCapabilities: {
        experimental: { 'openai/resource': {}, 'openai/modelContext': {} },
        updateModelContext: { text: {}, structuredContent: {} },
        openLinks: {},
      },
      hostContext: { theme: 'light', displayMode: 'fullscreen' },
    }),
    'resources/read': (params) => {
      if (params.uri !== uri) throw new Error('unknown resource ' + params.uri);
      const representation = params._meta?.['openai/resource']?.representation;
      log('resources/read representation=' + representation + ' -> ' + etag());
      return { contents: [{ uri, mimeType: 'application/json', text: host.text,
        _meta: { 'openai/resource': { etag: etag(), writable: true } } }] };
    },
    'resources/subscribe': () => { log('resources/subscribe'); return {}; },
    'resources/unsubscribe': () => ({}),
    'openai/resources/write': (params) => {
      host.writes.push(params);
      if (params.ifMatch && params.ifMatch !== etag()) return { outcome: 'conflict', etag: etag() };
      host.text = params.text; host.version++;
      log('write ifMatch=' + params.ifMatch + ' -> saved ' + etag());
      return { outcome: 'saved', etag: etag() };
    },
    'ui/update-model-context': (params) => {
      host.contexts.push(params);
      const blocks = params.content ?? [];
      document.getElementById('context').textContent = blocks.length === 0 ? '(cleared)' :
        blocks.map((b) => (b.annotations?.audience ? '[assistant only] ' : '') +
          (b._meta?.['openai/title'] ? '[' + b._meta['openai/title'] + '] ' : '') + b.text)
          .join('\\n\\n');
      log('ui/update-model-context (' + blocks.length + ' blocks)');
      return {};
    },
    'ui/open-link': () => ({}),
  };
  addEventListener('message', async (event) => {
    if (event.source !== frame.contentWindow) return;
    const message = event.data;
    if (!message || message.jsonrpc !== '2.0') return;
    if (message.method === 'ui/notifications/initialized') {
      log('initialized; sending tool-input FileInput');
      send({ method: 'ui/notifications/tool-input',
        params: { arguments: { file: { name: fileName, resourceUri: uri } } } });
      return;
    }
    if (!message.method || message.id == null) return;
    const handler = handlers[message.method];
    try {
      if (!handler) throw new Error('unsupported ' + message.method);
      send({ id: message.id, result: await handler(message.params ?? {}) });
    } catch (error) {
      host.errors.push(String(error));
      send({ id: message.id, error: { code: -32603, message: String(error.message ?? error) } });
    }
  });
</script></body></html>`;
const hostServer = await listen((request, response) => {
  response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }).end(hostPage);
});

const browser = await chromium.launch({
  executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
});
if (shots) await mkdir(shots, { recursive: true });
const page = await browser.newPage({ viewport: { width: 1600, height: 960 }, locale: 'en-US' });
const pageErrors = [];
const assetResponses = [];
page.on('pageerror', (error) => pageErrors.push(String(error)));
page.on('console', (message) => {
  if (message.type() === 'error' && !/wasmExports` is deprecated/.test(message.text())) {
    pageErrors.push(message.text());
  }
});
page.on('response', (response) => {
  if (assetBase && response.url().startsWith(assetBase)) {
    assetResponses.push({
      path: response.url().slice(assetBase.length),
      status: response.status(),
      type: response.headers()['content-type'],
      acao: response.headers()['access-control-allow-origin'],
    });
  }
});
const host = () => page.evaluate(() => JSON.parse(JSON.stringify(globalThis.fakeHost)));

try {
  await page.goto(`http://localhost:${hostServer.port}/`);
  const frame = await (await page.waitForSelector('#app')).contentFrame();
  await frame.waitForFunction(
    () => document.documentElement.getAttribute('data-ui-builder-ready') === 'true',
    null,
    { timeout: Number(process.env.SMOKE_READY_TIMEOUT_MS ?? 240_000) },
  );
  await page.waitForFunction(
    () => globalThis.fakeHost.calls.some((it) => it.startsWith('resources/read')),
    null,
    { timeout: 30_000 },
  );
  await page.waitForTimeout(3_000);
  let state = await host();
  expect(state.calls.includes('resources/read representation=text -> v1'), 'no text read');
  expect(state.writes.length === 0, `opening the design wrote it (${state.writes.length})`);

  // Select a layer on the canvas (the fixture's button, at this viewport, as #366's harness does).
  const box = await (await page.$('#app')).boundingBox();
  const before = state.contexts.length;
  await page.mouse.click(box.x + 180, box.y + 209);
  await page
    .waitForFunction((n) => globalThis.fakeHost.contexts.length > n, before, { timeout: 20_000 })
    .catch(() => {});
  state = await host();
  expect(state.contexts.at(-1)?.content?.[0]?._meta?.['openai/title'], 'no titled model context');

  const wasm = assetResponses.find((it) => it.path === 'uiBuilder.wasm');
  expect(wasm?.status === 200 && wasm?.type === 'application/wasm' && wasm?.acao === '*',
    `uiBuilder.wasm from the asset origin: ${JSON.stringify(wasm)}`);
  expect(assetResponses.every((it) => it.status === 200), `asset failures: ${JSON.stringify(
    assetResponses.filter((it) => it.status !== 200))}`);
  await page.evaluate((lines) => {
    document.getElementById('assets').textContent = lines.join('\n');
  }, assetResponses.slice(0, 16).map((it) => `${it.status} ${it.type} ACAO=${it.acao}  ${it.path}`));
  if (shots) await page.screenshot({ path: join(shots, 'design-open-editor.png') });
  console.log(`assets loaded from the origin: ${assetResponses.length}`);
  for (const it of assetResponses) console.log(`  ${it.status} ${it.type} ${it.path}`);
} catch (error) {
  failures.push(`not ready: ${error.message.split('\n')[0]}`);
}
failures.push(...pageErrors.map((it) => `page error: ${it}`));
console.log(failures.length === 0 ? 'ok   design_open through compose-preview-mcp' :
  `FAIL design_open\n  ${failures.join('\n  ')}`);
console.log(stderr.join('').split('\n').filter((it) => it.includes('UI Builder')).join('\n'));
await browser.close();
for (const it of [sandbox, hostServer]) it.server.close();
mcp.stdin.end();
mcp.kill();
process.exit(failures.length === 0 ? 0 : 1);
