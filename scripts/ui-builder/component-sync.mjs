#!/usr/bin/env node

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

/**
 * Move a component an editor published to a host into a project's own repository.
 *
 *   component-sync.mjs pull <system>/<componentId> --server <url> [--workspace <dir>]
 *
 * A component has the two homes a design has: the host, where it lives while it moves (the editor's
 * "Publish to project library" writes there), and the repository, where it settles. This is the
 * step between them. It reads `GET /api/ui-builder/v1/component-library/{system}/{componentId}/file`
 * — the one-component document exactly as published, and the index line naming it — and writes
 * both under `<workspace>/ui-builder/components/`, merging the line into an existing `index.json`.
 * Commit the result, and the project's copy is read first from then on: it shadows the host's, and
 * since the content is the same, so is the digest every importing design recorded — nothing reports
 * drift for having moved.
 *
 * The bearer is read from `COMPOSE_PREVIEW_TOKEN` (or `COMPOSE_PREVIEW_UI_BUILDER_TOKEN`), never
 * from an argument, as `design-sync.mjs` reads it; reading needs `ui-builder-read`.
 */

export const INDEX_SCHEMA = "compose-ui-builder-component-index/v1";

const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);

/**
 * `server` as a bare https origin (http only on loopback), or a refusal naming the rule. The token
 * goes to this origin and nowhere else — the rule `RemotePreviewClient.kt` applies on the JVM side.
 */
export function validatedServerOrigin(server) {
  const refusal = new Error("--server must be an https origin (http is allowed only on loopback)");
  let url;
  try {
    url = new URL(server);
  } catch {
    throw refusal;
  }
  const bare = url.username === "" && url.password === "" && url.search === "" && url.hash === "";
  const rootPath = url.pathname === "" || url.pathname === "/";
  const scheme = url.protocol === "https:" || (url.protocol === "http:" && LOOPBACK_HOSTS.has(url.hostname));
  const authority = /^[a-z][a-z0-9+.-]*:\/\//i.test(server.trim());
  if (!url.hostname || !bare || !rootPath || !scheme || !authority) throw refusal;
  return url.origin;
}

/** The rules the server's index reader applies, so nothing written here is dropped on read. */
const COMPONENT_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
const COMPONENT_FILE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.json$/;

/**
 * `existing` (an `index.json`'s text, or null) with [entry] in it: replacing a row with the same
 * id, keeping every other row as it was, ordered by id so two pulls in either order agree.
 */
export function mergeIndex(existing, entry) {
  if (!COMPONENT_ID.test(entry.id ?? "")) throw new Error(`unusable component id ${entry.id}`);
  if (!COMPONENT_FILE.test(entry.file ?? "")) throw new Error(`unusable file name ${entry.file}`);
  const index = existing == null ? { schema: INDEX_SCHEMA, components: [] } : JSON.parse(existing);
  if (index.schema !== INDEX_SCHEMA) {
    throw new Error(`index.json is ${index.schema ?? "unversioned"}, not ${INDEX_SCHEMA}`);
  }
  const row = { id: entry.id, title: entry.title, file: entry.file };
  if (entry.description) row.description = entry.description;
  const others = (index.components ?? []).filter((it) => it.id !== entry.id);
  const components = [...others, row].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  return `${JSON.stringify({ ...index, components }, null, 2)}\n`;
}

/** `system/componentId`, split and checked. */
export function parseTarget(target) {
  const slash = target?.indexOf("/") ?? -1;
  if (slash <= 0 || slash === target.length - 1) {
    throw new Error("name the component as <system>/<componentId>");
  }
  const system = target.slice(0, slash);
  const componentId = target.slice(slash + 1);
  if (!COMPONENT_ID.test(componentId)) throw new Error(`unusable component id ${componentId}`);
  return { system, componentId };
}

/** Writes a pulled component into [workspace]; returns the two paths it wrote. */
export function writePulled(workspace, reply) {
  const directory = join(workspace, "ui-builder", "components");
  mkdirSync(directory, { recursive: true });
  const indexPath = join(directory, "index.json");
  const merged = mergeIndex(existsSync(indexPath) ? readFileSync(indexPath, "utf8") : null, reply.entry);
  const filePath = join(directory, reply.entry.file);
  writeFileSync(filePath, `${JSON.stringify(reply.document, null, 2)}\n`);
  writeFileSync(indexPath, merged);
  return { filePath, indexPath };
}

async function fetchFile(server, system, componentId, token) {
  const path =
    `/api/ui-builder/v1/component-library/${encodeURIComponent(system)}/` +
    `${encodeURIComponent(componentId)}/file`;
  const response = await fetch(new URL(path, validatedServerOrigin(server)), {
    // The token is for this origin only; a redirect would carry it elsewhere.
    redirect: "error",
    headers: token ? { Authorization: `Bearer ${token}`, "X-Compose-Preview-Token": token } : {},
  });
  const text = await response.text();
  if (!response.ok) {
    let reason = text;
    try {
      reason = JSON.parse(text).error ?? text;
    } catch {}
    throw new Error(`${response.status}: ${reason}`);
  }
  return JSON.parse(text);
}

function flag(argv, name) {
  const at = argv.indexOf(name);
  return at >= 0 ? argv[at + 1] : undefined;
}

async function main(argv) {
  const [verb, target] = argv;
  const server = flag(argv, "--server");
  if (verb !== "pull" || !target || !server) {
    console.error("usage: component-sync.mjs pull <system>/<componentId> --server <url> [--workspace <dir>]");
    return 2;
  }
  const token = process.env.COMPOSE_PREVIEW_TOKEN || process.env.COMPOSE_PREVIEW_UI_BUILDER_TOKEN;
  const { system, componentId } = parseTarget(target);
  const reply = await fetchFile(server, system, componentId, token);
  const { filePath, indexPath } = writePulled(flag(argv, "--workspace") ?? ".", reply);
  console.log(`${filePath}: ${system}/${componentId}, indexed in ${indexPath}`);
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).then(
    (code) => {
      process.exitCode = code;
    },
    (error) => {
      console.error(error.message);
      process.exitCode = 1;
    },
  );
}
