// Evidence for #1241: the preview library MCP App in a minimal fake MCP Apps host.
// Usage: node docs/evidence/openai-sidebar-library/capture.mjs . docs/evidence/openai-sidebar-library
// The host and its data are fake; the render bytes are a stand-in screen, not a real Compose render.
import { chromium } from '/opt/node22/lib/node_modules/playwright/index.mjs';
import fs from 'node:fs';
import path from 'node:path';

const [repo, out] = process.argv.slice(2);
fs.mkdirSync(out, { recursive: true });
const html = fs.readFileSync(path.join(repo, 'mcp-app/preview-library.html'), 'utf8');
const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });

// Stand-in render bytes: a small Compose-like screen drawn once by the browser itself.
async function standInRender(title, dark) {
  const page = await browser.newPage({ viewport: { width: 360, height: 640 }, deviceScaleFactor: 1 });
  const bg = dark ? '#141218' : '#fef7ff', fg = dark ? '#e6e0e9' : '#1d1b20', card = dark ? '#2b2930' : '#f3edf7', primary = dark ? '#d0bcff' : '#6750a4';
  await page.setContent(`<body style="margin:0;background:${bg};color:${fg};font:16px Roboto,system-ui,sans-serif">
    <div style="height:64px;display:flex;align-items:center;padding:0 16px;font-size:22px">${title}</div>
    ${['Morning run · 5.2 km', 'Evening ride · 21 km', 'Swim · 1.5 km', 'Yoga · 40 min', 'Hike · 9.8 km'].map(t => `
      <div style="margin:8px 16px;padding:16px;border-radius:12px;background:${card};display:flex;gap:12px;align-items:center">
        <div style="width:40px;height:40px;border-radius:20px;background:${primary}"></div><div>${t}<div style="font-size:13px;opacity:.7">Today</div></div></div>`).join('')}
    <div style="position:absolute;right:16px;bottom:16px;width:56px;height:56px;border-radius:16px;background:${primary}"></div></body>`);
  const png = (await page.screenshot()).toString('base64');
  await page.close();
  return png;
}
const renders = { light: await standInRender('Activity', false), dark: await standInRender('Activity', true) };

const ws = 'wear-os-samples-3f2a';
const uri = (module, fqn) => `compose-preview://${ws}/${module.replace(/:/g, '_')}/${fqn}`;
const projects = [
  { id: ws, name: 'wear-os-samples', path: '/work/wear-os-samples', modules: [
    { path: ':app', previews: [
      { uri: uri(':app', 'com.example.ActivityListKt.ActivityListPreview'), name: 'ActivityListPreview', displayName: 'Activity list', sourceFile: 'ActivityList.kt', sourceLine: 42 },
      { uri: uri(':app', 'com.example.ActivityListKt.ActivityListPreview_Dark'), name: 'ActivityListPreview_Dark', displayName: 'Activity list – Dark', sourceFile: 'ActivityList.kt', sourceLine: 42 },
      { uri: uri(':app', 'com.example.ProfileKt.ProfilePreview'), name: 'ProfilePreview', displayName: 'Profile', sourceFile: 'Profile.kt', sourceLine: 18 },
      { uri: uri(':app', 'com.example.SettingsKt.SettingsPreview'), name: 'SettingsPreview', displayName: 'Settings', sourceFile: 'Settings.kt', sourceLine: 77 },
    ] },
    { path: ':wear', previews: [
      { uri: uri(':wear', 'com.example.wear.TileKt.TilePreview'), name: 'TilePreview', displayName: 'Tile', sourceFile: 'Tile.kt', sourceLine: 30 },
      { uri: uri(':wear', 'com.example.wear.ComplicationKt.ComplicationPreview'), name: 'ComplicationPreview', displayName: 'Complication', sourceFile: 'Complication.kt', sourceLine: 12 },
    ] },
  ] },
  { id: 'nowinandroid-81bc', name: 'nowinandroid', path: '/work/nowinandroid', modules: [] },
];

