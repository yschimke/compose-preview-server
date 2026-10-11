// A small byte-mode QR Code encoder, for the viewer's "On phone" popover.
//
// Written here rather than vendored because what that popover needs is a narrow slice of the
// standard (ISO/IEC 18004): one URL, byte mode, error correction L or M, no structured append, no
// ECI, no Kanji. That slice is a few hundred lines; a general encoder is several times that. It
// follows the shape of Project Nayuki's reference encoder (MIT), which is the clearest statement of
// the algorithm, and `test/qrEncode.test.ts` pins its output, module for module, to matrices
// produced independently by python-qrcode.
//
// Mask choice runs the standard's four penalty rules over the finished symbol, format and version
// information included. A decoder accepts any of the eight masks, so the choice only affects how
// easily a camera locks on; the tests pin the mask explicitly when they compare against a
// reference, plus one text whose chosen mask turns on rule N3 counting both sides of a run.

/** The two error-correction levels offered: L recovers ~7% of codewords, M ~15%. */
export type Ecl = "L" | "M";

/** A finished symbol: [size]×[size] modules, row-major, `true` dark. */
export interface QrCode {
    readonly version: number;
    readonly ecl: Ecl;
    readonly mask: number;
    readonly size: number;
    readonly modules: readonly boolean[];
}

// Per version 1..40 (index 0 unused): error-correction codewords per block, and the block count.
// From the standard's Table 9.
const ECC_PER_BLOCK: Record<Ecl, number[]> = {
    L: [
        0, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28,
        30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30,
        30, 30, 30, 30, 30,
    ],
    M: [
        0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28,
        26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
        28, 28, 28, 28, 28,
    ],
};
const BLOCKS: Record<Ecl, number[]> = {
    L: [
        0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9,
        10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25,
    ],
    M: [
        0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17,
        17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47,
        49,
    ],
};
// The two format-information bits that name the level (L is 01, M is 00).
const ECL_BITS: Record<Ecl, number> = { L: 1, M: 0 };

/** Modules left for codewords once every function pattern is placed. */
function rawDataModules(ver: number): number {
    let result = (16 * ver + 128) * ver + 64;
    if (ver >= 2) {
        const align = Math.floor(ver / 7) + 2;
        result -= (25 * align - 10) * align - 55;
        if (ver >= 7) result -= 36;
    }
    return result;
}

/** Data codewords (bytes) a symbol of this version and level carries. */
export function dataCapacity(ver: number, ecl: Ecl): number {
    return (
        Math.floor(rawDataModules(ver) / 8) -
        ECC_PER_BLOCK[ecl][ver] * BLOCKS[ecl][ver]
    );
}

/** Centre coordinates of the alignment patterns, on both axes. */
function alignmentPositions(ver: number): number[] {
    if (ver === 1) return [];
    const count = Math.floor(ver / 7) + 2;
    const step = Math.floor((ver * 8 + count * 3 + 5) / (count * 4 - 4)) * 2;
    const result = [6];
    for (let pos = ver * 4 + 10; result.length < count; pos -= step)
        result.splice(1, 0, pos);
    return result;
}

// GF(2^8) with the standard's polynomial x^8 + x^4 + x^3 + x^2 + 1.
function gfMul(x: number, y: number): number {
    let z = 0;
    for (let i = 7; i >= 0; i--) {
        z = (z << 1) ^ ((z >>> 7) * 0x11d);
        z ^= ((y >>> i) & 1) * x;
    }
    return z;
}

/** The Reed-Solomon generator polynomial of [degree], highest term dropped. */
function rsDivisor(degree: number): number[] {
    const result = new Array<number>(degree).fill(0);
    result[degree - 1] = 1;
    let root = 1;
    for (let i = 0; i < degree; i++) {
        for (let j = 0; j < degree; j++) {
            result[j] = gfMul(result[j], root);
            if (j + 1 < degree) result[j] ^= result[j + 1];
        }
        root = gfMul(root, 2);
    }
    return result;
}

function rsRemainder(data: number[], divisor: number[]): number[] {
    const result = divisor.map(() => 0);
    for (const b of data) {
        const factor = b ^ result.shift()!;
        result.push(0);
        divisor.forEach((coef, i) => (result[i] ^= gfMul(coef, factor)));
    }
    return result;
}

