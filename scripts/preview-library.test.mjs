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
      const preview = { uri, name: 'ListScreenPreview', sourceFile: 'ListScreen.kt', sourceLine: 42 };
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
        if (method === 'ui/update-model-context') {
          window.contexts ||= [];
          window.contexts.push(params);
          send({ id, result: {} });
        } else if (method === 'ui/initialize') {
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
    await frame.locator('.chat-context').getByText('Selection shared with chat', { exact: true }).waitFor();
    assert.deepEqual(await page.evaluate(() => JSON.parse(window.contexts.at(-1).content[0].text).selection),
      { kind: 'preview', host: 'local', uri: 'compose-preview://starter/_app/ListScreenPreview', name: 'ListScreenPreview', projectId: 'starter', projectName: 'ComposeStarter', module: ':app', sourceFile: 'ListScreen.kt', sourceLine: 42 });
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

for (const { hostFiles, path, expectedUrl } of [
  { hostFiles: true, path: '/work/active chat/designs/Watch #1.uid' },
  { hostFiles: false, path: '/work/active chat/designs/Watch #1.uid', expectedUrl: 'file:///work/active%20chat/designs/Watch%20%231.uid' },
  { hostFiles: false, path: String.raw`C:\work\active chat\Watch #1.uid`, expectedUrl: 'file:///C:/work/active%20chat/Watch%20%231.uid' },
  { hostFiles: false, path: String.raw`\\server\share\active chat\Watch #1.uid`, expectedUrl: 'file://server/share/active%20chat/Watch%20%231.uid' },
]) test(`local design tab opens the original .uid file (${path}, host file capability: ${hostFiles})`, async () => {
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 1100, height: 640 } });
    await page.setContent('<iframe id="app" style="width:100%;height:620px;border:0"></iframe>');
    await page.evaluate(({ html, hostFiles, path }) => {
      const frame = document.getElementById('app');
      window.calls = [];
      const send = m => frame.contentWindow.postMessage({ jsonrpc: '2.0', ...m }, '*');
      window.addEventListener('message', e => {
        if (e.source !== frame.contentWindow) return;
        const { id, method, params } = e.data;
        if (!method) return;
        window.calls.push({ method, params });
        if (method === 'ui/update-model-context') {
          window.contexts ||= [];
          window.contexts.push(params);
          send({ id, result: {} });
        } else if (method === 'ui/initialize') {
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
    await frame.locator('.chat-context').getByText('Selection shared with chat', { exact: true }).waitFor();
    assert.deepEqual(await page.evaluate(() => JSON.parse(window.contexts.at(-1).content[0].text).selection),
      { kind: 'local-design', host: 'local', id: path, name: 'Watch #1', path });
    if (process.env.PREVIEW_LIBRARY_DESIGN_EVIDENCE && hostFiles)
      await page.screenshot({ path: `${process.env.PREVIEW_LIBRARY_DESIGN_EVIDENCE}/local-designs.png` });
    await frame.getByRole('button', { name: 'Open in UI Builder' }).click();
    await frame.locator('#pane .status').getByText('Asked the host', { exact: false }).waitFor();
    const call = await page.evaluate(() => window.calls.find(c => c.method === 'openai/files/open' || c.method === 'ui/open-link'));
    assert.deepEqual(call, hostFiles ? { method: 'openai/files/open', params: { path } }
      : { method: 'ui/open-link', params: { url: expectedUrl } });
    assert.equal(await page.evaluate(() => window.calls.filter(c => c.method === 'tools/call').length), 0);
    await frame.locator('#previews-tab').click();
    await page.waitForFunction(() => window.contexts.at(-1).content.length === 0);
    assert.equal(await frame.locator('#pane .uri').count(), 0);
  } finally { await browser.close(); }
});

test('hosted library lists designs through its existing authorized tools', async () => {
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage();
    await page.setContent('<iframe id="app" style="width:100%;height:620px"></iframe>');
    await page.evaluate(({ html, png }) => {
      const frame = document.getElementById('app');
      window.calls = [];
      window.designSuccess = false;
      const send = m => frame.contentWindow.postMessage({ jsonrpc: '2.0', ...m }, '*');
      window.addEventListener('message', e => {
        if (e.source !== frame.contentWindow) return;
        const { id, method, params } = e.data;
        if (method === 'ui/update-model-context') {
          window.contexts ||= [];
          window.contexts.push(params);
          send({ id, result: {} });
        } else if (method === 'ui/initialize') {
          send({ id, result: { hostContext: {} } });
          send({ method: 'ui/notifications/tool-result', params: { structuredContent: {
            schema: 'compose-preview-library/v1', mode: 'library', host: 'hosted', projects: [],
            tools: { listDesigns: 'ui_builder_list_designs', view: 'ui_builder_view' },
          } } });
        } else if (method === 'tools/call') {
          window.calls.push(params);
          send({ id, result: params.name === 'ui_builder_list_designs'
            ? { structuredContent: { designs: [{ designId: 'owned-design', title: 'Owned design' }] } }
            : window.designSuccess ? { content: [{ type: 'image', mimeType: 'image/png', data: png }] }
            : { isError: true, content: [{ type: 'text', text: 'Export capability required' }] } });
        }
      });
      frame.srcdoc = html;
    }, { html, png });
    const frame = page.frameLocator('#app');
    await frame.locator('#designs-tab').click();
    await frame.locator('#tree [role="button"]').click();
    await frame.locator('#pane .error').getByText('Export capability required').waitFor();
    await frame.locator('.chat-context').getByText('Selection shared with chat', { exact: true }).waitFor();
    assert.deepEqual(await page.evaluate(() => JSON.parse(window.contexts.at(-1).content[0].text).selection),
      { kind: 'hosted-design', host: 'hosted', id: 'owned-design', name: 'Owned design' });
    assert.deepEqual(await page.evaluate(() => window.calls), [
      { name: 'ui_builder_list_designs', arguments: {} },
      { name: 'ui_builder_view', arguments: { designId: 'owned-design', inline: true } },
    ]);
    await page.evaluate(() => { window.designSuccess = true; });
    await frame.locator('#tree > .row').click();
    await frame.locator('.recent img').waitFor();
    const thumb = await frame.locator('.recent img').getAttribute('src');
    await page.evaluate(() => { window.designSuccess = false; });
    await frame.locator('.recent.row').click();
    await frame.locator('#pane .error').getByText('Refresh failed; showing last successful render.', { exact: false }).waitFor();
    assert.equal(await frame.locator('#pane img').getAttribute('src'), thumb);
  } finally { await browser.close(); }
});

test('pending selection updates preserve order, deletion clears context, and rejection leaves actions usable', async () => {
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage();
    await page.setContent('<iframe id="app" style="width:100%;height:620px"></iframe>');
    await page.evaluate(({ html }) => {
      const frame = document.getElementById('app');
      window.contexts = [];
      window.rejectContext = false;
      const send = m => frame.contentWindow.postMessage({ jsonrpc: '2.0', ...m }, '*');
      const data = { schema: 'compose-preview-library/v1', mode: 'library', host: 'local', projects: [],
        designs: ['A', 'B'].map(name => ({ id: name, name, path: `/work/${name}.uid` })) };
      window.removeDesigns = () => send({ method: 'ui/notifications/tool-result', params: { structuredContent: { ...data, designs: [] } } });
      window.addEventListener('message', e => {
        if (e.source !== frame.contentWindow) return;
        const { id, method, params } = e.data;
        if (method === 'ui/initialize') {
          send({ id, result: {} });
          send({ method: 'ui/notifications/tool-result', params: { structuredContent: data } });
        } else if (method === 'ui/update-model-context') {
          window.contexts.push(params);
          if (window.contexts.length === 1) window.releaseContext = () => send({ id, result: {} });
          else if (window.rejectContext) send({ id, error: { code: -32601, message: 'Context updates unsupported' } });
          else send({ id, result: {} });
        }
      });
      frame.srcdoc = html;
    }, { html });
    const frame = page.frameLocator('#app');
    await frame.locator('#designs-tab').click();
    await page.waitForFunction(() => !!window.releaseContext);
    await frame.locator('#tree > .row').getByText('A', { exact: true }).click();
    await frame.locator('#tree > .row').getByText('B', { exact: true }).click();
    assert.equal(await page.evaluate(() => window.contexts.length), 1);
    await page.evaluate(() => window.releaseContext());
    await frame.locator('.chat-context').getByText('Selection shared with chat', { exact: true }).waitFor();
    assert.equal(await page.evaluate(() => JSON.parse(window.contexts.at(-1).content[0].text).selection.id), 'B');
    await page.evaluate(() => { window.rejectContext = true; });
    await frame.locator('#tree > .row').getByText('A', { exact: true }).click();
    await frame.locator('.chat-context').getByText('Context updates unsupported', { exact: false }).waitFor();
    assert.equal(await frame.getByRole('button', { name: 'Open in UI Builder' }).isEnabled(), true);
    await page.evaluate(() => { window.rejectContext = false; window.removeDesigns(); });
    await page.waitForFunction(() => window.contexts.at(-1).content.length === 0);
    assert.equal(await frame.locator('#pane .uri').count(), 0);
  } finally { await browser.close(); }
});

