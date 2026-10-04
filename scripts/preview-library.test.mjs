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
