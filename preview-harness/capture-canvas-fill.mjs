import {chromium} from '@playwright/test';
import {mkdir, writeFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';

const [phase, url] = process.argv.slice(2);
assert(url, 'Usage: node preview-harness/capture-canvas-fill.mjs before|after <saved-design-url>');
assert(['before', 'after'].includes(phase));
const dir = fileURLToPath(new URL('../docs/design/evidence/ui-builder-canvas-fill', import.meta.url));
await mkdir(dir, {recursive: true});
const browser = await chromium.launch({headless: true, executablePath: process.env.CHROME_PATH || undefined, args: ['--enable-unsafe-swiftshader', '--use-gl=angle', '--force-renderer-accessibility']});
const errors = [];
try {
  const page = await browser.newPage({viewport: {width: 1600, height: 1050}, deviceScaleFactor: 1});
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(url);
  await page.waitForFunction(() => document.documentElement.dataset.uiBuilderReady === 'true', null, {timeout: 60000});
  await page.waitForFunction(() => globalThis.__uiBuilderInspection?.generation?.completed, null, {timeout: 30000});
  const observed = await page.evaluate(() => ({canvas: globalThis.__uiBuilderEditorCanvas, inspection: globalThis.__uiBuilderInspection}));
  const child = observed.inspection.nodes.find(node => node.nodeId === 'First').bounds;
  if (phase === 'before') assert.equal(child.height, 0);
  else {
    assert(Math.abs(child.height / observed.canvas.scale - 312) <= 1);
    assert(Math.abs(child.width / observed.canvas.scale - 312) <= 1);
  }
  assert.deepEqual(errors, []);
  await page.screenshot({path: `${dir}/${phase}.png`});
  await writeFile(`${dir}/${phase}.json`, JSON.stringify({...observed, errors}, null, 2) + '\n');
  console.log(JSON.stringify({phase, child, scale: observed.canvas.scale, errors}));
} finally { await browser.close(); }