async function shoot(name, { theme = 'light', deepLink, mode = 'library', search, later } = {}) {
  const page = await browser.newPage({ viewport: { width: 1000, height: 640 }, deviceScaleFactor: 1 });
  const calls = [];
  await page.exposeFunction('__record', c => calls.push(c));
  await page.setContent('<body style="margin:0"><iframe id="app" style="border:0;width:100vw;height:100vh"></iframe></body>');
  await page.evaluate(({ html, projects, renders, theme, deepLink, mode }) => {
    const frame = document.getElementById('app');
    const send = m => frame.contentWindow.postMessage({ jsonrpc: '2.0', ...m }, '*');
    window.__send = send;
    const structured = mode === 'designs'
      ? { schema: 'compose-preview-library/v1', mode, host: 'hosted', tools: { listDesigns: 'ui_builder_list_designs', view: 'ui_builder_view' }, projects: [] }
      : { schema: 'compose-preview-library/v1', mode, host: 'local',
      tools: { refresh: mode === 'register' ? 'settings_register_project' : 'previews_library', render: 'render_preview', register: 'register_project' },
      renderArgs: { inline: true, observe: 'png' }, projects };
    window.addEventListener('message', e => {
      if (e.source !== frame.contentWindow) return;
      const { id, method, params } = e.data || {};
      if (!method) return;
      window.__record({ method, params });
      const reply = result => send({ id, result });
      if (method === 'ui/initialize') {
        const hostContext = { theme, displayMode: 'fullscreen' };
        if (deepLink) hostContext['openai/deepLink'] = { url: deepLink };
        reply({ protocolVersion: '2026-01-26', hostInfo: { name: 'fake-host', version: '1' }, hostCapabilities: { serverTools: {} }, hostContext });
        send({ method: 'ui/notifications/tool-input', params: { arguments: {} } });
        send({ method: 'ui/notifications/tool-result', params: { content: [{ type: 'text', text: '2 project(s)' }], structuredContent: structured } });
      } else if (method === 'tools/call' && params.name === 'ui_builder_list_designs') {
        reply({ content: [{ type: 'text', text: JSON.stringify({ designs: [
          { designId: 'd-41', name: 'Onboarding' }, { designId: 'd-42', name: 'Activity feed' }, { designId: 'd-43', name: 'Settings sheet' } ] }) }] });
      } else if (method === 'tools/call' && params.name === 'ui_builder_view') {
        reply({ content: [{ type: 'text', text: '{"revision":3}' }, { type: 'image', mimeType: 'image/png', data: renders.light }] });
      } else if (method === 'tools/call' && params.name === 'render_preview') {
        setTimeout(() => reply({ content: [{ type: 'image', mimeType: 'image/png', data: params.arguments.uri.includes('Dark') || theme === 'dark' ? renders.dark : renders.light }] }), 50);
      } else if (method === 'tools/call') {
        reply({ content: [{ type: 'text', text: '{}' }], structuredContent: structured });
      } else if (id != null) reply({});
    });
    frame.srcdoc = html;
  }, { html, projects, renders, theme, deepLink, mode });
  await page.waitForTimeout(600);
  if (search) {
    await page.frameLocator('#app').locator('#search').fill(search);
    await page.waitForTimeout(200);
  }
  if (later) await later(page);
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(out, name) });
  await page.close();
  return calls;
}

const target = projects[0].modules[0].previews[0].uri;
const calls = await shoot('library-deeplink.light.png', { deepLink: '/preview/' + encodeURIComponent(target) });
const renderCall = calls.find(c => c.method === 'tools/call');
console.log('deep link render call:', JSON.stringify(renderCall.params));
if (renderCall.params.arguments.uri !== target) throw new Error('deep link did not select the preview');

await shoot('library-search.dark.png', { theme: 'dark', search: 'dark', later: async page => {
  await page.frameLocator('#app').locator('.preview.row').first().click();
} });

// A deep link that arrives after opening, through host-context-changed.
const changed = await shoot('library-project-link.light.png', { later: async page => {
  await page.evaluate(() => window.__send({ method: 'ui/notifications/host-context-changed', params: { 'openai/deepLink': { url: '/project/nowinandroid-81bc' } } }));
} });
const refresh = changed.find(c => c.method === 'tools/call' && c.params.name === 'previews_library');
console.log('project deep link refresh:', JSON.stringify(refresh?.params));
if (refresh?.params.arguments.projectId !== 'nowinandroid-81bc') throw new Error('project deep link did not refresh the project');

await shoot('register-modal.light.png', { mode: 'register' });
const design = await shoot('ui-builder-design-link.light.png', { mode: 'designs', deepLink: '/design/d-42' });
const view = design.find(c => c.method === 'tools/call' && c.params.name === 'ui_builder_view');
console.log('design deep link view:', JSON.stringify(view?.params));
if (view?.params.arguments.designId !== 'd-42') throw new Error('design deep link did not open the design');
await browser.close();
console.log('ok');
