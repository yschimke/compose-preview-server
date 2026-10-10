#!/usr/bin/env node
// Publish reviewed UID documents through the existing provider-neutral reference contract.
// Rendering is delegated to the installed server CLI; no second renderer or scorer lives here.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const schemas = new Set(['compose-ui-builder-document/v1', 'compose-ui-builder-document/v1-candidate']);
const safeId = /^[A-Za-z0-9][A-Za-z0-9._-]{0,150}$/;
export const digest = bytes => createHash('sha256').update(bytes).digest('hex');

export function selectDesign(text, id) {
  const file = JSON.parse(text);
  const documents = file.schema === 'compose-ui-builder-designs/v1' ? file.designs : [file];
  if (!Array.isArray(documents) || new Set(documents.map(d => d.id)).size !== documents.length)
    throw new Error('UID contains duplicate or missing designs');
  const document = documents.find(d => d.id === id);
  if (!document || !schemas.has(document.schema)) throw new Error(`No supported design '${id}'`);
  return structuredClone(document);
}

export function captureDocument(text, entry) {
  if (!safeId.test(entry.id) || !safeId.test(entry.previewId)) throw new Error('Unsafe capture identity');
  if (typeof entry.state !== 'string' || !entry.state.trim()) throw new Error('Capture requires an explicit state');
  const document = selectDesign(text, entry.designId);
  const { widthDp, heightDp, theme } = entry;
  if (![1, 2, 3, 4].includes(entry.density ?? 2)) throw new Error('Unsupported capture density');
  if (![widthDp, heightDp].every(n => Number.isInteger(n) && n > 0 && n <= 4096) || !['light', 'dark'].includes(theme))
    throw new Error('Capture requires explicit bounded dimensions and theme');
  document.environment = { ...document.environment, widthDp, heightDp, theme, density: entry.density ?? 2, fontScale: 1,
    locale: 'en-US', layoutDirection: 'ltr', animations: 'settled', networkAccess: false, exportDevices: [] };
  for (const [nodeId, properties] of Object.entries(entry.properties ?? {})) {
    if (!document.nodes[nodeId]) throw new Error(`Unknown UID node '${nodeId}'`);
    Object.assign(document.nodes[nodeId].properties, properties);
  }
  return document;
}

export function pngSize(bytes) {
  if (bytes.length < 24 || !bytes.subarray(0, 8).equals(Buffer.from('89504e470d0a1a0a', 'hex')))
    throw new Error('Renderer did not produce a PNG');
  return [bytes.readUInt32BE(16), bytes.readUInt32BE(20)];
}