/** Split data into blocks, append each block's ECC, and interleave as the standard orders them. */
function withEcc(data: number[], ver: number, ecl: Ecl): number[] {
    const blocks = BLOCKS[ecl][ver];
    const eccLen = ECC_PER_BLOCK[ecl][ver];
    const raw = Math.floor(rawDataModules(ver) / 8);
    const shortCount = blocks - (raw % blocks);
    const shortLen = Math.floor(raw / blocks);
    const divisor = rsDivisor(eccLen);
    const all: number[][] = [];
    for (let i = 0, k = 0; i < blocks; i++) {
        const len = shortLen - eccLen + (i < shortCount ? 0 : 1);
        const block = data.slice(k, k + len);
        k += len;
        const ecc = rsRemainder(block, divisor);
        // A short block gets a placeholder where the long blocks' extra byte sits, so one column
        // walk interleaves both kinds; the placeholder is skipped below.
        if (i < shortCount) block.push(-1);
        all.push(block.concat(ecc));
    }
    const out: number[] = [];
    for (let i = 0; i < all[0].length; i++)
        for (const block of all) if (block[i] >= 0) out.push(block[i]);
    return out;
}

/** Function patterns, codewords and format bits for one candidate mask. */
class Grid {
    readonly modules: boolean[];
    readonly reserved: boolean[];

    constructor(
        readonly size: number,
        private readonly ver: number,
    ) {
        this.modules = new Array<boolean>(size * size).fill(false);
        this.reserved = new Array<boolean>(size * size).fill(false);
    }

    get(x: number, y: number): boolean {
        return this.modules[y * this.size + x];
    }

    fn(x: number, y: number, dark: boolean): void {
        this.modules[y * this.size + x] = dark;
        this.reserved[y * this.size + x] = true;
    }

    drawFunctionPatterns(): void {
        const n = this.size;
        for (let i = 0; i < n; i++) {
            this.fn(6, i, i % 2 === 0);
            this.fn(i, 6, i % 2 === 0);
        }
        // Finders, each with its one-module light separator (clipped at the symbol's edge).
        for (const [cx, cy] of [
            [3, 3],
            [n - 4, 3],
            [3, n - 4],
        ]) {
            for (let dy = -4; dy <= 4; dy++)
                for (let dx = -4; dx <= 4; dx++) {
                    const d = Math.max(Math.abs(dx), Math.abs(dy));
                    const x = cx + dx;
                    const y = cy + dy;
                    if (x >= 0 && x < n && y >= 0 && y < n)
                        this.fn(x, y, d !== 2 && d !== 4);
                }
        }
        const align = alignmentPositions(this.ver);
        const last = align.length - 1;
        for (let i = 0; i <= last; i++)
            for (let j = 0; j <= last; j++) {
                // The three corners an alignment grid would put on a finder are left out.
                if (
                    (i === 0 && j === 0) ||
                    (i === 0 && j === last) ||
                    (i === last && j === 0)
                )
                    continue;
                for (let dy = -2; dy <= 2; dy++)
                    for (let dx = -2; dx <= 2; dx++)
                        this.fn(
                            align[i] + dx,
                            align[j] + dy,
                            Math.max(Math.abs(dx), Math.abs(dy)) !== 1,
                        );
            }
        // Reserve the format areas now; their real bits are written once the mask is known.
        this.drawFormat(0, "M");
        if (this.ver >= 7) {
            let rem = this.ver;
            for (let i = 0; i < 12; i++)
                rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
            const bits = (this.ver << 12) | rem;
            for (let i = 0; i < 18; i++) {
                const dark = ((bits >>> i) & 1) !== 0;
                const a = n - 11 + (i % 3);
                const b = Math.floor(i / 3);
                this.fn(a, b, dark);
                this.fn(b, a, dark);
            }
        }
    }

