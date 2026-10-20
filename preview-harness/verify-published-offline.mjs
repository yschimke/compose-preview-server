// Read-only production check. Designs are created only in this browser's IndexedDB.
// npm --prefix preview-harness ci
// CHROMIUM_PATH=/path/to/chromium node preview-harness/verify-published-offline.mjs [output-dir]
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { chromium } from '@playwright/test';

const output = resolve(process.argv[2] || 'build/published-offline');
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined });

async function entries(page) {
  return page.evaluate(() => new Promise((resolve, reject) => {
    const opening = indexedDB.open('ui-builder-local');
    opening.onerror = () => reject(opening.error);
    opening.onsuccess = () => {
      const db = opening.result;
      const tx = db.transaction('entries');
      const store = tx.objectStore('entries');
      const keys = store.getAllKeys();
      const values = store.getAll();
      tx.oncomplete = () => {
        resolve(keys.result.map((key, i) => ({ key, value: JSON.parse(values.result[i]) })));
        db.close();
      };
      tx.onerror = () => reject(tx.error);
    };
  }));
}

async function until(read) {
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    const value = await read();
    if (value) return value;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error('timed out waiting for the saved browser document');
}

// The service encodes empty defaults that the browser store omits.
function nodesWithDefaults(nodes) {
  return Object.fromEntries(Object.entries(nodes).map(([id, node]) => [id, {
    properties: {}, modifiers: [], slots: {}, eventBindings: {}, assetBindings: {}, tokenBindings: {},
    ...node,
  }]));
}

try {
  for (const [name, base, worker] of [
    ['root', 'https://ui.coo.ee/', '/push-sw.js'],
    ['path', 'https://preview.coo.ee/ui-builder/', '/ui-builder/ui-builder-sw.js'],
  ]) {
    const shots = resolve(output, name);
    await mkdir(shots, { recursive: true });
    const context = await browser.newContext({ viewport: { width: 1400, height: 1600 }, locale: 'en-US' });
    // Caddy redirects HTML navigations to the root host. Exercise the real path-layout responses
    // using a different Accept header, without supplying any fixture responses.
    if (name === 'path') {
      await context.route('**/ui-builder/**', route => route.continue({
        headers: { ...route.request().headers(), accept: '*/*' },
      }));
    }
    const errors = [];
    const writes = [];
    const watch = page => {
      page.on('pageerror', error => errors.push(String(error)));
      page.on('request', request => {
        if (!request.url().includes('/api/ui-builder/v1/requests') || request.method() !== 'POST') return;
        const type = request.postDataJSON().request?.type;
        if (!['listCatalogs', 'listDesigns'].includes(type)) writes.push(type);
      });
    };
    let page = await context.newPage();
    watch(page);
    let published;
    page.on('response', async response => {
      if (response.url().endsWith('/catalogs/m3-catalog/templates') && response.ok()) {
        published = await response.json();
      }
    });
    const url = `${base}?session=live&storage=local&catalog=m3-catalog`;
    try {
      const version = await (await context.request.get(new URL('/version', base).href)).json();
      const editor = await (await context.request.get(new URL('/ui-builder/ui-builder-web.json', base).href)).json();
      await page.goto(url);
      assert.equal(page.url(), url, 'the check must remain on the intended host layout');
      await page.waitForFunction(() => document.documentElement.dataset.uiBuilderReady === 'true', null, { timeout: 180000 });
      await page.waitForFunction(() => navigator.serviceWorker.controller !== null, null, { timeout: 120000 });
      await until(async () => published && (await entries(page)).some(row => row.key === 'ui-builder.local.templates."m3-catalog"'));
      const registrations = await page.evaluate(async () => (await navigator.serviceWorker.getRegistrations())
        .map(row => ({ scope: row.scope, script: row.active?.scriptURL })));
      assert.ok(registrations.some(row => new URL(row.script).pathname === worker));
      await page.screenshot({ path: resolve(shots, 'online-chooser.png') });
      await page.close();
      await context.setOffline(true);
      page = await context.newPage();
      watch(page);
      await page.goto(url);
      await page.waitForFunction(() => document.documentElement.dataset.uiBuilderReady === 'true', null, { timeout: 120000 });
      await page.screenshot({ path: resolve(shots, 'offline-chooser.png') });
      // The chooser is drawn on Compose canvas. Positions are recorded in the chooser evidence.
      await page.mouse.click(240, 525); // Published Hello template.
      await page.mouse.click(245, 1066); // Create.
      const created = await until(async () => (await entries(page)).find(row => row.value?.seed)?.value);
      assert.equal(created.seed.title, published.templates.hello.title);
      assert.notEqual(created.seed.id, published.templates.hello.id);
      assert.equal(created.seed.revision, 0);
      assert.deepEqual(created.seed.catalogPin, published.catalogPin);
      assert.deepEqual(nodesWithDefaults(created.seed.nodes), nodesWithDefaults(published.templates.hello.nodes));
      assert.deepEqual(created.seed.roots, published.templates.hello.roots);
      assert.deepEqual(created.seed.environment, published.templates.hello.environment);
      assert.deepEqual(errors, []);
      assert.deepEqual(writes, []);
      await page.waitForTimeout(1500);
      await page.screenshot({ path: resolve(shots, 'offline-created.png') });
      await writeFile(resolve(shots, 'result.json'), JSON.stringify({
        url, serverVersion: version.version, editorVersion: editor.version,
        networkDisabled: true, coldTab: true, noInterceptedResponses: true,
        pathNavigationAcceptOverride: name === 'path', registrations, template: 'hello',
        publishedPin: published.catalogPin, seed: created.seed, pageErrors: errors, serverWriteRequests: writes,
      }, null, 2) + '\n');
      console.log(`ok ${name}: published Hello cached; cold offline tab creates the repinned document; no server writes`);
    } finally {
      await context.close();
    }
  }
} finally {
  await browser.close();
}
