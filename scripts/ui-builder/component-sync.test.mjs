import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import {
  INDEX_SCHEMA,
  mergeIndex,
  parseTarget,
  validatedServerOrigin,
  writePulled,
} from "./component-sync.mjs";

const entry = { id: "inbox-email", title: "Inbox email", file: "inbox-email.json" };

test("a pull into a project with no library starts its index", () => {
  const index = JSON.parse(mergeIndex(null, entry));
  assert.equal(index.schema, INDEX_SCHEMA);
  assert.deepEqual(index.components, [entry]);
});

test("a pull replaces its own row and keeps every other one, in id order", () => {
  const existing = JSON.stringify({
    schema: INDEX_SCHEMA,
    components: [
      { id: "thread-row", title: "Thread row", file: "thread-row.json" },
      { id: "inbox-email", title: "Old title", file: "inbox-email.json" },
    ],
  });
  const index = JSON.parse(mergeIndex(existing, { ...entry, description: "One row" }));
  assert.deepEqual(
    index.components.map((it) => [it.id, it.title, it.description]),
    [
      ["inbox-email", "Inbox email", "One row"],
      ["thread-row", "Thread row", undefined],
    ],
  );
});

test("an index of another kind, or a name that could leave the directory, is refused", () => {
  assert.throws(() => mergeIndex(JSON.stringify({ schema: "something-else" }), entry));
  assert.throws(() => mergeIndex(null, { ...entry, file: "../escape.json" }));
  assert.throws(() => mergeIndex(null, { ...entry, id: "../escape" }));
});

test("a target names a system and a component", () => {
  assert.deepEqual(parseTarget("m3-catalog/inbox-email"), {
    system: "m3-catalog",
    componentId: "inbox-email",
  });
  assert.throws(() => parseTarget("inbox-email"));
  assert.throws(() => parseTarget("m3-catalog/"));
});

test("the document and the index land under ui-builder/components", () => {
  const workspace = mkdtempSync(join(tmpdir(), "component-sync-"));
  try {
    const directory = join(workspace, "ui-builder", "components");
    mkdirSync(directory, { recursive: true });
    writeFileSync(
      join(directory, "index.json"),
      JSON.stringify({ schema: INDEX_SCHEMA, components: [{ id: "other", title: "Other", file: "other.json" }] }),
    );
    const document = { id: "inbox-email", components: { "inbox-email": { name: "InboxEmail", root: "row" } } };

    writePulled(workspace, { system: "m3-catalog", entry, document });

    assert.deepEqual(JSON.parse(readFileSync(join(directory, "inbox-email.json"), "utf8")), document);
    const index = JSON.parse(readFileSync(join(directory, "index.json"), "utf8"));
    assert.deepEqual(index.components.map((it) => it.id), ["inbox-email", "other"]);
  } finally {
    rmSync(workspace, { recursive: true, force: true });
  }
});

test("the token is only sent to an https origin, or http on loopback", () => {
  assert.equal(validatedServerOrigin("https://preview.coo.ee"), "https://preview.coo.ee");
  assert.equal(validatedServerOrigin("http://127.0.0.1:7777/"), "http://127.0.0.1:7777");
  assert.throws(() => validatedServerOrigin("http://preview.coo.ee"));
  assert.throws(() => validatedServerOrigin("https://user:pw@preview.coo.ee"));
  assert.throws(() => validatedServerOrigin("https://preview.coo.ee/path"));
});