test('host-scoped recents restore actual thumbnails, skip removed items, and survive unavailable persistence', async () => {
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 1100, height: 720 } });
    await page.setContent('<iframe id="app" style="width:100%;height:700px;border:0"></iframe>');
    await page.evaluate(({ html, png }) => {
      const frame = document.getElementById('app');
      window.savedWidget = { modelContent: 'Existing context', privateContent: { unrelated: true } };
      window.contexts = [];
      window.paused = false;
      window.otherProject = false;
      window.throwPersistence = false;
      const send = m => frame.contentWindow.postMessage({ jsonrpc: '2.0', ...m }, '*');
      const listing = () => ({ schema: 'compose-preview-library/v1', mode: 'library', host: 'local',
        tools: { render: 'render_preview' }, projects: [window.otherProject
          ? { id: 'other', name: 'Other', path: '/work/other',
            modules: [{ path: ':app', previews: [{ uri: 'compose-preview://other/_app/ListScreenPreview', name: 'ListScreenPreview' }] }] }
          : { id: 'starter', name: 'ComposeStarter', path: '/work/starter',
            modules: [{ path: ':app', previews: [{ uri: 'compose-preview://starter/_app/ListScreenPreview', name: 'ListScreenPreview' }] }] }],
        designs: Array.from({ length: 10 }, (_, n) => ({ id: `design-${n}`, name: `Design ${n}`, path: `/work/design-${n}.uid` })) });
      window.reopen = () => {
        frame.srcdoc = html.replace('<script>', `<script>window.openai = { get widgetState() { return parent.savedWidget; }, setWidgetState(value) {
          if (parent.throwPersistence) throw new Error('Host persistence unavailable'); parent.savedWidget = value; } };`);
      };
      window.removeDesigns = () => send({ method: 'ui/notifications/tool-result', params: { structuredContent: { ...listing(), designs: [] } } });
      window.addEventListener('message', e => {
        if (e.source !== frame.contentWindow) return;
        const { id, method, params } = e.data;
        if (method === 'ui/initialize') {
          send({ id, result: {} });
          send({ method: 'ui/notifications/tool-result', params: { structuredContent: listing() } });
        } else if (method === 'ui/update-model-context') {
          window.contexts.push(params); send({ id, result: {} });
        } else if (method === 'tools/call') {
          if (window.paused) window.finishRender = () => send({ id, result: { isError: true, content: [{ type: 'text', text: 'Offline' }] } });
          else send({ id, result: { content: [{ type: 'image', mimeType: 'image/png', data: png }] } });
        }
      });
      window.reopen();
    }, { html, png });
    const frame = page.frameLocator('#app');
    await frame.locator('.recent img').waitFor();
    const thumb = await frame.locator('.recent img').getAttribute('src');
    assert.notEqual(thumb, `data:image/png;base64,${png}`);
    assert.equal(await frame.locator('.recent img').evaluate(img => img.naturalWidth <= 192 && img.naturalHeight <= 192), true);
    assert.equal(await page.evaluate(() => window.savedWidget.privateContent.unrelated), true);
    assert.equal(await page.evaluate(() => window.savedWidget.modelContent), 'Existing context');
    if (process.env.PREVIEW_LIBRARY_RECENT_EVIDENCE) await page.screenshot({ path: `${process.env.PREVIEW_LIBRARY_RECENT_EVIDENCE}/fresh-preview.png` });
    await page.evaluate(() => { window.paused = true; window.reopen(); });
    await frame.locator('#pane .status').getByText('Showing last successful render', { exact: false }).waitFor();
    assert.equal(await frame.locator('#pane img').getAttribute('src'), thumb);
    await page.waitForFunction(() => !!window.finishRender);
    await page.evaluate(() => window.finishRender());
    await frame.locator('#pane .error').getByText('Offline', { exact: false }).waitFor();
    assert.equal(await frame.locator('#pane img').getAttribute('src'), thumb);
    const evidence = process.env.PREVIEW_LIBRARY_RECENT_EVIDENCE;
    if (evidence) await page.screenshot({ path: `${evidence}/cached-preview.png` });
    await frame.locator('#designs-tab').click();
    for (let n = 0; n < 10; n++) await frame.locator('#tree > .row').getByText(`Design ${n}`, { exact: true }).click();
    assert.equal(await page.evaluate(() => window.savedWidget.privateContent.composePreviewRecents.items.length), 8);
    await frame.getByRole('button', { name: 'Clear recent items' }).click();
    assert.equal(await frame.locator('.recents').count(), 0);
    assert.equal(await page.evaluate(() => window.savedWidget.privateContent.composePreviewRecents.items.length), 0);
    await page.evaluate(() => { window.throwPersistence = true; });
    await frame.locator('#tree > .row').getByText('Design 1', { exact: true }).click();
    await frame.getByRole('button', { name: 'Open in UI Builder' }).waitFor();
    assert.equal(await frame.locator('.recent.row').count(), 1);
    await page.evaluate(() => window.removeDesigns());
    await frame.locator('#tree .empty').getByText('No .uid designs', { exact: false }).waitFor();
    assert.equal(await frame.locator('.recent.row').count(), 0);
    await page.evaluate(() => { window.otherProject = true; window.reopen(); });
    await frame.locator('#pane h2').getByText('ListScreenPreview', { exact: true }).waitFor();
    assert.equal(await frame.locator('#pane img').count(), 0);
  } finally { await browser.close(); }
});

