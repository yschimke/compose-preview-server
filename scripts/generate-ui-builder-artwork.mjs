import { createHash } from "node:crypto";
import { deflateSync } from "node:zlib";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import process from "node:process";

const ROOT = resolve(import.meta.dirname, "..");
const SIZE = 512;
const FILE_ROOT = resolve(
    ROOT,
    "ui-builder-artwork/src/commonMain/composeResources",
);
const variants = [
    {
        assetKey: "jetcaster.cover.android-developers-backstage",
        id: "project-owned-android-audio-512-v1",
        fileStem: "jetcaster-owned-android-512",
        palette: [0x1259c3, 0x05a58d, 0x111b32],
        accent: [230, 246, 255],
    },
    {
        assetKey: "jetcaster.cover.google-developers-podcast",
        id: "project-owned-google-audio-512-v1",
        fileStem: "jetcaster-owned-google-512",
        palette: [0xd94b41, 0xf0ae2c, 0x264f9b],
        accent: [255, 246, 224],
    },
];

function sha256(bytes) {
    return createHash("sha256").update(bytes).digest("hex");
}

function color(value) {
    return [(value >> 16) & 255, (value >> 8) & 255, value & 255];
}

function blendChannel(bottom, top, alpha) {
    return Math.round((bottom * (255 - alpha) + top * alpha) / 255);
}

function renderArtwork(spec) {
    const rgba = Buffer.alloc(SIZE * SIZE * 4);
    const palette = spec.palette.map(color);
    for (let y = 0; y < SIZE; y++) {
        for (let x = 0; x < SIZE; x++) {
            const diagonal = x + y;
            const half = SIZE - 1;
            const segment = diagonal <= half ? 0 : 1;
            const local = segment === 0 ? diagonal : diagonal - half;
            const amount = Math.min(255, Math.round((local * 255) / half));
            const from = palette[segment];
            const to = palette[segment + 1];
            const pixel = from.map((channel, index) =>
                blendChannel(channel, to[index], amount),
            );

            const lightDx = x - 390;
            const lightDy = y - 118;
            if (lightDx * lightDx + lightDy * lightDy <= 170 * 170) {
                for (let channel = 0; channel < 3; channel++)
                    pixel[channel] = blendChannel(pixel[channel], 255, 34);
            }
            const darkDx = x - 112;
            const darkDy = y - 366;
            if (darkDx * darkDx + darkDy * darkDy <= 112 * 112) {
                for (let channel = 0; channel < 3; channel++)
                    pixel[channel] = blendChannel(pixel[channel], 0, 42);
            }

            // Project-owned audio mark: three equalizer bars within a ring.
            const centerDx = x - 256;
            const centerDy = y - 256;
            const radiusSquared = centerDx * centerDx + centerDy * centerDy;
            const inRing = radiusSquared >= 132 * 132 && radiusSquared <= 148 * 148;
            const bar =
                (x >= 154 && x < 194 && y >= 214 && y < 338) ||
                (x >= 236 && x < 276 && y >= 166 && y < 338) ||
                (x >= 318 && x < 358 && y >= 234 && y < 338);
            if (inRing || bar) {
                for (let channel = 0; channel < 3; channel++)
                    pixel[channel] = blendChannel(pixel[channel], spec.accent[channel], 205);
            }

            const offset = (y * SIZE + x) * 4;
            rgba[offset] = pixel[0];
            rgba[offset + 1] = pixel[1];
            rgba[offset + 2] = pixel[2];
            rgba[offset + 3] = 255;
        }
    }
    return rgba;
}

const crcTable = Array.from({ length: 256 }, (_, value) => {
    let crc = value;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
    return crc >>> 0;
});

function crc32(bytes) {
    let crc = 0xffffffff;
    for (const byte of bytes) crc = (crc >>> 8) ^ crcTable[(crc ^ byte) & 255];
    return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
    const name = Buffer.from(type, "ascii");
    const body = Buffer.concat([name, data]);
    const checksum = Buffer.alloc(4);
    checksum.writeUInt32BE(crc32(body));
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length);
    return Buffer.concat([length, body, checksum]);
}

function encodePng(rgba) {
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(SIZE, 0);
    ihdr.writeUInt32BE(SIZE, 4);
    ihdr[8] = 8;
    ihdr[9] = 6;
    const raw = Buffer.alloc((SIZE * 4 + 1) * SIZE);
    for (let y = 0; y < SIZE; y++) {
        const row = y * (SIZE * 4 + 1);
        raw[row] = 0;
        rgba.copy(raw, row + 1, y * SIZE * 4, (y + 1) * SIZE * 4);
    }
    return Buffer.concat([
        Buffer.from("89504e470d0a1a0a", "hex"),
        chunk("IHDR", ihdr),
        chunk("IDAT", deflateSync(raw, { level: 9 })),
        chunk("IEND", Buffer.alloc(0)),
    ]);
}

const generated = variants.map((spec) => {
    const decoded = renderArtwork(spec);
    const encoded = encodePng(decoded);
    return { spec, decoded, encoded };
});

const manifest = {
    schema: "compose-preview-project-owned-artwork/v1",
    source: {
        kind: "project-owned-procedural",
        generator: "scripts/generate-ui-builder-artwork.mjs",
        upstreamArtwork: false,
        networkRequired: false,
    },
    license: {
        spdx: "Apache-2.0",
        file: "LICENSE",
        scope: "Artwork pixels generated by this repository; no upstream podcast artwork copied.",
    },
    assets: generated.map(({ spec, decoded, encoded }) => ({
        assetKey: spec.assetKey,
        sourceVariant: spec.id,
        widthPx: SIZE,
        heightPx: SIZE,
        encodedSha256: sha256(encoded),
        decodedRgbaSha256: sha256(decoded),
        drawableResource: `drawable/${spec.fileStem}.png`,
        byteResource: `files/artwork/${spec.fileStem}.png`,
    })),
};
const manifestBytes = Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`);

const outputs = new Map();
for (const { spec, encoded } of generated) {
    outputs.set(resolve(FILE_ROOT, `drawable/${spec.fileStem}.png`), encoded);
    outputs.set(resolve(FILE_ROOT, `files/artwork/${spec.fileStem}.png`), encoded);
}
outputs.set(resolve(FILE_ROOT, "files/artwork/manifest-v1.json"), manifestBytes);

if (process.argv.includes("--check")) {
    const failures = [];
    for (const [path, expected] of outputs) {
        try {
            const actual = await readFile(path);
            if (!actual.equals(expected)) failures.push(`${path} is stale`);
        } catch {
            failures.push(`${path} is missing`);
        }
    }
    if (failures.length) throw new Error(failures.join("\n"));
} else {
    for (const [path, bytes] of outputs) {
        await mkdir(dirname(path), { recursive: true });
        await writeFile(path, bytes);
    }
}