    drawFormat(mask: number, ecl: Ecl): void {
        const n = this.size;
        const data = (ECL_BITS[ecl] << 3) | mask;
        let rem = data;
        for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        const bits = ((data << 10) | rem) ^ 0x5412;
        const bit = (i: number) => ((bits >>> i) & 1) !== 0;
        for (let i = 0; i <= 5; i++) this.fn(8, i, bit(i));
        this.fn(8, 7, bit(6));
        this.fn(8, 8, bit(7));
        this.fn(7, 8, bit(8));
        for (let i = 9; i < 15; i++) this.fn(14 - i, 8, bit(i));
        for (let i = 0; i < 8; i++) this.fn(n - 1 - i, 8, bit(i));
        for (let i = 8; i < 15; i++) this.fn(8, n - 15 + i, bit(i));
        // The single always-dark module beside the bottom-left finder.
        this.fn(8, n - 8, true);
    }

    /** Lay codewords in the two-column zigzag, from the bottom-right corner. */
    drawCodewords(codewords: number[]): void {
        const n = this.size;
        let i = 0;
        for (let right = n - 1; right >= 1; right -= 2) {
            if (right === 6) right = 5; // The vertical timing column is skipped whole.
            for (let vert = 0; vert < n; vert++)
                for (let j = 0; j < 2; j++) {
                    const x = right - j;
                    const upward = ((right + 1) & 2) === 0;
                    const y = upward ? n - 1 - vert : vert;
                    if (this.reserved[y * n + x] || i >= codewords.length * 8)
                        continue;
                    this.modules[y * n + x] =
                        ((codewords[i >>> 3] >>> (7 - (i & 7))) & 1) !== 0;
                    i++;
                }
        }
    }

