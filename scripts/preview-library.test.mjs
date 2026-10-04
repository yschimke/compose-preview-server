// Run with NODE_PATH pointing at a Playwright installation: node --test scripts/preview-library.test.mjs
// The bridge is a test host. Image bytes come from the real ComposeStarter MCP render in evidence.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');
const html = fs.readFileSync(new URL('../mcp-app/preview-library.html', import.meta.url), 'utf8');
const png = fs.readFileSync(new URL('../docs/evidence/preview-library-recovery/compose-starter.png', import.meta.url)).toString('base64');

test('empty sidebar discovers registration, auto-selects, and keeps real cached pixels on failure', async () => {
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 1000, height: 640 } });
    await page.setContent('<iframe id="app" style="width:100%;height:620px;border:0"></iframe>');
    await page.evaluate(({ html, png }) => {
      const frame = document.getElementById('app');
      const uri = 'compose-preview://starter/_app/ListScreenPreview';
      const preview = { uri, name: 'ListScreenPreview' };
      window.calls = [];
      window.registered = false;
      window.discovered = false;
      window.failRender = false;
      const structured = () => ({ schema: 'compose-preview-library/v1', mode: 'library', host: 'local',
        tools: { refresh: 'previews_library', render: 'render_preview' }, renderArgs: { inline: true, observe: 'png' },
        projects: window.registered ? [{ id: 'starter', name: 'ComposeStarter', warming: !window.discovered,
          modules: window.discovered ? [{ path: ':app', previews: [preview] }] : [] }] : [] });
      const send = m => frame.contentWindow.postMessage({ jsonrpc: '2.0', ...m }, '*');
      window.addEventListener('message', e => {
        if (e.source !== frame.contentWindow) return;
        const { id, method, params } = e.data;
        if (!method) return;
        const reply = result => send({ id, result });
        if (method === 'ui/initialize') {
          reply({ hostContext: {}, hostCapabilities: { serverTools: {} } });
          send({ method: 'ui/notifications/tool-result', params: { structuredContent: structured() } });
        } else if (method === 'tools/call') {
          window.calls.push(params);
          if (params.name === 'previews_library') {
            if (params.arguments.projectId) window.discovered = true;
            reply({ structuredContent: structured() });
          } else if (params.name === 'render_preview') {
            if (window.failRender) setTimeout(() => reply({ isError: true, content: [{ type: 'text', text: 'daemon unavailable' }] }), 500);
            else reply({ content: [{ type: 'image', mimeType: 'image/png', data: png }] });
          }
        }
      });
      frame.srcdoc = html;
    }, { html, png });
    const frame = page.frameLocator('#app');
    await frame.locator('#tree').getByText('No projects are registered yet.', { exact: false }).waitFor();
    const evidence = process.env.PREVIEW_LIBRARY_EVIDENCE;
    if (evidence) await page.screenshot({ path: `${evidence}/before.png` });
    await page.evaluate(() => { window.registered = true; });
    await frame.locator('#pane img').waitFor({ timeout: 15000 });
    assert.equal(await frame.locator('#pane img').getAttribute('src'), `data:image/png;base64,${png}`);
    assert.equal(await page.evaluate(() => window.calls.filter(c => c.name === 'render_preview').length), 1);
    if (evidence) await page.screenshot({ path: `${evidence}/after.png` });
    await page.evaluate(() => { window.failRender = true; });
    await frame.locator('.preview.row').click();
    await frame.locator('.status').getByText('Showing last successful render', { exact: false }).waitFor();
    assert.equal(await frame.locator('#pane img').count(), 1);
    await frame.locator('.error').getByText('Refresh failed; showing last successful render. daemon unavailable').waitFor();
    assert.equal(await frame.locator('#pane img').getAttribute('src'), `data:image/png;base64,${png}`);
    assert.equal(await page.evaluate(() => window.calls.filter(c => c.arguments.projectId).length), 1);
  } finally { await browser.close(); }
});

