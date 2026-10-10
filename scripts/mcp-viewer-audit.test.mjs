// NODE_PATH=<Playwright installation>/node_modules node --test scripts/mcp-viewer-audit.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');
const html = fs.readFileSync(new URL('../mcp-app/compose-preview-viewer.html', import.meta.url), 'utf8');
const png = fs.readFileSync(new URL('../docs/evidence/preview-library-recovery/compose-starter.png', import.meta.url)).toString('base64');
const uri = 'compose-preview://starter/_app/ListScreenPreview';

async function setup(browser, options = {}, source = html) {
  const page = await browser.newPage({ viewport: { width: 1000, height: 1100 } });
  await page.setContent('<iframe id="app" style="width:100%;height:1060px;border:0"></iframe>');
  await page.evaluate(({ source, png, uri, options }) => {
    const frame = document.getElementById('app');
    window.messages = [];
    window.calls = [];
    const send = m => frame.contentWindow.postMessage({ jsonrpc: '2.0', ...m }, '*');
    const rendered = (value = {}) => ({ structuredContent: { uri, previewId: 'ListScreenPreview', ...value },
      content: [{ type: 'image', mimeType: 'image/png', data: png }] });
    window.nextResult = value => send({ method: 'ui/notifications/tool-result', params: rendered(value) });
    window.addEventListener('message', e => {
      if (e.source !== frame.contentWindow || !e.data.method || e.data.id === undefined) return;
      const { id, method, params } = e.data;
      const reply = result => send({ id, result });
      if (method === 'ui/initialize') {
        reply({ hostCapabilities: { serverTools: {}, ...(options.messaging === false ? {} : { message: { text: {} } }) }, hostContext: {} });
        send({ method: 'ui/notifications/tool-input', params: { arguments: { uri, overrides: { localeTag: 'de-DE' }, token: 'transport-secret' } } });
        window.nextResult(options.subject || {});
      } else if (method === 'tools/list') reply({ tools: options.saved ? [{ name: 'catalog__ui_builder_get_guidelines' }] : [] });
      else if (method === 'ui/message') {
        window.messages.push(params);
        if (options.reject === 'rpc') send({ id, error: { code: -32601, message: 'Messages unavailable' } });
        else reply(options.reject ? { isError: true } : {});
      } else if (method === 'tools/call') {
        window.calls.push({ method, params });
        reply(options.saved);
      } else {
        window.calls.push({ method, params });
        reply({});
      }
    });
    if (options.static) {
      const envelope = { version: 1, arguments: { uri }, result: rendered() };
      source += `<script type="application/json" id="compose-preview-result">${JSON.stringify(envelope)}<\/script>`;
    }
    frame.srcdoc = source;
  }, { source, png, uri, options });
  await page.frameLocator('#app').locator('#canvas img').waitFor();
  return page;
}

const launch = () => chromium.launch({ executablePath: process.env.HARNESS_CHROMIUM || undefined, args: ['--no-sandbox'] });

test('review action sends the selected subject and overrides to chat without a provider call', async () => {
  const browser = await launch();
  try {
    const evidence = process.env.MCP_AUDIT_EVIDENCE;
    if (evidence && process.env.MCP_AUDIT_BEFORE_HTML) {
      const before = await setup(browser, {}, fs.readFileSync(process.env.MCP_AUDIT_BEFORE_HTML, 'utf8'));
      await before.screenshot({ path: `${evidence}/before.png` });
      await before.close();
    }
    const page = await setup(browser);
    const frame = page.frameLocator('#app');
    await frame.locator('#audit-run').waitFor();
    if (evidence) await page.screenshot({ path: `${evidence}/after.png` });
    await frame.locator('#audit-run').click();
    await frame.locator('#audit-status').getByText('Review requested in chat.', { exact: false }).waitFor();
    const messages = await page.evaluate(() => window.messages);
    assert.equal(messages.length, 1);
    assert.equal(messages[0].role, 'user');
    const prompt = messages[0].content[0].text;
    assert.ok(prompt.includes(uri));
    assert.ok(prompt.includes('de-DE'));
    assert.ok(prompt.includes('unchecked coverage'));
    assert.ok(prompt.includes('do not start a paid provider run'));
    assert.ok(!prompt.includes('transport-secret'));
    assert.equal(await page.evaluate(() => window.calls.filter(c => c.method === 'tools/call').length), 0);
    assert.ok((await frame.locator('#audit-status').innerText()).includes('not an audit result'));
    // A new result resets the subject and delivery state; the next click uses it.
    await page.evaluate(() => window.nextResult({ uri: 'compose-preview://starter/_app/OtherPreview' }));
    await page.waitForFunction(() => document.querySelector('#app').contentDocument.querySelector('#audit-prompt').value.includes('OtherPreview'));
    await frame.locator('#audit-run').click();
    await page.waitForFunction(() => window.messages.length === 2);
    assert.ok((await page.evaluate(() => window.messages[1].content[0].text)).includes('OtherPreview'));
  } finally { await browser.close(); }
});