    /** XOR mask [mask] over the data area; applying it twice undoes it. */
    applyMask(mask: number): void {
        const n = this.size;
        for (let y = 0; y < n; y++)
            for (let x = 0; x < n; x++) {
                if (this.reserved[y * n + x]) continue;
                let flip: boolean;
                switch (mask) {
                    case 0:
                        flip = (x + y) % 2 === 0;
                        break;
                    case 1:
                        flip = y % 2 === 0;
                        break;
                    case 2:
                        flip = x % 3 === 0;
                        break;
                    case 3:
                        flip = (x + y) % 3 === 0;
                        break;
                    case 4:
                        flip =
                            (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0;
                        break;
                    case 5:
                        flip = ((x * y) % 2) + ((x * y) % 3) === 0;
                        break;
                    case 6:
                        flip = (((x * y) % 2) + ((x * y) % 3)) % 2 === 0;
                        break;
                    default:
                        flip = (((x + y) % 2) + ((x * y) % 3)) % 2 === 0;
                }
                if (flip) this.modules[y * n + x] = !this.modules[y * n + x];
            }
    }

    /** The standard's penalty score (rules N1–N4): lower scans more easily. */
    penalty(): number {
        const n = this.size;
        let score = 0;
        let dark = 0;
        // N3: a 1:1:3:1:1 dark-light run with four light modules beside it is +40 for EACH side
        // that has them, so a run with light on both sides costs 80. Beyond the symbol's edge is
        // the quiet zone, which is light.
        const finderLike = (line: boolean[], i: number) => {
            const p = [true, false, true, true, true, false, true];
            for (let k = 0; k < 7; k++) if (line[i + k] !== p[k]) return 0;
            const light = (from: number) => {
                for (let k = from; k < from + 4; k++)
                    if (k >= 0 && k < n && line[k]) return 0;
                return 40;
            };
            return light(i - 4) + light(i + 7);
        };
        for (let pass = 0; pass < 2; pass++)
            for (let a = 0; a < n; a++) {
                const line: boolean[] = [];
                for (let b = 0; b < n; b++)
                    line.push(pass === 0 ? this.get(b, a) : this.get(a, b));
                let run = 1;
                for (let b = 1; b <= n; b++) {
                    if (b < n && line[b] === line[b - 1]) run++;
                    else {
                        if (run >= 5) score += run - 2;
                        run = 1;
                    }
                }
                for (let b = 0; b + 7 <= n; b++) score += finderLike(line, b);
            }
        for (let y = 0; y < n; y++)
            for (let x = 0; x < n; x++) {
                const c = this.get(x, y);
                if (c) dark++;
                if (
                    x + 1 < n &&
                    y + 1 < n &&
                    c === this.get(x + 1, y) &&
                    c === this.get(x, y + 1) &&
                    c === this.get(x + 1, y + 1)
                )
                    score += 3;
            }
        const total = n * n;
        // N4: 10 for every full 5% the dark share departs from 50%.
        score += Math.floor(Math.abs(dark * 20 - total * 10) / total) * 10;
        return score;
    }
}

export interface EncodeOptions {
    /** Only ever use this level, instead of M where it fits and L where it does not. */
    ecl?: Ecl;
    /** Force a mask (0–7) instead of choosing by penalty. */
    mask?: number;
    /** Force a version instead of the smallest that fits. */
    version?: number;
}

/**
 * Encode [text] as UTF-8 bytes in the smallest symbol that holds it. Within that version M is
 * preferred, and L takes over only where it saves a version: a smaller symbol has bigger modules on
 * the same screen, which helps a camera more than the extra redundancy would. Null when even
 * version 40 at L cannot hold it.
 */
export function encodeQr(
    text: string,
    options: EncodeOptions = {},
): QrCode | null {
    const bytes = Array.from(new TextEncoder().encode(text));
    const levels: Ecl[] = options.ecl ? [options.ecl] : ["M", "L"];
    const first = options.version ?? 1;
    const last = options.version ?? 40;
    for (let ver = first; ver <= last; ver++) {
        const countBits = ver <= 9 ? 8 : 16;
        for (const ecl of levels) {
            const capacity = dataCapacity(ver, ecl);
            if (4 + countBits + bytes.length * 8 > capacity * 8) continue;
            return build(bytes, ver, ecl, countBits, capacity, options.mask);
        }
    }
    return null;
}

function build(
    bytes: number[],
    ver: number,
    ecl: Ecl,
    countBits: number,
    capacity: number,
    forcedMask: number | undefined,
): QrCode {
    // Bit stream: byte-mode indicator, count, payload, then terminator and padding.
    const bits: number[] = [];
    const put = (value: number, len: number) => {
        for (let i = len - 1; i >= 0; i--) bits.push((value >>> i) & 1);
    };
    put(0b0100, 4);
    put(bytes.length, countBits);
    for (const b of bytes) put(b, 8);
    put(0, Math.min(4, capacity * 8 - bits.length));
    put(0, (8 - (bits.length % 8)) % 8);
    const data: number[] = [];
    for (let i = 0; i < bits.length; i += 8)
        data.push(bits.slice(i, i + 8).reduce((acc, b) => (acc << 1) | b, 0));
    for (let pad = 0xec; data.length < capacity; pad ^= 0xec ^ 0x11)
        data.push(pad);

    const size = ver * 4 + 17;
    const grid = new Grid(size, ver);
    grid.drawFunctionPatterns();
    grid.drawCodewords(withEcc(data, ver, ecl));
    let mask = forcedMask ?? 0;
    if (forcedMask === undefined) {
        let best = Infinity;
        for (let m = 0; m < 8; m++) {
            grid.applyMask(m);
            grid.drawFormat(m, ecl);
            const score = grid.penalty();
            if (score < best) {
                best = score;
                mask = m;
            }
            grid.applyMask(m);
        }
    }
    grid.applyMask(mask);
    grid.drawFormat(mask, ecl);
    return { version: ver, ecl, mask, size, modules: grid.modules };
}

/**
 * The symbol as an SVG string: one path of horizontal runs on a light square, with the standard's
 * four-module quiet zone. Always dark on light, whatever the page theme — an inverted code is one
 * many phone cameras will not read.
 */
export function qrSvg(code: QrCode, label: string): string {
    const n = code.size;
    const quiet = 4;
    let path = "";
    for (let y = 0; y < n; y++)
        for (let x = 0; x < n;) {
            if (!code.modules[y * n + x]) {
                x++;
                continue;
            }
            let run = 1;
            while (x + run < n && code.modules[y * n + x + run]) run++;
            path += `M${x + quiet} ${y + quiet}h${run}v1h-${run}z`;
            x += run;
        }
    const box = n + quiet * 2;
    const esc = label.replace(/[&<>"]/g, (c) => `&#${c.charCodeAt(0)};`);
    return (
        `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${box} ${box}" ` +
        `role="img" aria-label="${esc}" shape-rendering="crispEdges">` +
        `<rect width="${box}" height="${box}" fill="#fff"/>` +
        `<path d="${path}" fill="#000"/></svg>`
    );
}
