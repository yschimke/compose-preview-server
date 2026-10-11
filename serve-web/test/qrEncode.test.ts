// The viewer's QR encoder (`qr/encode.ts`), against matrices from an independent encoder.
//
// `fixtures/qr/reference-vectors.json` is python-qrcode's output for the same text, version, level
// and mask (`fixtures/qr/reference.py` regenerates it). Equal matrices mean equal bit streams,
// Reed-Solomon codewords, interleaving, placement, masking and format/version information — the
// whole symbol, not just "something a scanner happens to accept".

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dataCapacity, encodeQr, qrSvg, type Ecl } from "../src/qr/encode.js";

const VECTORS = JSON.parse(
    readFileSync(
        new URL("./fixtures/qr/reference-vectors.json", import.meta.url),
        "utf8",
    ),
) as {
    text: string;
    ecl: Ecl;
    version: number;
    mask: number;
    rows: string[];
}[];

function rows(code: NonNullable<ReturnType<typeof encodeQr>>): string[] {
    const out: string[] = [];
    for (let y = 0; y < code.size; y++) {
        let row = "";
        for (let x = 0; x < code.size; x++)
            row += code.modules[y * code.size + x] ? "1" : "0";
        out.push(row);
    }
    return out;
}

describe("the QR encoder", () => {
    for (const v of VECTORS) {
        it(`matches the reference at version ${v.version}-${v.ecl}, mask ${v.mask}`, () => {
            const code = encodeQr(v.text, {
                ecl: v.ecl,
                version: v.version,
                mask: v.mask,
            });
            assert.ok(code, "the text fits the reference's version");
            assert.equal(code.size, v.version * 4 + 17);
            assert.deepEqual(rows(code), v.rows);
        });
    }

    it("knows the standard's data capacities", () => {
        // ISO/IEC 18004 Table 7, data codewords per version and level.
        const expected: [number, Ecl, number][] = [
            [1, "L", 19],
            [1, "M", 16],
            [7, "M", 124],
            [10, "L", 274],
            [10, "M", 216],
            [27, "L", 1468],
            [27, "M", 1128],
            [40, "L", 2956],
            [40, "M", 2334],
        ];
        for (const [ver, ecl, bytes] of expected)
            assert.equal(dataCapacity(ver, ecl), bytes, `${ver}-${ecl}`);
    });

    it("uses the smallest version, at M unless only L saves a version", () => {
        // Byte mode at version 1 holds 14 bytes at M and 17 at L.
        assert.deepEqual(
            [encodeQr("a".repeat(14))!.version, encodeQr("a".repeat(14))!.ecl],
            [1, "M"],
        );
        assert.deepEqual(
            [encodeQr("a".repeat(15))!.version, encodeQr("a".repeat(15))!.ecl],
            [1, "L"],
        );
        assert.deepEqual(
            [encodeQr("a".repeat(18))!.version, encodeQr("a".repeat(18))!.ecl],
            [2, "M"],
        );
    });

    it("counts UTF-8 bytes, not characters", () => {
        // Seven characters, fourteen bytes: still version 1-M; one more 2-byte letter is not.
        assert.equal(encodeQr("ééééééé")!.ecl, "M");
        assert.equal(encodeQr("éééééééé")!.ecl, "L");
    });

    it("answers null past version 40-L rather than a broken symbol", () => {
        assert.equal(encodeQr("a".repeat(2953))!.version, 40);
        assert.equal(encodeQr("a".repeat(2954)), null);
    });

    it("chooses the mask the full penalty picks, N3 counting both sides of a run", () => {
        // Light on both sides of a 1:1:3:1:1 run is two N3 penalties, not one: counted once, this
        // text chose mask 7. The symbol itself is the reference vector at 3-L, mask 5.
        const text = "https://preview.coo.ee/preview/com.example.Card?x=0-";
        const code = encodeQr(text)!;
        assert.deepEqual([code.version, code.ecl, code.mask], [3, "L", 5]);
        const ref = VECTORS.find((v) => v.text === text && v.mask === 5)!;
        assert.deepEqual(rows(code), ref.rows);
    });

    it("chooses a mask by penalty when none is forced", () => {
        const code = encodeQr("https://preview.coo.ee/")!;
        assert.ok(code.mask >= 0 && code.mask < 8);
        // The choice is deterministic: the same text gives the same symbol.
        assert.deepEqual(
            rows(encodeQr("https://preview.coo.ee/")!),
            rows(code),
        );
    });
});

describe("the QR SVG", () => {
    it("paints every dark module once, inside a four-module quiet zone", () => {
        const code = encodeQr("HELLO", { mask: 0 })!;
        const svg = qrSvg(code, "QR code");
        assert.match(svg, /viewBox="0 0 29 29"/);
        const runs = [...svg.matchAll(/M(\d+) (\d+)h(\d+)v1h-\3z/g)];
        let painted = 0;
        for (const [, x, y, len] of runs) {
            assert.ok(+x >= 4 && +y >= 4 && +x + +len <= 25 && +y < 25);
            painted += +len;
        }
        assert.equal(painted, code.modules.filter(Boolean).length);
    });

    it("escapes its label and is dark on light whatever the theme", () => {
        const svg = qrSvg(encodeQr("x")!, `a "b" <c> & d`);
        assert.match(svg, /aria-label="a &#34;b&#34; &#60;c&#62; &#38; d"/);
        assert.match(svg, /<rect [^>]*fill="#fff"/);
        assert.match(svg, /<path [^>]*fill="#000"/);
    });
});