export function publish({ config, root, out, revision, renderer, catalog, components, render }) {
  if (config.schema !== 'compose-ui-builder-references/v1') throw new Error('Unsupported reference plan');
  if (!/^[a-f0-9]{40}$/.test(revision)) throw new Error('A full source commit is required');
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(config.repository)) throw new Error('Invalid repository');
  if (!Array.isArray(config.captures) || !config.captures.length) throw new Error('No captures');
  const provenance = catalog ? { rendererBundleSha256: digest(fs.readFileSync(catalog)) } : {};
  if (components) provenance.componentsSha256 = digest(fs.readFileSync(components.slice(components.indexOf('=') + 1)));
  const ids = new Set(), previews = new Set();
  const inputs = config.captures.map(entry => {
    if (ids.has(entry.id) || previews.has(entry.previewId)) throw new Error('Duplicate reference or preview identity');
    ids.add(entry.id); previews.add(entry.previewId);
    const file = path.resolve(root, entry.file);
    const relative = path.relative(fs.realpathSync(root), fs.realpathSync(file));
    if (relative.startsWith('..') || path.isAbsolute(relative) || !entry.file.endsWith('.uid')) throw new Error('UID must be inside the project');
    const text = fs.readFileSync(file, 'utf8');
    return { entry, document: captureDocument(text, entry), sourceDigest: digest(text) };
  });
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'uid-references-'));
  try {
    const references = inputs.map(({ entry, document, sourceDigest }) => {
      const uid = Buffer.from(JSON.stringify(document, null, 2) + '\n');
      const documentPath = path.join(temp, `${entry.id}.uid`);
      const imagePath = path.join(temp, `${entry.id}.png`);
      fs.writeFileSync(documentPath, uid);
      if (render) render(documentPath, imagePath);
      else execFileSync(renderer, ['design', 'render', '--local', '--document', documentPath, '--catalog', catalog, ...(components ? ['--components', components] : []), '-o', imagePath], { stdio: 'inherit' });
      const png = fs.readFileSync(imagePath);
      const [width, height] = pngSize(png);
      if (width !== entry.widthDp * (entry.density ?? 2) || height !== entry.heightDp * (entry.density ?? 2)) throw new Error(`Wrong render extent for ${entry.id}: ${width}x${height}`);
      return {
        id: entry.id, previewId: entry.previewId, label: `UI Builder · ${entry.widthDp}dp · ${entry.state} · ${entry.theme}`,
        raster: { path: `references/${entry.id}-${digest(png)}.png`, width, height, sha256: digest(png) },
        artifact: { kind: 'uid', path: `references/${entry.id}-${digest(uid)}.uid` },
        source: { provider: 'ui-builder', revision,
          uri: `https://github.com/${config.repository}/blob/${revision}/${entry.file.split('/').map(encodeURIComponent).join('/')}`,
          attributes: { ...provenance, designId: entry.designId, sourceSha256: sourceDigest, documentSha256: digest(uid),
            widthDp: String(entry.widthDp), heightDp: String(entry.heightDp), theme: entry.theme, state: entry.state, density: String(entry.density ?? 2) } },
      };
    });
    // Only replace the manifest after every reference rendered successfully. Other providers remain.
    const dir = path.join(out, 'references');
    fs.mkdirSync(dir, { recursive: true });
    const manifestPath = path.join(dir, 'index.json');
    const old = fs.existsSync(manifestPath) ? JSON.parse(fs.readFileSync(manifestPath, 'utf8')).references : [];
    const retained = old.filter(r => r.source?.provider !== 'ui-builder');
    if (retained.some(r => ids.has(r.id))) throw new Error('Reference id collides with another provider');
    for (const ref of references) {
      fs.copyFileSync(path.join(temp, `${ref.id}.uid`), path.join(out, ref.artifact.path));
      fs.copyFileSync(path.join(temp, `${ref.id}.png`), path.join(out, ref.raster.path));
    }
    fs.writeFileSync(manifestPath + '.tmp', JSON.stringify({ schema: 'compose-preview-references/v1', references: [...retained, ...references] }, null, 2) + '\n');
    fs.renameSync(manifestPath + '.tmp', manifestPath);
    return references;
  } finally { fs.rmSync(temp, { recursive: true, force: true }); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    const args = process.argv.slice(2);
    const options = Object.fromEntries(args.reduce((pairs, arg, i) => i % 2 ? pairs : [...pairs, [arg, args[i + 1]]], []));
    for (const key of ['--plan', '--out', '--revision', '--renderer', '--catalog'])
      if (!options[key]) throw new Error(`Required: ${key}`);
    if (!/^[a-f0-9]{40}$/.test(options['--revision'])) throw new Error('A full source commit is required');
    const root = path.resolve(options['--root'] ?? '.');
    const config = JSON.parse(fs.readFileSync(path.resolve(root, options['--plan']), 'utf8'));
    for (const file of new Set(config.captures.map(entry => entry.file))) {
      const committed = execFileSync('git', ['show', `${options['--revision']}:${file}`], { cwd: root });
      if (!committed.equals(fs.readFileSync(path.resolve(root, file))))
        throw new Error(`UID differs from the selected source commit: ${file}`);
    }
    const refs = publish({ config, root, out: path.resolve(options['--out']), revision: options['--revision'], renderer: path.resolve(options['--renderer']), catalog: path.resolve(options['--catalog']), components: options['--components'] });
    console.log(`Published ${refs.length} UID references`);
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
