// Tests for prune-evidence.mjs. Run: node --test scripts/prune-evidence.test.mjs
//
// Each of the three ways a directory survives is pinned separately, because the failure mode of
// this script is silent deletion of something that mattered. The age floor gets the most attention:
// it is the rule that keeps an open PR's evidence, and it cannot be checked by reading the tree —
// it needs real commit timestamps, so the fixtures build a real git repository.

import { test, describe, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { classify, DEFAULT_ROOTS } from "./prune-evidence.mjs";

const DAY = 86400_000;
const IDENT = {
    GIT_AUTHOR_NAME: "T",
    GIT_AUTHOR_EMAIL: "t@example.com",
    GIT_COMMITTER_NAME: "T",
    GIT_COMMITTER_EMAIL: "t@example.com",
};

describe("prune-evidence", () => {
    let repo;
    let now;
    const ROOT = "docs/design/evidence";

    const git = (args, extraEnv = {}) =>
        execFileSync("git", ["-C", repo, ...args], { env: { ...process.env, ...IDENT, ...extraEnv } });

    /** Commit a directory as if it were written `ageDays` ago. */
    const addEvidence = (name, ageDays, { keep = false, readme = null, root = ROOT } = {}) => {
        const d = join(repo, root, name);
        mkdirSync(d, { recursive: true });
        writeFileSync(join(d, "README.md"), readme ?? `# ${name}\n`);
        if (keep) writeFileSync(join(d, "KEEP"), "a baseline nothing links to\n");
        const stamp = new Date(now - ageDays * DAY).toISOString();
        git(["add", "-A"]);
        git(["commit", "-q", "-m", `add ${name}`], { GIT_AUTHOR_DATE: stamp, GIT_COMMITTER_DATE: stamp });
    };

    const run = (minAgeDays = 90) => classify(repo, { minAgeDays, now });

    beforeEach(() => {
        repo = mkdtempSync(join(tmpdir(), "prune-evidence-"));
        now = Date.now();
        execFileSync("git", ["-C", repo, "init", "-q", "-b", "main"]);
        for (const r of DEFAULT_ROOTS) mkdirSync(join(repo, r), { recursive: true });
    });
    afterEach(() => rmSync(repo, { recursive: true, force: true }));

    test("old and unreferenced is prunable", () => {
        addEvidence("spent", 200);
        const { prunable, kept } = run();
        assert.deepEqual(prunable, [`${ROOT}/spent`]);
        assert.deepEqual(kept, {});
    });

    test("referenced is kept however old", () => {
        addEvidence("cited", 999);
        mkdirSync(join(repo, "docs"), { recursive: true });
        writeFileSync(join(repo, "docs/index.md"), `see [it](design/evidence/cited/README.md)\n`);
        git(["add", "-A"]);
        git(["commit", "-q", "-m", "doc"]);
        const { prunable, kept } = run();
        assert.deepEqual(prunable, []);
        assert.equal(kept[`${ROOT}/cited`], "referenced");
    });

    test("young is kept even when unreferenced — the open-PR case", () => {
        addEvidence("in-flight", 3);
        const { prunable, kept } = run();
        assert.deepEqual(prunable, []);
        assert.match(kept[`${ROOT}/in-flight`], /3d old/);
    });

    test("age floor boundary", () => {
        addEvidence("just-under", 89);
        addEvidence("just-over", 91);
        assert.deepEqual(run(90).prunable, [`${ROOT}/just-over`]);
    });

    test("KEEP file wins over age", () => {
        addEvidence("baseline", 999, { keep: true });
        const { prunable, kept } = run();
        assert.deepEqual(prunable, []);
        assert.equal(kept[`${ROOT}/baseline`], "KEEP file");
    });

    test("a reference from source counts, not only markdown", () => {
        addEvidence("live-press", 400);
        writeFileSync(join(repo, "Probe.kt"), "// the filmstrip renders/live-press/ is built from\n");
        git(["add", "-A"]);
        git(["commit", "-q", "-m", "src"]);
        assert.equal(run().kept[`${ROOT}/live-press`], "referenced");
    });

    test("a reference from inside the trees does not count", () => {
        addEvidence("a", 400, { readme: "see renders/b/README.md\n" });
        addEvidence("b", 400, { readme: "see docs/design/evidence/a/README.md\n", root: "renders" });
        assert.deepEqual(run().prunable.sort(), [`${ROOT}/a`, "renders/b"]);
    });

    test("every root is walked", () => {
        addEvidence("in-renders", 400, { root: "renders" });
        addEvidence("in-serve-web", 400, { root: "serve-web/renders" });
        addEvidence("in-docs", 400, { root: "docs/evidence" });
        addEvidence("in-design", 400);
        assert.deepEqual(run().prunable.sort(), [
            `${ROOT}/in-design`,
            "docs/evidence/in-docs",
            "renders/in-renders",
            "serve-web/renders/in-serve-web",
        ].sort());
    });

    test("the same name in two trees is kept apart", () => {
        addEvidence("dup", 400, { root: "renders" });
        addEvidence("dup", 400, { root: "docs/evidence" });
        assert.deepEqual(run().prunable.sort(), ["docs/evidence/dup", "renders/dup"]);
    });

    test("a root that does not exist is skipped", () => {
        rmSync(join(repo, "renders"), { recursive: true, force: true });
        addEvidence("spent", 400);
        assert.deepEqual(run().prunable, [`${ROOT}/spent`]);
    });

    test("a repository with no evidence tree at all is not an error", () => {
        const empty = mkdtempSync(join(tmpdir(), "prune-evidence-empty-"));
        execFileSync("git", ["-C", empty, "init", "-q", "-b", "main"]);
        assert.deepEqual(classify(empty, { now }), { prunable: [], kept: {} });
        rmSync(empty, { recursive: true, force: true });
    });

    test("prune actually deletes, and report mode is a gate", () => {
        addEvidence("spent", 200);
        assert.ok(existsSync(join(repo, ROOT, "spent")));
        const { prunable } = run();
        assert.deepEqual(prunable, [`${ROOT}/spent`]);
        rmSync(join(repo, prunable[0]), { recursive: true, force: true });
        assert.ok(!existsSync(join(repo, ROOT, "spent")));
        assert.deepEqual(run().prunable, []);
    });
});