for (const hostFiles of [true, false]) test(`local design tab opens the original .uid file (host file capability: ${hostFiles})`, async () => {
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 1100, height: 640 } });
    await page.setContent('<iframe id="app" style="width:100%;height:620px;border:0"></iframe>');
    const path = '/work/active chat/designs/Watch #1.uid';
    await page.evaluate(({ html, hostFiles, path }) => {
      const frame = document.getElementById('app');
      window.calls = [];
      const send = m => frame.contentWindow.postMessage({ jsonrpc: '2.0', ...m }, '*');
      window.addEventListener('message', e => {
        if (e.source !== frame.contentWindow) return;
        const { id, method, params } = e.data;
        if (!method) return;
        window.calls.push({ method, params });
        if (method === 'ui/initialize') {
          send({ id, result: { hostCapabilities: { experimental: hostFiles ? { 'openai/files': {} } : {} }, hostContext: {} } });
          send({ method: 'ui/notifications/tool-result', params: { structuredContent: {
            schema: 'compose-preview-library/v1', mode: 'library', host: 'local', projects: [],
            tools: { refresh: 'previews_library' }, designs: [{ id: path, name: 'Watch #1', path }],
          } } });
        } else if (method === 'openai/files/open' || method === 'ui/open-link') send({ id, result: {} });
      });
      frame.srcdoc = html;
    }, { html, hostFiles, path });
    const frame = page.frameLocator('#app');
    await frame.locator('#designs-tab').click();
    await frame.locator('#search').fill('active chat');
    await frame.locator('#tree [role="button"]').click();
    assert.equal(await frame.locator('#pane .uri').textContent(), path);
    if (process.env.PREVIEW_LIBRARY_DESIGN_EVIDENCE && hostFiles)
      await page.screenshot({ path: `${process.env.PREVIEW_LIBRARY_DESIGN_EVIDENCE}/local-designs.png` });
    await frame.getByRole('button', { name: 'Open in UI Builder' }).click();
    await frame.locator('#pane .status').getByText('Asked the host', { exact: false }).waitFor();
    const call = await page.evaluate(() => window.calls.find(c => c.method === 'openai/files/open' || c.method === 'ui/open-link'));
    assert.deepEqual(call, hostFiles ? { method: 'openai/files/open', params: { path } }
      : { method: 'ui/open-link', params: { url: 'file:///work/active%20chat/designs/Watch%20%231.uid' } });
    assert.equal(await page.evaluate(() => window.calls.filter(c => c.method === 'tools/call').length), 0);
  } finally { await browser.close(); }
});

test('hosted library lists designs through its existing authorized tools', async () => {
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage();
    await page.setContent('<iframe id="app" style="width:100%;height:620px"></iframe>');
    await page.evaluate(({ html }) => {
      const frame = document.getElementById('app');
      window.calls = [];
      const send = m => frame.contentWindow.postMessage({ jsonrpc: '2.0', ...m }, '*');
      window.addEventListener('message', e => {
        if (e.source !== frame.contentWindow) return;
        const { id, method, params } = e.data;
        if (method === 'ui/initialize') {
          send({ id, result: { hostContext: {} } });
          send({ method: 'ui/notifications/tool-result', params: { structuredContent: {
            schema: 'compose-preview-library/v1', mode: 'library', host: 'hosted', projects: [],
            tools: { listDesigns: 'ui_builder_list_designs', view: 'ui_builder_view' },
          } } });
        } else if (method === 'tools/call') {
          window.calls.push(params);
          send({ id, result: params.name === 'ui_builder_list_designs'
            ? { structuredContent: { designs: [{ designId: 'owned-design', title: 'Owned design' }] } }
            : { isError: true, content: [{ type: 'text', text: 'Export capability required' }] } });
        }
      });
      frame.srcdoc = html;
    }, { html });
    const frame = page.frameLocator('#app');
    await frame.locator('#designs-tab').click();
    await frame.locator('#tree [role="button"]').click();
    await frame.locator('#pane .error').getByText('Export capability required').waitFor();
    assert.deepEqual(await page.evaluate(() => window.calls), [
      { name: 'ui_builder_list_designs', arguments: {} },
      { name: 'ui_builder_view', arguments: { designId: 'owned-design', inline: true } },
    ]);
  } finally { await browser.close(); }
});
