const frame = document.querySelector("iframe");
const mode = document.body.dataset.mode;
let reads = 0;

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
    send({ jsonrpc: "2.0", id: message.id, result: {} });
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
              uri: "compose-preview://fixture/_app/com.example.Card?overrides=fixture",
              name: "Compose Preview render",
              mimeType: "image/png",
            },
          ],
        },
      },
    });
    return;
  }
  if (message.method === "resources/read") {
    reads += 1;
    window.__mcpReadCount = reads;
    await new Promise((resolve) => setTimeout(resolve, 150));
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
      mode === "refresh" && reads > 1
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