for (const options of [{ messaging: false }, { static: true }, { reject: true }, { reject: 'rpc' }]) {
  test(`copyable request survives missing or rejected messaging ${JSON.stringify(options)}`, async () => {
    const browser = await launch();
    try {
      const page = await setup(browser, options);
      const frame = page.frameLocator('#app');
      await frame.locator('#audit').waitFor();
      if (options.reject) {
        await frame.locator('#audit-run').click();
        await frame.locator('#audit-status').getByText('Delivery could not be confirmed.', { exact: false }).waitFor();
      }
      assert.equal(await frame.locator('#audit-run').isVisible(), false);
      assert.ok((await frame.locator('#audit-prompt').inputValue()).includes(uri));
      if (!options.reject) assert.equal(await frame.locator('#audit-copy').evaluate(el => el.open), false);
      if (!await frame.locator('#audit-copy').evaluate(el => el.open)) await frame.locator('#audit-copy summary').click();
      await frame.locator('#audit-select').click();
      assert.ok(await frame.locator('#audit-prompt').evaluate(el => el.selectionEnd === el.value.length));
      assert.equal(await page.evaluate(() => window.messages.length), options.reject ? 1 : 0);
    } finally { await browser.close(); }
  });
}

test('UI Builder review preserves revision and links result recording to its Issues panel', async () => {
  const browser = await launch();
  try {
    const page = await setup(browser, { subject: { designId: 'my-design', revision: 12 } });
    const prompt = await page.frameLocator('#app').locator('#audit-prompt').inputValue();
    assert.ok(prompt.includes('"designId":"my-design","revision":12'));
    assert.ok(prompt.includes('ui_builder_record_guidelines'));
    assert.ok(prompt.includes('canonical editor link'));
    assert.ok(prompt.includes('home/thread links in chat rather than duplicating'));
    assert.ok(!prompt.includes('transport-secret'));
  } finally { await browser.close(); }
});

test('credential-bearing subjects cannot become a chat request', async () => {
  const browser = await launch();
  try {
    const page = await setup(browser, { subject: { uri: `${uri}?token=private` } });
    assert.equal(await page.frameLocator('#app').locator('#audit').isVisible(), false);
    assert.equal(await page.evaluate(() => window.messages.length), 0);
  } finally { await browser.close(); }
});

for (const state of ['fresh', 'stale', 'empty', 'denied']) {
  test(`saved UI Builder review remains explicit about coverage and freshness: ${state}`, async () => {
    const browser = await launch();
    try {
      const value = { schema: 'compose-preview/ui-builder-guidelines/v1', designId: 'my-design', currentRevision: 12,
        stale: state === 'stale', summary: 'One rule needs attention.', unanswered: ['wear-2'],
        record: state === 'empty' ? null : { schema: 'compose-ui-builder/guidelines-result/v1', revision: state === 'stale' ? 11 : 12,
          rulesVersion: 3, model: 'test-model', asked: ['wear-1', 'wear-2'], verdicts: [{ ruleId: 'wear-1', verdict: 'fail' }] },
        findings: [{ severity: 'warning', code: 'wear-1', message: '<b>Missing label</b>', nodeId: 'node-1' }] };
      const saved = state === 'denied' ? { isError: true, content: [{ type: 'text', text: 'access denied' }] }
        : { content: [{ type: 'text', text: JSON.stringify(value) }] };
      const page = await setup(browser, { subject: { designId: 'my-design', revision: 12 }, saved });
      const frame = page.frameLocator('#app');
      await frame.locator('#audit-saved').click();
      await page.waitForFunction(() => !document.querySelector('#app').contentDocument.querySelector('#audit-saved').disabled);
      const summary = await frame.locator('#audit-summary').innerText();
      if (state === 'empty') assert.ok(summary.includes('No saved guideline review'));
      else if (state === 'denied') assert.ok(summary.includes('Saved review unavailable'));
      else {
        assert.ok(summary.includes(state === 'stale' ? 'Stale saved review' : 'Saved review'));
        assert.ok(summary.includes('1/2 asked rules answered; 1 unchecked'));
        assert.ok(summary.includes('test-model'));
        assert.ok((await frame.locator('#audit-findings').innerText()).includes('<b>Missing label</b>'));
        assert.equal(await frame.locator('#audit-findings b').count(), 0);
      }
      const calls = await page.evaluate(() => window.calls.filter(c => c.method === 'tools/call'));
      assert.deepEqual(calls.map(c => c.params.name), ['catalog__ui_builder_get_guidelines']);
      assert.equal(calls[0].params.arguments.designId, 'my-design');
      assert.equal(await page.evaluate(() => window.messages.length), 0);
    } finally { await browser.close(); }
  });
}
