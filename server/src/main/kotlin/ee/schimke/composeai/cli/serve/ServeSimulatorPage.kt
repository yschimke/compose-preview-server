package ee.schimke.composeai.cli.serve

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A whole document around [body]: the simulator's pages carry no site chrome, so they embed. */
internal fun simulatorPlainPage(title: String, body: String): String =
  """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${html(title)}</title>
<style>$SIMULATOR_CSS</style>
</head><body>$body</body></html>"""

/**
 * The viewer, for this server's page and for an embed alike. Its config names the app, whether it
 * is an embed, and the origins its parent may be ([allowedParents]): the only windows it talks to
 * with `postMessage`. Everything the app is, it learns from its start request, which is authorized.
 */
internal fun simulatorViewerBody(
  appId: String,
  embed: Boolean,
  allowedParents: List<String>,
  label: String? = null,
  modes: List<String> = emptyList(),
): String {
  val config = buildJsonObject {
    put("appId", appId)
    put("embed", embed)
    put("parents", JsonArray(allowedParents.map(::JsonPrimitive)))
    label?.let { put("label", it) }
    put("modes", JsonArray(modes.map(::JsonPrimitive)))
    put("api", SIMULATOR_API)
    put("adminHeader", ServeHttpServer.ADMIN_TOKEN_HEADER)
  }
    .toString()
  // Inside <script>: never let a value close the element.
  .replace("<", "\\u003c")
  return """
<main class="sim ${if (embed) "embed" else "page"}">
  <header class="bar">
    <strong id="sim-title">${html(label ?: appId)}</strong>
    <span id="sim-mode-wrap" hidden><select id="sim-mode" aria-label="Device"></select></span>
    <button id="sim-start" type="button">Run</button>
    <button id="sim-stop" type="button" hidden>End</button>
    <span id="sim-remaining" class="muted"></span>
  </header>
  <section id="sim-stage" class="stage" hidden>
    <div class="label" id="sim-stage-label">Starting</div>
    <progress id="sim-progress" max="4" value="0"></progress>
    <div class="muted" id="sim-stage-elapsed"></div>
  </section>
  <section id="sim-devices" class="devices" aria-live="polite"></section>
  <p id="sim-message" class="message" role="status" hidden></p>
  <details id="sim-log-wrap" class="log"${if (embed) " hidden" else ""}><summary>Events</summary><ol id="sim-log"></ol></details>
</main>
<script type="application/json" id="sim-config">$config</script>
<script>$SIMULATOR_JS</script>"""
}

private fun html(text: String): String =
  text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private const val SIMULATOR_CSS =
  """
:root{color-scheme:light dark;--bg:#f6f7f9;--fg:#1b1d21;--muted:#646a73;--panel:#fff;--edge:#d5d9e0;--accent:#3567e8}
@media (prefers-color-scheme:dark){:root{--bg:#121417;--fg:#e8eaee;--muted:#9aa1ab;--panel:#1c1f24;--edge:#30353d;--accent:#7c9cff}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.4 system-ui,sans-serif}
.sim{max-width:1100px;margin:0 auto;padding:12px 16px}.sim.embed{padding:8px}
.bar{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:12px}
.bar strong{font-size:15px;margin-right:auto}
button,select{font:inherit;border:1px solid var(--edge);background:var(--panel);color:var(--fg);border-radius:8px;padding:6px 12px;cursor:pointer}
button:hover{border-color:var(--accent)}#sim-start{background:var(--accent);border-color:var(--accent);color:#fff}
.muted{color:var(--muted);font-size:12px}
.stage{background:var(--panel);border:1px solid var(--edge);border-radius:10px;padding:12px;margin-bottom:12px}
.stage progress{width:100%;height:8px}
.devices{display:flex;gap:24px;flex-wrap:wrap;justify-content:center;align-items:flex-start}
.device{display:flex;flex-direction:column;align-items:center;gap:8px;outline:none}
.screen{position:relative;background:#000;border:10px solid #23262b;border-radius:28px;overflow:hidden;touch-action:none;user-select:none}
.device.watch .screen{border-radius:50%;border-width:14px}
.device:focus-visible .screen{box-shadow:0 0 0 3px var(--accent)}
.screen img{display:block;width:100%;height:100%;-webkit-user-drag:none;pointer-events:none}
.screen .wait{position:absolute;inset:0;display:grid;place-items:center;color:#aaa;font-size:12px}
.screen .wait[hidden],[hidden]{display:none}
.keys{display:flex;gap:6px;flex-wrap:wrap;justify-content:center}.keys button{padding:4px 10px}
.name{font-size:12px;color:var(--muted);text-transform:capitalize}
.message{background:var(--panel);border:1px solid var(--edge);border-radius:10px;padding:10px 12px}
.devices.asleep .screen img{filter:grayscale(.7) brightness(.6)}
.devices.asleep::after{content:attr(data-label);position:absolute;left:50%;transform:translateX(-50%);margin-top:-28px;background:var(--panel);border:1px solid var(--edge);border-radius:999px;padding:6px 14px;font-size:13px}
.devices{position:relative}
.log ol{max-height:220px;overflow:auto;font:12px ui-monospace,monospace;padding-left:20px}
"""

