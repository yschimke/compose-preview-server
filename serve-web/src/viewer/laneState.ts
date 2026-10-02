// Which lane the viewer is on, what it is called, and what each chip reports about it.
//
// The viewer has one primary chip that does two jobs — it NAMES the renderer on the stage
// ("AndroidX View", "Camaelon JS", "Figma spec", "Live") and its status dot says whether that render is interactive — plus a
// spec chip and a source chip that report their own lanes. Every route out of a lane has to
// un-press every chip: the Live chip, a combo pick, an SVG swap, the spec chip, Back/Forward. Miss
// one route and a chip stays lit over a lane that is no longer showing, which is a control claiming
// something untrue rather than a control that fails to work.
//
// DOM-free: `viewer.js` reads the toggles and passes booleans.

/** Which lanes are currently painting, in the order they take precedence. */
export interface LaneFlags {
    rcWasm: boolean;
    rc: boolean;
    wasm: boolean;
    spec: boolean;
    /** The daemon stream. */
    live: boolean;
}

/**
 * The Remote Compose player ids, each naming the implementation that draws:
 *
 * | id                  | implementation                                  | where                  |
 * | ------------------- | ----------------------------------------------- | ---------------------- |
 * | `androidx-view`     | AndroidX `remote-player-view` RemoteComposePlayer | daemon                 |
 * | `androidx-embedded` | vendored AndroidX embedded player               | daemon                 |
 * | `cmp-android`       | CMP player (`rc-player-compose`) on Android     | daemon, by `playerId`  |
 * | `cmp-jvm`           | CMP player on the desktop JVM                   | server subprocess      |
 * | `cmp-wasm`          | CMP player compiled to Wasm                     | browser                |
 * | `camaelon-js`       | vendored TypeScript player                      | browser                |
 *
 * `cmp-android` used to name the AndroidX embedded player; it now names the CMP player on Android.
 */
export const RC_PLAYER = {
    androidxView: "androidx-view",
    androidxEmbedded: "androidx-embedded",
    cmpAndroid: "cmp-android",
    cmpJvm: "cmp-jvm",
    cmpWasm: "cmp-wasm",
    camaelonJs: "camaelon-js",
} as const;

/** The players drawn server-side, which name themselves on `/render` as `rcPlayer=<id>`. */
const SERVER_SIDE_PLAYERS: ReadonlySet<string> = new Set([
    RC_PLAYER.androidxView,
    RC_PLAYER.androidxEmbedded,
    RC_PLAYER.cmpAndroid,
    RC_PLAYER.cmpJvm,
]);

/**
 * The canonical id of a `?rcPlayer=` value, so a link written before the rename still opens on the
 * player it named: `java` / `view` → `androidx-view`, `embedded` → `androidx-embedded`, `js` →
 * `camaelon-js`, `rcplayer-jvm` / `rcplayer-wasm` → `cmp-jvm` / `cmp-wasm`. `cmp-android` is NOT
 * remapped here: in a request it means the CMP player on Android.
 */
export function normalizeRcPlayer(raw: string | null | undefined): string {
    const v = (raw || "").trim().toLowerCase();
    switch (v) {
        case "java":
        case "view":
            return RC_PLAYER.androidxView;
        case "embedded":
            return RC_PLAYER.androidxEmbedded;
        case "js":
            return RC_PLAYER.camaelonJs;
        case "rcplayer-jvm":
            return RC_PLAYER.cmpJvm;
        case "rcplayer-wasm":
            return RC_PLAYER.cmpWasm;
        default:
            return v;
    }
}

/**
 * The canonical id of the player a preview was CAPTURED with (`data-rc-baked-player`). Older
 * servers and daemons recorded the embedded player as `cmp-android` and the view player as `java`,
 * so here — and only here, never for a `?rcPlayer=` request — `cmp-android` means
 * `androidx-embedded`.
 */
export function normalizeBakedPlayer(raw: string | null | undefined): string {
    const v = (raw || "").trim().toLowerCase();
    return v === RC_PLAYER.cmpAndroid
        ? RC_PLAYER.androidxEmbedded
        : normalizeRcPlayer(v);
}

/**
 * Every lane that paints a RUNNING composition rather than a finished image.
 *
 * The daemon stream, the in-browser Wasm app, and both Remote Compose player lanes, which replay
 * the document client-side. This is what the status dot reports, so picking "Camaelon JS" from the combo
 * lights the same indicator clicking into Live does — they are the same claim.
 */
export function anyInteractive(lanes: LaneFlags): boolean {
    return lanes.live || lanes.wasm || lanes.rc || lanes.rcWasm;
}

/** Whether there is any live lane to enter at all. */
export function liveTransportAvailable(options: {
    daemon: boolean;
    wasm: boolean;
}): boolean {
    return options.daemon || options.wasm;
}

/**
 * The live lane the primary chip enters: the daemon stream when this session offers it, else the
 * in-browser Wasm app, else nothing.
 */
export function bestLiveMode(options: {
    daemon: boolean;
    wasm: boolean;
}): "live" | "wasm" | null {
    if (options.daemon) return "live";
    return options.wasm ? "wasm" : null;
}

export interface LanePick {
    /** The Remote Compose backend the server would use with no pick. */
    defaultBackend: string;
    /** The backend the visitor picked, if they have. */
    pickedBackend: string;
    picked: boolean;
}

