// A fake MCP Apps host for `mcp-app/rc-viewer.html` (ui://compose-preview/rc-viewer, #1237).
//
// It does what a host does with the server's resource: takes the HTML, inlines the vendored
// player bundle where the server would (`/*RC_PLAYER_BUNDLE*/`), and loads it as the iframe's
// document. Then it drives the bridge in one of three modes, from `body[data-mode]`:
//
// - `file`: an OpenAI file entrypoint. tool-input is FileInput with an opaque host URI; the app
//   reads it with `resources/read` (asking for the blob representation) and subscribes to it.
// - `path`: a model `rc_open {path}` call. tool-input echoes the path (the app must ignore it);
//   tool-result carries the bytes in `_meta["compose-preview/rc"]` and a server document URI.
// - `error`: `rc_open` failed; tool-result is the server's structured error.
//
// State for assertions lives on `window.__rcHost`.
const frame = document.querySelector("iframe");
const body = document.body;
const mode = body.dataset.mode;
const fixture = body.dataset.fixture;
const name = body.dataset.name;
const host = {
  reads: [],
  subscriptions: [],
  modelContext: null,
  initialized: false,
  bytes: null,
  // True once the viewer reports `ready` or `error`, so a capture shoots a settled frame.
  settled: false,
  // Replace the document on "disk" with the one at [url]; the next read returns it.
  async swap(url) {
    host.bytes = new Uint8Array(await (await fetch(url)).arrayBuffer());
  },
  // What a host sends when the subscribed file changes.
  fireUpdate(uri) {
    send({ jsonrpc: "2.0", method: "notifications/resources/updated", params: { uri } });
  },
  hostContextChanged(params) {
    send({ jsonrpc: "2.0", method: "ui/notifications/host-context-changed", params });
  },
};
window.__rcHost = host;

const HOST_URI = "host-resource://fixture-rc";
const SERVER_URI = "compose-preview-rc://document/0123456789abcdef/" + name;

function send(message) {
  frame.contentWindow.postMessage(message, "*");
}

function toBase64(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

const hostTheme = () =>
  window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";

window.addEventListener("message", (event) => {
  if (event.source !== frame.contentWindow) return;
  const message = event.data;
  if (!message || message.jsonrpc !== "2.0" || !message.method) return;
  const reply = (result) => send({ jsonrpc: "2.0", id: message.id, result });
  switch (message.method) {
    case "ui/initialize":
      reply({
        protocolVersion: "2026-01-26",
        hostInfo: { name: "fake-host", version: "1" },
        hostCapabilities: {
          experimental: { "openai/resource": {}, "openai/modelContext": {} },
          serverResources: { subscribe: true },
          updateModelContext: { text: {}, image: {}, structuredContent: {} },
        },
        hostContext: { theme: hostTheme() },
      });
      return;
    case "ui/notifications/initialized":
      host.initialized = true;
      start();
      return;
    case "resources/read":
      host.reads.push(message.params);
      reply({
        contents: [
          {
            uri: message.params.uri,
            mimeType: "application/vnd.remote-compose",
            blob: toBase64(host.bytes),
          },
        ],
      });
      return;
    case "resources/subscribe":
      host.subscriptions.push(message.params.uri);
      reply({});
      return;
    case "resources/unsubscribe":
      reply({});
      return;
    case "ui/update-model-context":
      host.modelContext = message.params;
      reply({ _meta: { "openai/modelContext": { updateId: "update-1" } } });
      return;
    case "ui/notifications/size-changed":
      return;
    default:
      if (message.id != null) {
        send({ jsonrpc: "2.0", id: message.id, error: { code: -32601, message: "not found" } });
      }
  }
});

function start() {
  if (mode === "file") {
    send({
      jsonrpc: "2.0",
      method: "ui/notifications/tool-input",
      params: { arguments: { file: { name, resourceUri: HOST_URI } } },
    });
    return;
  }
  if (mode === "path") {
    send({
      jsonrpc: "2.0",
      method: "ui/notifications/tool-input",
      params: { arguments: { path: "/home/someone/secret/project/" + name } },
    });
    send({
      jsonrpc: "2.0",
      method: "ui/notifications/tool-result",
      params: {
        result: {
          content: [{ type: "text", text: `Opened ${name} in the Remote Compose viewer.` }],
          structuredContent: {
            schema: "compose-preview/rc-document/v1",
            source: "server",
            name,
            resourceUri: SERVER_URI,
            sizeBytes: host.bytes.length,
          },
          _meta: { "compose-preview/rc": { documentBase64: toBase64(host.bytes) } },
        },
      },
    });
    return;
  }
  send({
    jsonrpc: "2.0",
    method: "ui/notifications/tool-result",
    params: {
      result: {
        isError: true,
        content: [{ type: "text", text: "rc_open: no such file: missing.rc" }],
        structuredContent: {
          error: { code: "not_found", message: "rc_open: no such file: missing.rc" },
        },
      },
    },
  });
}

const [html, bundle, bytes] = await Promise.all([
  fetch("/mcp-app/rc-viewer.html").then((r) => r.text()),
  fetch("/server/build/generated/rc-player-js/rc-player/bundle.js").then((r) => r.text()),
  fixture
    ? fetch(fixture).then((r) => r.arrayBuffer()).then((b) => new Uint8Array(b))
    : Promise.resolve(new Uint8Array()),
]);
host.bytes = bytes;
host.hostUri = HOST_URI;
host.serverUri = SERVER_URI;
frame.srcdoc = html.replace("/*RC_PLAYER_BUNDLE*/", () => bundle);

const watchSettled = () => {
  const state = frame.contentDocument?.documentElement?.dataset?.rcPlayerState;
  if (host.initialized && (state === "ready" || state === "error")) host.settled = true;
  else requestAnimationFrame(watchSettled);
};
requestAnimationFrame(watchSettled);
