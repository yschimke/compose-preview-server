import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { captureDocument, publish, selectDesign } from './publish-references.mjs';

const doc = { schema: 'compose-ui-builder-document/v1', id: 'inbox', environment: {}, nodes: { panes: { properties: {} } } };
const entry = { id: 'phone', previewId: 'phone-detail', designId: 'inbox', file: 'inbox.uid', widthDp: 412, heightDp: 720, density: 1, theme: 'dark', state: 'detail', properties: { panes: { activePaneIndex: { type: 'int', value: 1 } } } };

test('capture selects a named design, pins dimensions and changes only declared state', () => {
  const text = JSON.stringify({ schema: 'compose-ui-builder-designs/v1', active: 'other', designs: [doc, { ...doc, id: 'other' }] });
  const captured = captureDocument(text, entry);
  assert.equal(captured.id, 'inbox');
  assert.equal(captured.environment.widthDp, 412);
  assert.equal(captured.environment.density, 1);
  assert.equal(captured.nodes.panes.properties.activePaneIndex.value, 1);
  assert.deepEqual(selectDesign(text, 'inbox').nodes.panes.properties, {});
  assert.throws(() => selectDesign(text, 'missing'));
});

test('a failed or wrong-size render leaves the published manifest untouched', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'uid-publish-test-'));
  try {
    fs.writeFileSync(path.join(root, 'inbox.uid'), JSON.stringify(doc));
    fs.mkdirSync(path.join(root, 'references'));
    const manifest = path.join(root, 'references/index.json');
    fs.writeFileSync(manifest, '{"references":[]}');
    const png = Buffer.alloc(24); Buffer.from('89504e470d0a1a0a', 'hex').copy(png);
    png.writeUInt32BE(100, 16); png.writeUInt32BE(100, 20);
    const options = { root, out: root, revision: 'a'.repeat(40), config: { schema: 'compose-ui-builder-references/v1', repository: 'owner/app', captures: [entry] }, render: (_, out) => fs.writeFileSync(out, png) };
    assert.throws(() => publish(options), /Wrong render extent/);
    assert.equal(fs.readFileSync(manifest, 'utf8'), '{"references":[]}');
    png.writeUInt32BE(412, 16); png.writeUInt32BE(720, 20);
    const [ref] = publish(options);
    assert.equal(ref.source.provider, 'ui-builder');
    assert.match(ref.source.uri, /\/blob\/a{40}\/inbox.uid$/);
    assert.equal(ref.previewId, 'phone-detail');
    assert.equal(ref.source.attributes.documentSha256.length, 64);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});
