const frame = document.querySelector("iframe");
const mode = document.body.dataset.mode;
let reads = 0;
let resourceUpdates = 0;
const activeSubscriptions = new Set();

async function png(path) {
  const bytes = new Uint8Array(await (await fetch(path)).arrayBuffer());
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function send(message) {
  frame.contentWindow.postMessage(message, "*");
}

window.addEventListener("message", async (event) => {
  if (event.source !== frame.contentWindow) return;
  const message = event.data;
  if (!message || message.jsonrpc !== "2.0" || !message.method) return;
  if (message.method === "ui/initialize") {
    send({
      jsonrpc: "2.0",
      id: message.id,
      result: {
        hostCapabilities: {
          serverResources: {
            subscribe:
              mode === "refresh" ||
              mode === "same-uri-redraw" ||
              mode === "subscription-revisit" ||
              mode === "subscribe-late" ||
              mode === "read-replaced-inline" ||
              mode === "subscribe-fails" ||
              mode === "stale-read-marker",
          },
        },
      },
    });
    send({
      jsonrpc: "2.0",
      method: "ui/notifications/tool-input",
      params: {
        arguments: {
          uri: "compose-preview://fixture/_app/com.example.Card",
          previewId: "CardPreview",
          overrides: { uiMode: "dark" },
          sessionId: "must-not-travel",
          sourceUrl: "https://preview.invalid/render?cookie=also-must-not-travel",
          callbackUrl: "https://preview.invalid/callback#access_token=fragment-must-not-travel",
          redirects: [
            "https://preview.invalid/callback#access_token=array-must-not-travel",
          ],
        },
      },
    });
    send({
      jsonrpc: "2.0",
      method: "ui/notifications/tool-result",
      params: {
        result: {
          content: [
            {
              type: "resource_link",
              uri:
                mode === "refresh"
                  ? "compose-preview://fixture/_app/com.example.Card?overrides=stale"
                  : "compose-preview://fixture/_app/com.example.Card?overrides=fixture",
              name: "Compose Preview render",
              mimeType: "image/png",
            },
          ],
        },
      },
    });
    if (mode === "refresh") {
      window.setTimeout(() => {
        send({
          jsonrpc: "2.0",
          method: "ui/notifications/tool-result",
          params: {
            result: {
              content: [
                {
                  type: "resource_link",
                  uri: "compose-preview://fixture/_app/com.example.Card?overrides=fixture",
                  name: "Compose Preview render",
                  mimeType: "image/png",
                },
              ],
            },
          },
        });
      }, 100);
    }
    if (mode === "stale-read-marker") {
      window.setTimeout(async () => {
        send({
          jsonrpc: "2.0",
          method: "ui/notifications/tool-result",
          params: {
            result: {
              content: [
                {
                  type: "image",
                  mimeType: "image/png",
                  data: await png("/preview-harness/fixtures/pages/_render-placeholder.png"),
                },
                {
                  type: "resource_link",
                  uri: "compose-preview://fixture/_app/com.example.Card?overrides=other",
                  name: "Compose Preview render",
                  mimeType: "image/png",
                },
              ],
            },
          },
        });
      }, 50);
      window.setTimeout(async () => {
        send({
          jsonrpc: "2.0",
          method: "ui/notifications/tool-result",
          params: {
            result: {
              content: [
                {
                  type: "image",
                  mimeType: "image/png",
                  data: await png("/preview-harness/fixtures/pages/_render-placeholder.png"),
                },
                {
                  type: "resource_link",
                  uri: "compose-preview://fixture/_app/com.example.Card?overrides=fixture",
                  name: "Compose Preview render",
                  mimeType: "image/png",
                },
              ],
            },
          },
        });
      }, 100);
    }
    if (mode === "same-uri-redraw") {
      window.setTimeout(async () => {
        send({
          jsonrpc: "2.0",
          method: "ui/notifications/tool-result",
          params: {
            result: {
              content: [
                {
                  type: "image",
                  mimeType: "image/png",
                  data: await png("/preview-harness/fixtures/pages/_design-render-placeholder.png"),
                },
                {
                  type: "resource_link",
                  uri: "compose-preview://fixture/_app/com.example.Card?overrides=fixture",
                  name: "Compose Preview render",
                  mimeType: "image/png",
                },
              ],
            },
          },
        });
      }, 250);
    }
    if (mode === "subscription-revisit") {
      for (const [delay, overrides] of [[250, "other"], [350, "fixture"]]) {
        window.setTimeout(async () => {
          send({
            jsonrpc: "2.0",
            method: "ui/notifications/tool-result",
            params: {
              result: {
                content: [
                  {
                    type: "image",
                    mimeType: "image/png",
                    data: await png("/preview-harness/fixtures/pages/_design-render-placeholder.png"),
                  },
                  {
                    type: "resource_link",
                    uri: `compose-preview://fixture/_app/com.example.Card?overrides=${overrides}`,
                    name: "Compose Preview render",
                    mimeType: "image/png",
                  },
                ],
              },
            },
          });
        }, delay);
      }
    }
    if (mode === "read-replaced-inline") {
      window.setTimeout(async () => {
        send({
          jsonrpc: "2.0",
          method: "ui/notifications/tool-result",
          params: {
            result: {
              content: [
                {
                  type: "image",
                  mimeType: "image/png",
                  data: await png("/preview-harness/fixtures/pages/_design-render-placeholder.png"),
                },
                {
                  type: "resource_link",
                  uri: "compose-preview://fixture/_app/com.example.Card?overrides=inline",
                  name: "Compose Preview render",
                  mimeType: "image/png",
                },
              ],
            },
          },
        });
      }, 100);
    }
    return;
  }
  if (message.method === "resources/subscribe") {
    window.__mcpSubscribeCount = (window.__mcpSubscribeCount || 0) + 1;
    if (mode === "subscribe-fails" || mode === "stale-read-marker") {
      window.setTimeout(
        () =>
          send({
            jsonrpc: "2.0",
            id: message.id,
            error: { code: -32601, message: "subscriptions unavailable" },
          }),
        250,
      );
      return;
    }
    activeSubscriptions.add(message.params.uri);
    window.__mcpActiveSubscriptions = [...activeSubscriptions];
    if (mode === "subscribe-late") {
      window.setTimeout(
        () => send({ jsonrpc: "2.0", id: message.id, result: {} }),
        5250,
      );
      return;
    }
    const stale = message.params.uri.includes("overrides=stale");
    if (stale) {
      window.setTimeout(() => send({ jsonrpc: "2.0", id: message.id, result: {} }), 250);
    } else {
      send({ jsonrpc: "2.0", id: message.id, result: {} });
    }
    window.__mcpSubscribedUri = message.params.uri;
    if (stale || mode === "read-replaced-inline") return;
    window.setTimeout(() => {
      resourceUpdates += 1;
      send({
        jsonrpc: "2.0",
        method: "notifications/resources/updated",
        params: { uri: message.params.uri },
      });
    }, 300);
    return;
  }
  if (message.method === "resources/unsubscribe") {
    window.__mcpUnsubscribedUris = [
      ...(window.__mcpUnsubscribedUris || []),
      message.params.uri,
    ];
    const finish = () => {
      activeSubscriptions.delete(message.params.uri);
      window.__mcpActiveSubscriptions = [...activeSubscriptions];
      send({ jsonrpc: "2.0", id: message.id, result: {} });
    };
    if (mode === "subscription-revisit") window.setTimeout(finish, 400);
    else finish();
    return;
  }
  if (message.method === "resources/read") {
    reads += 1;
    window.__mcpReadCount = reads;
    await new Promise((resolve) =>
      setTimeout(
        resolve,
        mode === "slow-resource"
          ? 5250
          : mode === "stale-read-marker" || mode === "read-replaced-inline"
            ? 500
            : mode === "manual-poll" && reads === 2
              ? 1000
              : 150,
      ),
    );
    if (mode === "fallback") {
      send({
        jsonrpc: "2.0",
        id: message.id,
        result: {
          contents: [
            {
              uri: message.params.uri,
              mimeType: "text/plain",
              text: "No PNG is available",
            },
          ],
        },
      });
      return;
    }
    const path =
      mode === "refresh" && resourceUpdates > 0
        ? "/preview-harness/fixtures/pages/_design-render-placeholder.png"
        : "/preview-harness/fixtures/pages/_render-placeholder.png";
    send({
      jsonrpc: "2.0",
      id: message.id,
      result: {
        contents: [
          {
            uri: message.params.uri,
            mimeType: "image/png",
            blob: await png(path),
          },
        ],
      },
    });
    return;
  }
  if (message.method === "ui/update-model-context") {
    window.__mcpModelContext = message.params;
    send({ jsonrpc: "2.0", id: message.id, result: {} });
  }
});

frame.src = frame.dataset.src;
