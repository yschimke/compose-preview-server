package ee.schimke.composeai.cli.serve

/**
 * The real watch-face tab: one self-contained page (no bundle, no build step) at
 * `/ui-builder/{designId}/watch-face?session=…`, opened by the editor in a new window after it
 * `POST`s `…/dwf-preview` with its WFF XML. It shows the runtime's frames and the controls that
 * reach the daemon, and when this host has no runtime it says so before offering anything.
 *
 * It is served without authentication, like the editor's shell: it carries no design data, and
 * every call it makes (status, start, socket) is separately authorized.
 */
internal object UiBuilderDwfPage {

  fun html(designId: String): String = TEMPLATE.replace("__DESIGN_ID__", escape(designId))

  private fun escape(s: String): String = buildString {
    for (c in s) {
      when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&#39;")
        else -> append(c)
      }
    }
  }

  private val TEMPLATE =
    """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Watch face on Android</title>
<style>
  :root { --bg:#fff; --fg:#1b1b1f; --muted:#5f6368; --card:#f3f3f6; --line:#d6d6dc; --warn-bg:#fff4e5; --warn-fg:#663c00; --accent:#1a73e8; }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#121214; --fg:#e8e8ec; --muted:#a0a3a8; --card:#1d1d21; --line:#35353b; --warn-bg:#3a2a10; --warn-fg:#ffd9a0; --accent:#8ab4f8; }
  }
  * { box-sizing: border-box; }
  body { margin:0; font:14px/1.45 system-ui, sans-serif; background:var(--bg); color:var(--fg); }
  main { display:grid; grid-template-columns: minmax(240px, 420px) 1fr; gap:24px; padding:16px; max-width:1100px; margin:0 auto; }
  @media (max-width: 720px) { main { grid-template-columns: 1fr; } }
  h1 { font-size:16px; margin:0 0 4px; }
  .sub { color:var(--muted); margin:0 0 12px; }
  #stage { background:#000; border-radius:50%; aspect-ratio:1; width:100%; max-width:420px; display:grid; place-items:center; overflow:hidden; }
  #stage img { width:100%; height:100%; object-fit:contain; }
  #banner { display:none; background:var(--warn-bg); color:var(--warn-fg); border-radius:8px; padding:12px 14px; margin-bottom:12px; }
  #banner strong { display:block; margin-bottom:4px; }
  fieldset { border:1px solid var(--line); border-radius:8px; margin:0 0 12px; padding:8px 12px 12px; background:var(--card); }
  legend { padding:0 6px; color:var(--muted); }
  label { display:flex; gap:8px; align-items:center; margin:6px 0; }
  label > span { min-width:92px; color:var(--muted); }
  input[type=number], input[type=text], input[type=datetime-local], select { flex:1; min-width:0; padding:4px 6px; background:var(--bg); color:var(--fg); border:1px solid var(--line); border-radius:4px; font:inherit; }
  button { padding:4px 10px; font:inherit; background:var(--bg); color:var(--fg); border:1px solid var(--line); border-radius:4px; cursor:pointer; }
  #status { color:var(--muted); min-height:1.4em; }
  #status.error { color:#d93025; }
  .row { display:flex; gap:8px; align-items:center; }
</style>
</head>
<body>
<main data-design="__DESIGN_ID__" id="root">
  <section>
    <h1>Watch face on Android</h1>
    <p class="sub">Drawn by the Wear OS watch-face runtime. The editor's own preview is the fast one; this is the real one.</p>
    <div id="stage" aria-label="Watch face"><img id="frame" alt="" hidden></div>
    <p id="status" role="status">Starting…</p>
  </section>
  <section>
    <div id="banner" role="alert"><strong id="banner-title"></strong><span id="banner-body"></span></div>
    <div id="controls" hidden>
      <fieldset><legend>Time</legend>
        <label><span>Clock</span><input id="time" type="datetime-local" step="1"></label>
        <div class="row"><button id="now" type="button">Now</button>
        <label><input id="ticking" type="checkbox"> Tick in real time</label></div>
      </fieldset>
      <fieldset><legend>Display</legend>
        <label><input id="ambient" type="checkbox"> Ambient mode</label>
        <label><input id="burnIn" type="checkbox"> Burn-in protection</label>
        <label><input id="lowBit" type="checkbox"> Low-bit ambient</label>
      </fieldset>
      <fieldset><legend>Sensors</legend>
        <label><span>Heart rate</span><input id="heartRate" type="number" min="0" max="300" placeholder="bpm"></label>
        <label><span>Steps</span><input id="steps" type="number" min="0" placeholder="count"></label>
        <label><span>Battery</span><input id="battery" type="number" min="0" max="100" placeholder="%"></label>
      </fieldset>
      <fieldset><legend>Configuration</legend>
        <label><span>Flavor</span><input id="flavor" type="text" placeholder="flavor id"></label>
        <label><span>Option</span><input id="cfgKey" type="text" placeholder="option id"><input id="cfgVal" type="text" placeholder="value"><button id="cfgSet" type="button">Set</button></label>
      </fieldset>
      <fieldset><legend>Complication</legend>
        <label><span>Slot</span><input id="slot" type="number" min="0" max="15" value="0"></label>
        <label><span>Type</span><select id="ctype"><option>SHORT_TEXT</option><option>LONG_TEXT</option><option>RANGED_VALUE</option><option>GOAL_PROGRESS</option><option>EMPTY</option></select></label>
        <label><span>Text</span><input id="ctext" type="text"></label>
        <label><span>Title</span><input id="ctitle" type="text"></label>
        <label><span>Value</span><input id="cvalue" type="number" step="any"><input id="cmin" type="number" step="any" placeholder="min"><input id="cmax" type="number" step="any" placeholder="max"></label>
        <div class="row"><button id="cset" type="button">Set slot</button><button id="cclear" type="button">Clear slot</button></div>
      </fieldset>
    </div>
  </section>
</main>
<script>
(function () {
  var root = document.getElementById('root');
  var design = root.dataset.design;
  var params = new URLSearchParams(location.search);
  var statusEl = document.getElementById('status');
  var base = '/api/ui-builder/v1/designs/' + encodeURIComponent(design) + '/dwf-preview';
  var tokenHeaders = params.get('token') ? { 'X-Compose-Preview-Token': params.get('token') } : {};
  var socket = null;

  function say(text, isError) { statusEl.textContent = text; statusEl.className = isError ? 'error' : ''; }
  function banner(title, body) {
    document.getElementById('banner-title').textContent = title;
    document.getElementById('banner-body').textContent = body;
    document.getElementById('banner').style.display = 'block';
  }

  function api(path, init) {
    init = init || {};
    init.credentials = 'same-origin';
    init.headers = Object.assign({}, tokenHeaders, init.headers || {});
    return fetch(base + path, init);
  }

  function reasons(body) {
    try { var j = JSON.parse(body); return (j.reasons || [j.reason]).join(' '); } catch (e) { return body; }
  }

  function connect(sessionId) {
    var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    var q = params.get('token') ? '?token=' + encodeURIComponent(params.get('token')) : '';
    socket = new WebSocket(proto + '//' + location.host + base + '/' + encodeURIComponent(sessionId) + '/ws' + q);
    socket.onopen = function () { say('Connected. Waiting for the first frame…'); };
    socket.onclose = function () { say('Disconnected. Reload to resume this session.', true); };
    socket.onmessage = function (ev) {
      var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
      if (m.type === 'frame') {
        var img = document.getElementById('frame');
        img.src = 'data:image/' + (m.codec || 'png') + ';base64,' + m.dataBase64;
        img.hidden = false;
        say('Frame ' + m.seq);
      } else if (m.type === 'error') {
        say(m.message, true);
      }
    };
    document.getElementById('controls').hidden = false;
  }

  function send(controls) {
    if (socket && socket.readyState === 1) socket.send(JSON.stringify({ type: 'controls', controls: controls }));
  }
  function numOrNull(el) { return el.value === '' ? null : Number(el.value); }
  function on(id, ev, fn) { document.getElementById(id).addEventListener(ev, fn); }

  on('time', 'change', function (e) {
    var t = e.target.value ? new Date(e.target.value).getTime() : null;
    send({ timeMillis: t });
  });
  on('now', 'click', function () {
    var d = new Date(); d.setMilliseconds(0);
    document.getElementById('time').value = new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 19);
    send({ timeMillis: d.getTime() });
  });
  on('ticking', 'change', function (e) { send({ ticking: e.target.checked }); });
  on('ambient', 'change', function (e) { send({ ambient: e.target.checked }); });
  on('burnIn', 'change', function (e) { send({ burnInProtection: e.target.checked }); });
  on('lowBit', 'change', function (e) { send({ lowBitAmbient: e.target.checked }); });
  on('heartRate', 'change', function (e) { send({ heartRate: numOrNull(e.target) }); });
  on('steps', 'change', function (e) { send({ steps: numOrNull(e.target) }); });
  on('battery', 'change', function (e) { send({ batteryPercent: numOrNull(e.target) }); });
  on('flavor', 'change', function (e) { send({ flavor: e.target.value || null }); });
  on('cfgSet', 'click', function () {
    var k = document.getElementById('cfgKey').value, v = document.getElementById('cfgVal').value;
    if (!k) return;
    var o = {}; o[k] = v; cfg = Object.assign(cfg, o); send({ config: cfg });
  });
  var cfg = {};
  function slot() { return String(Number(document.getElementById('slot').value || 0)); }
  on('cset', 'click', function () {
    var c = { type: document.getElementById('ctype').value };
    [['ctext','text'],['ctitle','title']].forEach(function (p) { var v = document.getElementById(p[0]).value; if (v) c[p[1]] = v; });
    [['cvalue','value'],['cmin','min'],['cmax','max']].forEach(function (p) { var v = numOrNull(document.getElementById(p[0])); if (v !== null) c[p[1]] = v; });
    var o = {}; o[slot()] = c; send({ complications: o });
  });
  on('cclear', 'click', function () { var o = {}; o[slot()] = null; send({ complications: o }); });
  document.addEventListener('visibilitychange', function () {
    if (socket && socket.readyState === 1) socket.send(JSON.stringify({ type: 'visibility', visible: !document.hidden }));
  });

  api('/status').then(function (r) {
    if (!r.ok) throw new Error(r.status === 404 ? 'This design was not found, or you cannot open it.' : 'Status check failed (' + r.status + ').');
    return r.json();
  }).then(function (s) {
    if (!s.available) {
      banner('The real watch-face runtime is not available on this server', s.reason || 'No reason was given.');
      say('No frames: the runtime is not available here.', true);
      return null;
    }
    var existing = params.get('session');
    if (existing) return existing;
    say('Starting a session…');
    return api('', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' }).then(function (r) {
      return r.text().then(function (body) {
        if (r.status === 201) return JSON.parse(body).sessionId;
        banner('Could not start the real preview', reasons(body));
        say('No session.', true);
        return null;
      });
    });
  }).then(function (id) { if (id) connect(id); }).catch(function (e) { say(e.message, true); });
})();
</script>
</body>
</html>
"""
}