/**
 * The viewer's script. Vanilla and inline: the embed's CSP allows this origin's script only, and
 * nothing here needs a bundle.
 */
private const val SIMULATOR_JS =
  """
(() => {
  const cfg = JSON.parse(document.getElementById('sim-config').textContent);
  const ${'$'} = (id) => document.getElementById(id);
  const params = new URLSearchParams(location.search);
  // ?token= is the admin token on this server's page and an embed token in an embed. Either way it
  // leaves the address bar at once, so it is not in history, a bookmark or a screenshot.
  const token = params.get('token');
  if (params.has('token')) {
    params.delete('token');
    history.replaceState(null, '', location.pathname + (params.toString() ? '?' + params : ''));
  }
  const adminToken = cfg.embed ? null : token;
  const embedToken = cfg.embed ? token : null;
  const parentOrigin = (() => {
    if (!cfg.embed || window.parent === window) return null;
    let o = null;
    try { o = (location.ancestorOrigins && location.ancestorOrigins[0]) || null; } catch (_) {}
    if (!o && document.referrer) { try { o = new URL(document.referrer).origin; } catch (_) {} }
    return o && cfg.parents.includes(o) ? o : null;
  })();
  const toParent = (type, body) => {
    if (parentOrigin) window.parent.postMessage(Object.assign({ source: 'compose-preview-simulator', type }, body || {}), parentOrigin);
  };

  let ws = null, session = null, nextId = 1, lane = null, asleep = false;
  const pending = new Map();
  const screens = new Map();

  const headers = () => {
    const h = { 'Content-Type': 'application/json' };
    if (adminToken) h[cfg.adminHeader] = adminToken;
    return h;
  };
  const say = (text) => { const m = ${'$'}('sim-message'); m.textContent = text || ''; m.hidden = !text; };
  const log = (text) => {
    const li = document.createElement('li'); li.textContent = text;
    const ol = ${'$'}('sim-log'); ol.appendChild(li); while (ol.children.length > 80) ol.firstChild.remove();
  };

  function setModes(modes) {
    const sel = ${'$'}('sim-mode'); sel.innerHTML = '';
    const names = { phone: 'Phone', watch: 'Watch', paired: 'Phone + watch (paired)' };
    modes.forEach((m) => { const o = document.createElement('option'); o.value = m; o.textContent = names[m] || m; sel.appendChild(o); });
    const wanted = params.get('mode'); if (wanted && modes.includes(wanted)) sel.value = wanted;
    ${'$'}('sim-mode-wrap').hidden = modes.length < 2;
  }

  async function discover() {
    if (cfg.modes.length) { setModes(cfg.modes); return; }
    try {
      const r = await fetch(cfg.api + '/apps', { headers: headers(), credentials: 'same-origin' });
      const apps = r.ok ? await r.json() : [];
      const app = apps.find((a) => a.appId === cfg.appId);
      if (app) { ${'$'}('sim-title').textContent = app.label; document.title = app.label; setModes(app.modes); }
    } catch (_) {}
  }

  async function start() {
    say(''); ${'$'}('sim-start').disabled = true;
    const mode = ${'$'}('sim-mode').value || params.get('mode') || undefined;
    let r;
    try {
      r = await fetch(cfg.api + '/apps/' + encodeURIComponent(cfg.appId) + '/sessions', {
        method: 'POST', headers: headers(), credentials: 'same-origin',
        body: JSON.stringify({ mode, embedToken, embed: cfg.embed }),
      });
    } catch (e) { ${'$'}('sim-start').disabled = false; say('Could not reach the server.'); return; }
    const body = await r.json().catch(() => ({}));
    if (!r.ok) {
      ${'$'}('sim-start').disabled = false;
      say(body.reason || ('The simulator could not start (' + r.status + ').'));
      toParent('error', { code: body.code, reason: body.reason });
      return;
    }
    session = body;
    ${'$'}('sim-start').hidden = true; ${'$'}('sim-stop').hidden = false; ${'$'}('sim-mode-wrap').hidden = true;
    ${'$'}('sim-stage').hidden = false;
    connect();
  }

  function connect() {
    const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(scheme + '//' + location.host + session.socketPath + '?secret=' + encodeURIComponent(session.secret));
    ws.onmessage = (e) => { let m; try { m = JSON.parse(e.data); } catch (_) { return; } onMessage(m); };
    ws.onclose = () => { if (session) ended({ stage: 'closed' }); };
  }

  function send(obj) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); }

  function command(cmd, args, device) {
    const id = nextId++;
    send(Object.assign({ type: 'command', id, cmd }, device ? { device } : {}, args || {}));
    return new Promise((resolve) => pending.set(id, resolve));
  }

  function fire(cmd, args, device) { send(Object.assign({ type: 'command', cmd }, device ? { device } : {}, args || {})); }

  function onMessage(m) {
    switch (m.type) {
      case 'stage': {
        const waiting = ['queued', 'starting', 'converting', 'booting'].includes(m.stage);
        ${'$'}('sim-stage').hidden = !waiting;
        ${'$'}('sim-devices').classList.toggle('asleep', m.stage === 'paused' || m.stage === 'hibernated');
        ${'$'}('sim-devices').dataset.label = m.label;
        asleep = m.stage === 'paused' || m.stage === 'hibernated';
        ${'$'}('sim-stage-label').textContent = m.label;
        ${'$'}('sim-progress').value = m.step; ${'$'}('sim-progress').max = m.steps;
        ${'$'}('sim-stage-elapsed').textContent = Math.round(m.elapsedMillis / 1000) + ' s';
        if (m.stage === 'failed' && m.diagnostics) log(m.diagnostics.join('\n'));
        toParent('stage', { stage: m.stage, label: m.label, step: m.step, steps: m.steps });
        break;
      }
      case 'lane': lane = m; ${'$'}('sim-stage').hidden = true; buildDevices(m); countdown(m.remainingMillis); toParent('lane', { mode: m.mode, devices: m.devices, commands: m.commands }); break;
      case 'frame': {
        const s = screens.get(m.device); if (!s) break;
        s.img.src = 'data:image/' + m.codec + ';base64,' + m.dataBase64;
        s.w = m.widthPx; s.h = m.heightPx; s.wait.hidden = true; size(s);
        break;
      }
      case 'event': {
        const e = m.event || {};
        log('[' + m.device + '] ' + (e.event || '?') + ' ' + JSON.stringify(Object.fromEntries(Object.entries(e).filter(([k]) => k !== 'event'))));
        toParent('event', { device: m.device, event: e });
        break;
      }
      case 'result': {
        if (m.id != null && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
        else if (!m.ok) log('error: ' + m.error);
        break;
      }
      case 'error': log('error: ' + (m.message || '')); break;
      case 'ended': ended(m); break;
    }
  }

  function size(s) {
    if (!s.w || !s.h) return;
    const maxH = Math.min(window.innerHeight - 160, s.device === 'watch' ? 360 : 720);
    const maxW = s.device === 'watch' ? 360 : Math.max(240, (window.innerWidth - 64) / (screens.size || 1));
    const scale = Math.min(maxH / s.h, maxW / s.w, 1);
    s.screen.style.width = Math.round(s.w * scale) + 'px';
    s.screen.style.height = Math.round(s.h * scale) + 'px';
  }
  window.addEventListener('resize', () => screens.forEach(size));

  function key(device, name) { return command('key', { key: name }, device); }

  function buildDevices(m) {
    const root = ${'$'}('sim-devices'); root.innerHTML = ''; screens.clear();
    const allowed = new Set(m.commands);
    for (const d of m.devices) {
      const device = d.device;
      const wrap = document.createElement('div'); wrap.className = 'device ' + device; wrap.tabIndex = 0;
      const screen = document.createElement('div'); screen.className = 'screen';
      const img = document.createElement('img'); img.alt = device + ' screen';
      const wait = document.createElement('div'); wait.className = 'wait'; wait.textContent = 'Waiting for a frame…';
      screen.append(img, wait);
      const keys = document.createElement('div'); keys.className = 'keys';
      const button = (text, title, fn) => { const b = document.createElement('button'); b.type = 'button'; b.textContent = text; b.title = title; b.onclick = () => { fn(); wrap.focus(); }; keys.appendChild(b); };
      button('◁', 'Back', () => command('back', {}, device));
      if (m.launcher) button('○', 'Home', () => command('home', {}, device));
      if (device === 'phone' && allowed.has('rotate')) button('⟳', 'Rotate', () => command('rotate', {}, device));
      if (device === 'watch') {
        button('▲', 'Crown up', () => command('rotary', { delta: 1, count: 3 }, device));
        button('▼', 'Crown down', () => command('rotary', { delta: -1, count: 3 }, device));
        button('●', 'Side button', () => key(device, 'STEM_PRIMARY'));
      }
      const name = document.createElement('div'); name.className = 'name'; name.textContent = device;
      wrap.append(screen, keys, name); root.appendChild(wrap);
      const s = { device, wrap, screen, img, wait, w: d.widthPx, h: d.heightPx };
      screens.set(device, s); size(s);
      wireInput(s);
    }
    const first = screens.values().next().value; if (first) first.wrap.focus();
  }

  function wireInput(s) {
    const point = (e) => {
      const r = s.screen.getBoundingClientRect();
      const bw = s.screen.clientLeft, bh = s.screen.clientTop;
      const x = (e.clientX - r.left - bw) / s.screen.clientWidth * (s.w || 1);
      const y = (e.clientY - r.top - bh) / s.screen.clientHeight * (s.h || 1);
      return { x: Math.round(x * 10) / 10, y: Math.round(y * 10) / 10 };
    };
    let down = false, move = null, raf = 0;
    const flush = () => { raf = 0; if (move) { fire('touch', Object.assign({ action: 'move' }, move), s.device); move = null; } };
    s.screen.addEventListener('pointerdown', (e) => {
      e.preventDefault(); s.wrap.focus();
      // A paused or sleeping session: this touch wakes it, rather than landing on a stale frame.
      if (asleep) { send({ type: 'wake' }); return; }
      s.screen.setPointerCapture(e.pointerId); down = true;
      fire('touch', Object.assign({ action: 'down' }, point(e)), s.device);
    });
    s.screen.addEventListener('pointermove', (e) => {
      if (!down) return; move = point(e); if (!raf) raf = requestAnimationFrame(flush);
    });
    const up = (e) => {
      if (!down) return; down = false; if (raf) { cancelAnimationFrame(raf); raf = 0; } flush();
      fire('touch', Object.assign({ action: 'up' }, point(e)), s.device);
      setTimeout(() => send({ type: 'requestFrame' }), 120);
    };
    s.screen.addEventListener('pointerup', up);
    s.screen.addEventListener('pointercancel', up);
    let wheelAt = 0;
    s.screen.addEventListener('wheel', (e) => {
      e.preventDefault();
      const now = performance.now(); if (now - wheelAt < 60) return; wheelAt = now;
      const delta = e.deltaY > 0 ? -1 : 1;
      if (s.device === 'watch') fire('rotary', { delta, count: 1 }, s.device);
      else {
        const cx = (s.w || 0) / 2, cy = (s.h || 0) / 2, dy = (s.h || 0) * 0.25 * delta;
        fire('swipe', { x1: cx, y1: cy - dy, x2: cx, y2: cy + dy, durationMs: 150 }, s.device);
      }
    }, { passive: false });
    let typed = '', typedTimer = 0;
    const flushText = () => { typedTimer = 0; if (typed) { fire('text', { text: typed }, s.device); typed = ''; } };
    const KEYS = { Backspace: 'DEL', Delete: 'FORWARD_DEL', Enter: 'ENTER', Tab: 'TAB', ArrowUp: 'DPAD_UP', ArrowDown: 'DPAD_DOWN', ArrowLeft: 'DPAD_LEFT', ArrowRight: 'DPAD_RIGHT', Home: 'MOVE_HOME', End: 'MOVE_END', PageUp: 'PAGE_UP', PageDown: 'PAGE_DOWN' };
    s.wrap.addEventListener('keydown', (e) => {
      if (e.metaKey || e.ctrlKey || e.altKey) return;
      if (e.key === 'Escape') { e.preventDefault(); flushText(); fire('back', {}, s.device); return; }
      if (KEYS[e.key]) { e.preventDefault(); flushText(); fire('key', { key: KEYS[e.key] }, s.device); return; }
      if (e.key.length === 1) { e.preventDefault(); typed += e.key; if (!typedTimer) typedTimer = setTimeout(flushText, 60); }
    });
  }

  let timer = 0;
  function countdown(ms) {
    const until = Date.now() + ms; clearInterval(timer);
    const tick = () => {
      const left = Math.max(0, until - Date.now());
      const m = Math.floor(left / 60000), s = Math.floor(left / 1000) % 60;
      ${'$'}('sim-remaining').textContent = m + ':' + String(s).padStart(2, '0') + ' left';
    };
    tick(); timer = setInterval(tick, 1000);
  }

  function ended(m) {
    if (!session) return;
    const reason = m.reason || (m.stage === 'live' ? '' : 'The session ended.');
    session = null; clearInterval(timer); ${'$'}('sim-remaining').textContent = '';
    if (ws) { try { ws.close(); } catch (_) {} ws = null; }
    ${'$'}('sim-stop').hidden = true; ${'$'}('sim-start').hidden = false; ${'$'}('sim-start').disabled = false;
    ${'$'}('sim-start').textContent = 'Run again'; ${'$'}('sim-stage').hidden = true;
    setModes(Array.from(${'$'}('sim-mode').options).map((o) => o.value));
    say(reason || 'The session ended.');
    pending.forEach((resolve) => resolve({ ok: false, error: 'the session ended' })); pending.clear();
    toParent('ended', { stage: m.stage, code: m.code, reason });
  }

  async function stop() {
    if (!session) return;
    const s = session;
    await fetch(cfg.api + '/sessions/' + s.sessionId, { method: 'DELETE', headers: { 'X-Simulator-Session-Secret': s.secret } }).catch(() => {});
    ended({ stage: 'closed', reason: 'You ended the session.' });
  }

  document.addEventListener('visibilitychange', () => send({ type: 'visibility', visible: !document.hidden }));
  // Leaving the page ends the session at once rather than after the server's reconnect grace.
  window.addEventListener('pagehide', () => {
    if (session) fetch(cfg.api + '/sessions/' + session.sessionId, { method: 'DELETE', keepalive: true, headers: { 'X-Simulator-Session-Secret': session.secret } }).catch(() => {});
  });
  ${'$'}('sim-start').onclick = start;
  ${'$'}('sim-stop').onclick = stop;

  // The embedding page's API: start, stop and commands, from the parent window alone, and only
  // when it is one of the app's listed origins. The server applies the viewer's command policy.
  window.addEventListener('message', async (e) => {
    if (!parentOrigin || e.source !== window.parent || e.origin !== parentOrigin) return;
    const m = e.data || {};
    if (m.target !== 'compose-preview-simulator') return;
    if (m.type === 'start') { if (!session) { if (m.mode) ${'$'}('sim-mode').value = m.mode; start(); } return; }
    if (m.type === 'stop') { stop(); return; }
    if (m.type === 'command' && typeof m.cmd === 'string') {
      const args = Object.assign({}, m.args || {});
      const r = session ? await command(m.cmd, args, m.device) : { ok: false, error: 'no session is running' };
      toParent('result', { id: m.id, ok: r.ok, result: r.result, error: r.error });
    }
  });

  discover();
  toParent('ready', { appId: cfg.appId, modes: cfg.modes });
})();
"""
