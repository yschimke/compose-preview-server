import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { captureDocument, publish, selectDesign, readCommittedFile } from './publish-references.mjs';

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
    const original = '{"schema":"compose-preview-references/v1","references":[]}';
    fs.writeFileSync(manifest, original);
    const png = Buffer.alloc(24); Buffer.from('89504e470d0a1a0a', 'hex').copy(png);
    png.writeUInt32BE(100, 16); png.writeUInt32BE(100, 20);
    const options = { root, out: root, revision: 'a'.repeat(40), config: { schema: 'compose-ui-builder-references/v1', repository: 'owner/app', captures: [entry] }, render: (_, out) => fs.writeFileSync(out, png) };
    assert.throws(() => publish(options), /Wrong render extent/);
    assert.equal(fs.readFileSync(manifest, 'utf8'), original);
    png.writeUInt32BE(412, 16); png.writeUInt32BE(720, 20);
    const [ref] = publish(options);
    assert.equal(ref.source.provider, 'ui-builder');
    assert.match(ref.source.uri, /\/blob\/a{40}\/inbox.uid$/);
    assert.equal(ref.previewId, 'phone-detail');
    assert.equal(ref.source.attributes.documentSha256.length, 64);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('a dirty capture plan is refused even when its UID is unchanged', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'uid-source-test-'));
  const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  try {
    git('init');
    fs.writeFileSync(path.join(root, 'inbox.uid'), JSON.stringify(doc));
    const plan = JSON.stringify({ captures: [entry] });
    fs.writeFileSync(path.join(root, 'references.json'), plan);
    git('add', '.');
    git('-c', 'user.name=Yuri Schimke', '-c', 'user.email=yuri@schimke.ee', '-c', 'commit.gpgsign=false', 'commit', '-m', 'test: source fixture');
    const revision = git('rev-parse', 'HEAD').trim();
    assert.equal(readCommittedFile(root, revision, 'references.json').toString(), plan);
    fs.writeFileSync(path.join(root, 'references.json'), JSON.stringify({ captures: [{ ...entry, widthDp: 700 }] }));
    assert.doesNotThrow(() => readCommittedFile(root, revision, 'inbox.uid'));
    assert.throws(() => readCommittedFile(root, revision, 'references.json'), /differs from the selected source commit/);
    assert.throws(() => execFileSync(process.execPath, [fileURLToPath(new URL('./publish-references.mjs', import.meta.url)),
      '--root', root, '--plan', 'references.json', '--revision', revision, '--out', path.join(root, 'out'),
      '--renderer', 'must-not-start', '--catalog', 'must-not-read',
    ], { stdio: ['ignore', 'pipe', 'pipe'] }), error =>
      error.status === 1 && error.stderr.toString().includes('Input differs from the selected source commit: references.json'));
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('a malformed existing manifest is rejected before rendering', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'uid-manifest-test-'));
  try {
    fs.mkdirSync(path.join(root, 'references'));
    fs.writeFileSync(path.join(root, 'references/index.json'), '{}');
    let renders = 0;
    assert.throws(() => publish({ root, out: root, revision: 'a'.repeat(40),
      config: { schema: 'compose-ui-builder-references/v1', repository: 'owner/app', captures: [entry] },
      render: () => renders++,
    }), /Existing reference manifest/);
    assert.equal(renders, 0);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});
