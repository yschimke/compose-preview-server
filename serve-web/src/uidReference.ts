// Embed the released editor using its existing IDE host bridge. The published UID is immutable;
// this tab owns a working copy, which can be downloaded into the repository for review.
export {};
const frame = document.getElementById("editor") as HTMLIFrameElement | null;
const status = document.getElementById("status");
const encoded = document.getElementById("uid-document")?.textContent;
if (frame && status && encoded) {
    let text = encoded;
    const original = JSON.parse(text);
    let capabilities = "";
    const say = (message: string) => {
        status.textContent = message;
    };
    window.addEventListener("message", (event) => {
        if (
            event.source !== frame.contentWindow ||
            event.origin !== location.origin
        )
            return;
        const message = event.data;
        if (message?.type === "compose-ui-builder/ready") {
            frame.contentWindow?.postMessage(
                {
                    type: "compose-ui-builder/open",
                    document: text,
                    capabilities,
                    label: "Reference working copy",
                },
                location.origin,
            );
            say(
                "Published reference loaded. Edits stay in this tab. Use Device view to check the adaptive layout.",
            );
        } else if (message?.type === "compose-ui-builder/changed") {
            if (typeof message.document === "string") {
                text = message.document;
                say(
                    "Edited locally · download the .uid to save it in your repository.",
                );
            }
        } else if (message?.type === "compose-ui-builder/error") {
            say(`Editor: ${message.message ?? "Unable to open this design"}`);
        }
    });
    document.getElementById("download")?.addEventListener("click", () => {
        const blob = URL.createObjectURL(
            new Blob([text], { type: "application/json" }),
        );
        const link = document.createElement("a");
        link.href = blob;
        link.download = `${original.id}.uid`;
        link.click();
        setTimeout(() => URL.revokeObjectURL(blob), 1000);
    });
    const read = async (url: string) => {
        const response = await fetch(url, { credentials: "same-origin" });
        if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);
        return response.text();
    };
    void (async () => {
        const system = original.catalogPin.systemId;
        if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,100}$/.test(system))
            throw new Error("Invalid catalog identity");
        const [html, catalog] = await Promise.all([
            read("/ui-builder/"),
            read(`/ui-builder/${system}-capabilities-v1.json`),
        ]);
        capabilities = catalog;
        // The host bridge must exist before the editor module starts. Relative archive URLs
        // continue to resolve under /ui-builder/, even though this page belongs to a catalog.
        const bridge = `<base href="${location.origin}/ui-builder/"><script>globalThis.composeUiBuilderHost={postMessage:m=>parent.postMessage(m,${JSON.stringify(location.origin)})};<\/script>`;
        frame.srcdoc = html.replace(
            /<head(?:\s[^>]*)?>/i,
            (match) => match + bridge,
        );
    })().catch((error) =>
        say(
            `UI Builder unavailable: ${error.message}. Download the .uid to open it in the desktop editor.`,
        ),
    );
}