/**
 * Whether a server-side RC lane must name its backend on `/render`.
 *
 * Every lane names itself EXCEPT the one a bare URL already produces: `bakedPlayer` is the player
 * the preview's baked artifact was drawn with, reported by the server as `data-rc-baked-player`
 * (`ServeHost.bakedRcPlayer`). Naming that lane is a parameter that changes nothing — it splits one
 * rendering across two cache entries and reads as a deliberate choice the visitor never made.
 *
 * This used to answer `true` for the embedded player unconditionally, on the reasoning that "the
 * server's absent-player default is the view player". That has not been true for some time, and
 * believing it cost a URL: the viewer seeds its pick state from this answer, so a first click from
 * a catalog stamped the embedded player onto `?rcPlayer=`.
 *
 * Fixing it by hardcoding `androidx-embedded` instead would only have moved the assumption. A
 * preview pinning `RemoteViewPreviewWrapper` bakes through the VIEW player, so on it
 * `androidx-embedded` is a genuine re-render that MUST keep naming itself while `androidx-view`
 * becomes the silent one — and the server agrees, because it decides from the same fact. An absent
 * or unrecognised `bakedPlayer` (a non–Remote Compose preview, an older server) names everything,
 * which is the conservative answer this started from. Both sides are compared as canonical ids, so
 * an older server's `cmp-android` / `java` report still matches.
 */
export function backendRequiresRenderParam(
    backend: string,
    bakedPlayer?: string | null,
): boolean {
    // A browser lane paints from the `.rc` bytes and never calls `/render`, so it names nothing
    // whatever baked. Only the server-side lanes are candidates at all.
    const id = normalizeRcPlayer(backend);
    if (!SERVER_SIDE_PLAYERS.has(id)) return false;
    if (!bakedPlayer) return true;
    return id !== normalizeBakedPlayer(bakedPlayer);
}

/** The server-side player parameter represented by a pick, or nothing for a browser lane. */
export function serverPlayerParam(
    backend: string,
    picked: boolean,
): string | null {
    if (!picked) return null;
    const id = normalizeRcPlayer(backend);
    return SERVER_SIDE_PLAYERS.has(id) ? id : null;
}

/** Restore the server-rendered default when returning from a browser-only player lane. */
export function restoreStaticPlayer(
    pick: LanePick,
    bakedPlayer?: string | null,
): LanePick {
    if (pick.picked) return pick;
    const retained = serverPlayerParam(pick.pickedBackend, true);
    if (retained) {
        return {
            defaultBackend: pick.defaultBackend,
            pickedBackend: retained,
            // A retained player needs an explicit parameter when it overrides the page default, or
            // when that default is itself a lane the bare URL does not already produce.
            picked:
                retained !== pick.defaultBackend ||
                backendRequiresRenderParam(pick.defaultBackend, bakedPlayer),
        };
    }
    return {
        defaultBackend: pick.defaultBackend,
        pickedBackend: pick.defaultBackend,
        picked: backendRequiresRenderParam(pick.defaultBackend, bakedPlayer),
    };
}

/**
 * The lane the picker is — or would be — sitting on, in the combo's own value space.
 *
 * A daemon stream is NOT one of the offered renderers; it is the live form of whichever one is
 * picked. So it deliberately falls through to the static player lane the toggle will return to,
 * which is what makes the chip's label survive entering and leaving Live.
 */
export function currentLaneValue(lanes: LaneFlags, pick: LanePick): string {
    if (lanes.rcWasm) return `rc:${RC_PLAYER.cmpWasm}`;
    if (lanes.rc) return `rc:${RC_PLAYER.camaelonJs}`;
    if (lanes.wasm) return "wasm";
    if (lanes.spec) return "spec";
    if (pick.defaultBackend)
        return `rc:${pick.picked ? pick.pickedBackend : pick.defaultBackend}`;
    return "png";
}

/**
 * What the primary chip calls the current lane.
 *
 * "Live" while the daemon stream is up — that lane IS the live form of whichever renderer is
 * picked, and the picked one is a click away again. Otherwise the matching combo option's own
 * label, so the chip and the combo can never name a lane two different things.
 *
 * On the spec lane there is no matching option, and that is deliberate: the spec chip beside this
 * one is lit and already names it, and two adjacent chips both reading "Figma" would be two
 * controls arguing about the same fact. So this one keeps naming the render lane, which is exactly
 * where clicking it goes back to.
 */
export function laneLabelText(options: {
    live: boolean;
    /** The combo's options as value→label, or `null` on a preview with no combo. */
    laneOptions: ReadonlyMap<string, string> | null;
    wanted: string;
    defaultLabel: string;
}): string {
    if (options.live) return "Live";
    if (!options.laneOptions) return options.defaultLabel;
    return options.laneOptions.get(options.wanted) || options.defaultLabel;
}

/**
 * Whether the viewer should be OFFERING the live lane right now.
 *
 * One predicate behind three affordances — the chip's "▸ Live" verb, the hint badge on the stage,
 * and the click handler on the snapshot itself — so they cannot disagree about whether a click on
 * the picture does anything. A hint over a stage whose click is inert is worse than no hint.
 *
 * `mode` is the viewer's own mode value, and `"png"` is the only one that qualifies: the fixed-frame
 * lanes (the imported spec, the usage source, a recorded motion clip) put something on the stage
 * that is not this preview's render, and clicking through from one of those to a live session would
 * silently discard what the visitor asked to look at.
 */
export function liveInviteAvailable(options: {
    interactive: boolean;
    transport: boolean;
    mode: string;
}): boolean {
    return !options.interactive && options.transport && options.mode === "png";
}

export interface ChipState {
    pressed: boolean;
    disabled: boolean;
}

/**
 * A lane chip's state.
 *
 * Enabled when there is a lane to enter — OR when its lane is already on the stage, which is the
 * only way back OUT of it. A chip that disabled itself on entry would strand the visitor there.
 */
export function laneChip(options: {
    onLane: boolean;
    available: boolean;
}): ChipState {
    return {
        pressed: options.onLane,
        disabled: !options.available && !options.onLane,
    };
}
