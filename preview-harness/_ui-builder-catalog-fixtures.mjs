import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const fixtures = resolve(
    dirname(fileURLToPath(import.meta.url)),
    "../docs/design/fixtures/ui-builder",
);
let publicationDirectory;

// These tests measure the frozen Jetcaster benchmark, whose catalog includes historical adapters
// absent from today's delivery branch. Publish that exact capability data through the normal
// composer rather than depending on an implicit built-in benchmark or a moving GitHub catalog.
export function prepareCatalogFixtures() {
    return (publicationDirectory ??= (async () => {
        const directory = await mkdtemp(
            resolve(tmpdir(), "visual-harness-catalogs-"),
        );
        for (const system of ["m3-catalog", "remote-m3"]) {
            const catalog = JSON.parse(
                await readFile(
                    resolve(fixtures, `${system}-capabilities-v1.json`),
                    "utf8",
                ),
            );
            const builtins = Object.fromEntries(
                catalog.components.map((component) => [
                    component.componentId,
                    {
                        displayName: component.displayName,
                        shelfRole: component.role,
                        traits: component.traits,
                        properties: component.properties,
                        modifierCapabilities: component.modifierCapabilities,
                        wasm: component.wasm,
                        unrolled: component.wasm.unrolled,
                        code: component.code,
                        svg: component.svg,
                        slots: Object.fromEntries(
                            component.slots.map((slot) => {
                                if (slot.cardinality.min > 1)
                                    throw new Error(
                                        `unrepresentable benchmark slot: ${component.componentId}.${slot.name}`,
                                    );
                                return [
                                    slot.name,
                                    {
                                        required: slot.cardinality.min === 1,
                                        max: slot.cardinality.max,
                                        ordered: slot.ordered,
                                        acceptedRoles: slot.acceptedRoles,
                                        acceptedTraits: slot.acceptedTraits,
                                    },
                                ];
                            }),
                        ),
                    },
                ]),
            );
            await writeFile(
                resolve(directory, `${system}.json`),
                JSON.stringify({
                    schema: "compose-ui-builder-catalog/v1",
                    catalog: { id: system, title: system },
                    statusSemantics: {
                        ...catalog.statusSemantics,
                        componentIdPrefix:
                            system === "m3-catalog" ? "m3/" : "remote-m3/",
                        builtins,
                    },
                }),
            );
        }
        return directory;
    })());
}

// The visual document's nodes stay frozen; its catalog pin names the checked-in publication the
// server actually composed. Publication revisions are byte digests, not the old `candidate` pin.
export async function pinFixtureDocument(origin, token, document) {
    const response = await fetch(`${origin}/api/ui-builder/v1/requests`, {
        method: "POST",
        headers: {
            "content-type": "application/json",
            "X-Compose-Preview-Token": token,
        },
        body: JSON.stringify({
            schemaVersion: 1,
            requestId: "harness-catalog-pin",
            actorId: "operator",
            request: { type: "listCatalogs" },
        }),
    });
    if (!response.ok)
        throw new Error(
            `catalog fixtures unavailable: HTTP ${response.status}`,
        );
    const envelope = await response.json();
    const benchmark = envelope.response.catalogs.find(
        (catalog) =>
            catalog.benchmark.catalogSystemId === document.catalogPin.systemId,
    )?.benchmark;
    if (!benchmark)
        throw new Error(
            `catalog fixture missing: ${document.catalogPin.systemId}`,
        );
    return {
        ...document,
        catalogPin: {
            ...document.catalogPin,
            catalogRevision: benchmark.catalogRevision,
            nativeRuntimeId: benchmark.nativeRuntimeId,
        },
    };
}