test('a project appearing keeps the selection, its pixels and the recents; only removing it clears', async () => {
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 1100, height: 720 } });
    await page.setContent('<iframe id="app" style="width:100%;height:700px;border:0"></iframe>');
    await page.evaluate(({ html, png }) => {
      const frame = document.getElementById('app');
      window.savedWidget = {};
      window.contexts = [];
      const send = m => frame.contentWindow.postMessage({ jsonrpc: '2.0', ...m }, '*');
      const project = (id, name) => ({ id, name, path: `/work/${id}`,
        modules: [{ path: ':app', previews: [{ uri: `compose-preview://${id}/_app/ListScreenPreview`, name: `${name}Preview` }] }] });
      const listing = projects => ({ schema: 'compose-preview-library/v1', mode: 'library', host: 'local',
        tools: { render: 'render_preview' }, projects, designs: [] });
      window.push = ids => send({ method: 'ui/notifications/tool-result', params: { structuredContent:
        listing(ids.map(id => project(id, id === 'starter' ? 'Starter' : 'Added'))) } });
      window.addEventListener('message', e => {
        if (e.source !== frame.contentWindow) return;
        const { id, method, params } = e.data;
        if (method === 'ui/initialize') {
          send({ id, result: {} });
          window.push(['starter']);
        } else if (method === 'ui/update-model-context') {
          window.contexts.push(params); send({ id, result: {} });
        } else if (method === 'tools/call') send({ id, result: { content: [{ type: 'image', mimeType: 'image/png', data: png }] } });
      });
      frame.srcdoc = html.replace('<script>', `<script>window.openai = { get widgetState() { return parent.savedWidget; },
        setWidgetState(value) { parent.savedWidget = value; } };`);
    }, { html, png });
    const frame = page.frameLocator('#app');
    await frame.locator('.recent img').waitFor();
    await frame.locator('.chat-context').getByText('Selection shared with chat', { exact: true }).waitFor();
    const contexts = await page.evaluate(() => window.contexts.length);
    await page.evaluate(() => window.push(['starter', 'added']));
    await frame.locator('#tree').getByText('Added', { exact: true }).waitFor();
    assert.equal(await frame.locator('#pane h2').textContent(), 'StarterPreview');
    assert.equal(await frame.locator('#pane img').getAttribute('src'), `data:image/png;base64,${png}`);
    assert.equal(await frame.locator('.recent.row').count(), 1);
    assert.equal(await page.evaluate(() => window.contexts.length), contexts);
    assert.equal(await page.evaluate(() => window.savedWidget.privateContent.composePreviewRecents.items.length), 1);
    await page.evaluate(() => window.push(['added', 'third']));
    await page.waitForFunction(() => window.contexts.at(-1).content.length === 0);
    assert.equal(await frame.locator('#pane img').count(), 0);
    assert.equal(await page.evaluate(() => window.savedWidget.privateContent.composePreviewRecents.items.length), 1);
  } finally { await browser.close(); }
});
