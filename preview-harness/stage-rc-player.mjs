// Stages the TypeScript Remote Compose player exactly as `:server:stageRcPlayerJs` does — rc-players'
// `remote-compose-player-js-dist` zip from Maven Central with `server/src/rc-player/inert-custom-host.js`
// appended — at the same path, so the Node-only harness jobs (no Gradle) load the bundle the server
// serves. The version is the `remote-compose-player-js` ref in `gradle/libs.versions.toml`.
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const out = join(root, "server/build/generated/rc-player-js/rc-player/bundle.js");
const shim = readFileSync(join(root, "server/src/rc-player/inert-custom-host.js"));

const catalog = readFileSync(join(root, "gradle/libs.versions.toml"), "utf8");
const version = catalog.match(/^remote-compose-player-js = "([^"]+)"/m)?.[1];
if (!version) throw new Error("no remote-compose-player-js version in gradle/libs.versions.toml");

const marker = `${out}.version`;
if (existsSync(out) && existsSync(marker) && readFileSync(marker, "utf8") === version) process.exit(0);

const base = "https://repo1.maven.org/maven2/ee/schimke/composeai/remote-compose-player-js-dist";
const url = `${base}/${version}/remote-compose-player-js-dist-${version}-dist.zip`;
let response;
for (let attempt = 1; ; attempt++) {
  response = await fetch(url);
  if (response.ok || attempt === 5 || (response.status !== 429 && response.status < 500)) break;
  await new Promise((r) => setTimeout(r, attempt * 5000));
}
if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);

const work = mkdtempSync(join(tmpdir(), "rc-player-js-"));
try {
  const zip = join(work, "dist.zip");
  writeFileSync(zip, Buffer.from(await response.arrayBuffer()));
  const bundle = execFileSync("unzip", ["-p", zip, "bundle.js"], { maxBuffer: 64 * 1024 * 1024 });
  if (bundle.length === 0) throw new Error(`${url} has no bundle.js`);
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, Buffer.concat([bundle, Buffer.from("\n"), shim]));
  writeFileSync(marker, version);
} finally {
  rmSync(work, { recursive: true, force: true });
}
