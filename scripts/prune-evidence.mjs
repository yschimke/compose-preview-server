// Remove evidence directories that nothing references any more.
//
// `renders/`, `serve-web/renders/`, `docs/evidence/` and `docs/design/evidence/` are all per-PR
// capture: images embedded in one pull request body, commit-pinned as AGENTS.md requires. Once that
// PR merges the directory has done its job — the pinned URLs keep resolving at the commit they name
// — but nothing removes it, so the trees only grow. They stand at 92 directories and ~64 MB, in a
// family of repositories on a hard per-checkout size budget.
//
// Four trees because the same thing was added four times under four names.
//
// A directory is KEPT when any of these holds:
//
//   * Something references it. Any tracked file outside the evidence trees containing the
//     directory's name counts, whether as a markdown link, an image embed or a comment. The match
//     is a plain substring, deliberately: over-matching keeps a directory, which is the safe
//     direction. "Outside the trees" means outside all of them, so two spent directories citing
//     each other cannot keep each other alive.
//   * It carries a KEEP file. The escape hatch for evidence worth keeping that nothing links to —
//     a baseline, a reference capture. Put the reason in the file.
//   * It is younger than the age floor. Evidence for a pull request that is still open must
//     survive, and the PR that adds a directory adds no reference to it from anywhere else. The
//     floor is measured from the last commit that touched the directory.
//
// The age floor needs real commit dates, so this must run against a full clone. Under a shallow one
// every path reports the clone's single commit and every directory looks new — nothing is pruned,
// silently. `--min-age-days 0` opts out of the floor entirely for a caller that knows better.

import { execFileSync } from "node:child_process";
import { existsSync, readdirSync, rmSync, statSync, readFileSync } from "node:fs";
import { join, resolve, sep } from "node:path";

export const DEFAULT_ROOTS = [
    "docs/design/evidence",
    "docs/evidence",
    "renders",
    "serve-web/renders",
];
export const DEFAULT_MIN_AGE_DAYS = 90;

const git = (repo, args) =>
    execFileSync("git", ["-C", repo, ...args], { encoding: "utf8", maxBuffer: 1 << 28 });

const trackedFiles = (repo) => git(repo, ["ls-files"]).split("\n").filter(Boolean);

/** Unix seconds of the last commit touching `rel`, or null when git knows nothing about it. */
const lastCommitEpoch = (repo, rel) => {
    const out = git(repo, ["log", "-1", "--format=%ct", "--", rel]).trim();
    return out ? Number(out) : null;
};

const insideRoots = (path, roots) => roots.some((r) => path === r || path.startsWith(r + "/"));

/** Every tracked file outside the evidence trees, concatenated, for substring matching. */
const referencedNames = (repo, roots) => {
    const chunks = [];
    for (const rel of trackedFiles(repo)) {
        if (insideRoots(rel, roots)) continue;
        try {
            chunks.push(readFileSync(join(repo, rel), "latin1"));
        } catch {
            // unreadable is not referenced
        }
    }
    return chunks.join("\n");
};

/**
 * `{ prunable, kept }` — both keyed by the directory's repo-relative path.
 *
 * Keyed by path rather than by name because the trees can hold the same name twice; the reference
 * check still matches on the bare name, which is how a link, an embed or a comment spells it.
 */
export function classify(repo, { minAgeDays = DEFAULT_MIN_AGE_DAYS, roots = DEFAULT_ROOTS, now = Date.now() } = {}) {
    const present = roots.filter((r) => existsSync(join(repo, r)) && statSync(join(repo, r)).isDirectory());
    if (present.length === 0) return { prunable: [], kept: {} };

    const floor = now / 1000 - minAgeDays * 86400;
    const haystack = referencedNames(repo, roots);
    const prunable = [];
    const kept = {};

    for (const root of present) {
        const entries = readdirSync(join(repo, root), { withFileTypes: true })
            .filter((e) => e.isDirectory())
            .map((e) => e.name)
            .sort();
        for (const name of entries) {
            const rel = `${root}/${name}`;
            if (existsSync(join(repo, rel, "KEEP"))) {
                kept[rel] = "KEEP file";
            } else if (haystack.includes(name)) {
                kept[rel] = "referenced";
            } else {
                const touched = lastCommitEpoch(repo, rel);
                if (touched !== null && touched > floor) {
                    kept[rel] = `only ${Math.floor(now / 1000 - touched) / 86400 | 0}d old (floor ${minAgeDays}d)`;
                } else {
                    prunable.push(rel);
                }
            }
        }
    }
    return { prunable, kept };
}

function main(argv) {
    const arg = (name, fallback) => {
        const i = argv.indexOf(name);
        return i === -1 ? fallback : argv[i + 1];
    };
    const repo = resolve(arg("--repo", "."));
    const minAgeDays = Number(arg("--min-age-days", DEFAULT_MIN_AGE_DAYS));
    const roots = arg("--roots", DEFAULT_ROOTS.join(",")).split(",").map((r) => r.trim()).filter(Boolean);
    const prune = argv.includes("--prune");
    const quiet = argv.includes("--quiet");

    const { prunable, kept } = classify(repo, { minAgeDays, roots });

    if (!quiet) {
        console.log(`${Object.keys(kept).length} kept, ${prunable.length} prunable`);
        for (const rel of prunable) console.log(`  prune ${rel}`);
    }

    if (prune) {
        for (const rel of prunable) rmSync(join(repo, rel), { recursive: true, force: true });
        if (!quiet && prunable.length) console.log(`removed ${prunable.length} directories`);
        return 0;
    }
    // Report mode is a gate: non-zero when there is something to prune.
    return prunable.length ? 1 : 0;
}

if (process.argv[1] && resolve(process.argv[1]).endsWith(`${sep}prune-evidence.mjs`)) {
    process.exit(main(process.argv.slice(2)));
}
