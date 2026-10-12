// NODE_PATH=<Playwright installation>/node_modules node --test scripts/mcp-viewer-details.test.mjs
//
// The static card (Antigravity) with `render_preview details: ["a11y", "layout"]`: the Audit mode
// numbers the ATF findings over the render, and Layout bounds carries a legend whose rows list each
// box's layout properties.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');
const html = fs.readFileSync(process.env.MCP_RESOURCE_VIEWER_HTML || new URL('../mcp-app/compose-preview-viewer.html', import.meta.url), 'utf8');
const png = fs.readFileSync(new URL('../docs/evidence/preview-library-recovery/compose-starter.png', import.meta.url)).toString('base64');
const uri = 'compose-preview://starter/_app/ListScreenPreview';
const META = 'composePreview/detail';

const details = {
  a11y: {
    summary: 'a11y: 1 error, 1 warning (TouchTargetSize ×1, TextContrast ×1)',
    findings: [
      { level: 'ERROR', rule: 'TouchTargetSize', message: "This item's height is 41dp.", node: 'android.view.View', bounds: [68, 369, 386, 450] },
      { level: 'WARNING', rule: 'TextContrast', message: 'Low contrast.' },
    ],
  },
  layout: {
    summary: 'layout: 2 nodes (layout/inspector)',
    boxes: [
      { label: 'Column', depth: 0, bounds: [0, 0, 454, 454], props: ['454×454px', 'pad 8.0dp 16.0dp'], source: 'MainActivity.kt:120' },
      { label: 'Button', depth: 1, bounds: [68, 369, 386, 450], props: ['318×98px', 'scale 0.83×0.83'] },
    ],
  },
};

async function card(browser, withOverlay = true) {
  const page = await browser.newPage({ viewport: { width: 900, height: 1200 } });
  const content = [
    { type: 'image', mimeType: 'image/png', data: png },
    { type: 'text', text: JSON.stringify({ uri, widthPx: 454, heightPx: 454 }) },
    { type: 'text', text: JSON.stringify(details), _meta: { [META]: 'details' } },
    ...(withOverlay ? [{ type: 'image', mimeType: 'image/png', data: png, _meta: { [META]: 'a11y/overlay' } }] : []),
  ];
  const envelope = { version: 1, arguments: { uri }, result: { content } };
  const source = `${html}<script type="application/json" id="compose-preview-result">${JSON.stringify(envelope).replace(/</g, '\\u003c')}</script>`;
  await page.setContent(source);
  await page.locator('#canvas img').waitFor();
  return page;
}

const modeLabels = page => page.locator('#detail-modes button').allTextContents();

test('static card offers Plain, A11y overlay, Audit and Layout bounds', async () => {
  const browser = await chromium.launch();
  try {
    const page = await card(browser);
    assert.deepEqual(await modeLabels(page), ['Plain', 'A11y overlay', 'Audit', 'Layout bounds']);
    // Without the overlay PNG there is nothing for "A11y overlay" to show; Audit still draws the findings.
    const bare = await card(browser, false);
    assert.deepEqual(await modeLabels(bare), ['Plain', 'Audit', 'Layout bounds']);
  } finally {
    await browser.close();
  }
});

test('Audit numbers the findings that have bounds and keys the level colours', async () => {
  const browser = await chromium.launch();
  try {
    const page = await card(browser);
    await page.getByRole('button', { name: 'Audit' }).click();
    assert.equal(await page.locator('.detail-boxes rect.finding').count(), 1);
    assert.equal(await page.locator('.detail-boxes rect.finding.error').count(), 1);
    assert.deepEqual(await page.locator('.detail-badges span').allTextContents(), ['1']);
    assert.equal(await page.locator('#findings li').first().locator('.badge').textContent(), '1');
    assert.deepEqual(await page.locator('#detail-key > span').allTextContents(), ['Error']);
    assert.equal(await page.locator('#detail-legend').isHidden(), true);
    await page.locator('#findings li').first().hover();
    assert.equal(await page.locator('.detail-boxes rect.active').count(), 1);
  } finally {
    await browser.close();
  }
});

test('Layout bounds has a legend listing each box with its properties', async () => {
  const browser = await chromium.launch();
  try {
    const page = await card(browser);
    await page.getByRole('button', { name: 'Layout bounds' }).click();
    assert.equal(await page.locator('.detail-boxes rect.layout').count(), 2);
    assert.equal(await page.locator('.detail-boxes rect.layout.d1').count(), 1);
    assert.deepEqual(await page.locator('.detail-badges span').allTextContents(), ['1', '2']);
    const rows = page.locator('#detail-legend li');
    assert.equal(await rows.count(), 2);
    assert.equal(await rows.nth(0).locator('strong').textContent(), 'Column');
    assert.equal(await rows.nth(0).locator('.props').textContent(), '454×454px · pad 8.0dp 16.0dp · MainActivity.kt:120');
    assert.equal(await rows.nth(1).locator('.props').textContent(), '318×98px · scale 0.83×0.83');
    const key = await page.locator('#detail-key').textContent();
    assert.match(key, /Root/);
    assert.match(key, /Depth 1/);
    assert.match(key, /nesting depth/);
    await rows.nth(1).hover();
    assert.equal(await page.locator('.detail-boxes rect.active').getAttribute('data-key'), '1');
    // Back to Plain clears the boxes, the badges and the legend.
    await page.getByRole('button', { name: 'Plain' }).click();
    assert.equal(await page.locator('.detail-boxes, .detail-badges').count(), 0);
    assert.equal(await page.locator('#detail-legend').isHidden(), true);
    assert.equal(await page.locator('#detail-key').isHidden(), true);
  } finally {
    await browser.close();
  }
});
