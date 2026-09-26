const frame = document.querySelector("iframe");
const mode = document.body.dataset.mode;

function base64Url(value) {
  const bytes = new TextEncoder().encode(JSON.stringify(value));
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

const content = [];
if (mode === "image") {
  const bytes = new Uint8Array(
    await (await fetch("/preview-harness/fixtures/pages/_render-placeholder.png")).arrayBuffer(),
  );
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  content.push({ type: "image", mimeType: "image/png", data: btoa(binary) });
}
content.push({
  type: "text",
  text: JSON.stringify({
    observe: "semantics",
    uri: "compose-preview://fixture/_app/com.example.Card",
    sha256: "fixture-sha256",
    widthPx: 200,
    heightPx: 420,
    semantics: { root: { testTag: "static-card", role: "Card" } },
  }),
});
content.push({
  type: "resource_link",
  uri: "compose-preview://fixture/_app/com.example.Card?overrides=fixture",
  name: "Compose Preview render",
  mimeType: "image/png",
});

const envelope = {
  version: 1,
  arguments: {
    uri: "compose-preview://fixture/_app/com.example.Card",
    previewId: "CardPreview",
    overrides: { uiMode: "dark" },
  },
  result: { content },
};
frame.src = `${frame.dataset.src}#compose-preview-result=${base64Url(envelope)}`;
