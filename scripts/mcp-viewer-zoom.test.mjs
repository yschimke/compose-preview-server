// NODE_PATH=<Playwright installation>/node_modules node --test scripts/mcp-viewer-zoom.test.mjs
//
// Fit / Actual size on a static card (Antigravity) held to a short frame: Actual size shows the
// whole render at its own pixels, so the page grows to it, rather than a fitted-height slice that
// scrolls inside the canvas; Fit goes back.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import zlib from 'node:zlib';
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');
const html = fs.readFileSync(process.env.MCP_RESOURCE_VIEWER_HTML || new URL('../mcp-app/compose-preview-viewer.html', import.meta.url), 'utf8');

// A 300×1200 render: taller than any fitted frame.
function tallPng(width, height) {
  const crc = buffer => {
    let c = ~0;
    for (const byte of buffer) {
      c ^= byte;
      for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xedb88320 : c >>> 1;
    }
    return ~c >>> 0;
  };
  const chunk = (type, data) => {
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const out = Buffer.alloc(body.length + 8);
    out.writeUInt32BE(data.length, 0);
    body.copy(out, 4);
    out.writeUInt32BE(crc(body), body.length + 4);
    return out;
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header.set([8, 2, 0, 0, 0], 8);
  const row = Buffer.concat([Buffer.from([0]), Buffer.alloc(width * 3, 0x40)]);
  const raw = Buffer.concat(Array.from({ length: height }, () => row));
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
  ]).toString('base64');
}

test('Actual size shows the whole render and Fit goes back', async () => {
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 640, height: 520 } });
    const uri = 'compose-preview://app/_app/TallPreview';
    const envelope = {
      version: 1,
      arguments: { uri },
      result: { content: [
        { type: 'image', mimeType: 'image/png', data: tallPng(300, 1200) },
        { type: 'text', text: JSON.stringify({ uri, widthPx: 300, heightPx: 1200 }) },
      ] },
    };
    await page.setContent(`${html}<script type="application/json" id="compose-preview-result">${JSON.stringify(envelope).replace(/</g, '\\u003c')}</script>`);
    await page.locator('#canvas img').waitFor();
    const zoom = page.locator('#zoom');
    await zoom.waitFor({ state: 'visible' });
    const measure = () => page.evaluate(() => {
      const image = document.querySelector('.preview-stage > img').getBoundingClientRect();
      const canvas = document.querySelector('#canvas');
      return { height: Math.round(image.height), canvas: canvas.clientHeight, scroll: canvas.scrollHeight };
    });
    const fitted = await measure();
    assert.ok(fitted.height < 520, `fitted to the frame: ${fitted.height}`);

    await zoom.click();
    assert.equal(await zoom.textContent(), 'Fit');
    const actual = await measure();
    assert.equal(actual.height, 1200);
    // Nothing of the render is hidden inside the canvas: the canvas is as tall as what it holds.
    assert.ok(actual.canvas >= actual.scroll, `canvas ${actual.canvas} clips ${actual.scroll}`);

    await zoom.click();
    assert.equal(await zoom.textContent(), 'Actual size');
    assert.equal((await measure()).height, fitted.height);
  } finally {
    await browser.close();
  }
});
