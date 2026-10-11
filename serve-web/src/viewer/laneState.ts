// Which lane the viewer is on, what it's called, and what each chip reports. The primary chip names
// the renderer on stage and its dot says whether it's interactive; the spec and source chips report
// their own lanes. Every route out of a lane must un-press every chip, or a chip stays lit over a
// lane that isn't showing. DOM-free: `viewer.js` passes booleans.

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
 * | id                  | implementation                                    | where                 |
 * | ------------------- | ------------------------------------------------- | --------------------- |
 * | `androidx-view`     | AndroidX `remote-player-view` RemoteComposePlayer | daemon                |
 * | `androidx-embedded` | vendored AndroidX embedded player                 | daemon                |
 * | `cmp-android`       | CMP player (`rc-player-compose`) on Android       | daemon, by `playerId` |
 * | `cmp-jvm`           | CMP player on the desktop JVM                     | server subprocess     |
 * | `cmp-wasm`          | CMP player compiled to Wasm                       | browser               |
 * | `camaelon-js`       | vendored TypeScript player                        | browser               |
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
 * The canonical id of a `?rcPlayer=` value, mapping legacy spellings: `java` / `view` →
 * `androidx-view`, `embedded` → `androidx-embedded`, `js` → `camaelon-js`, `rcplayer-jvm` /
 * `rcplayer-wasm` → `cmp-jvm` / `cmp-wasm`. `cmp-android` is not remapped in a request.
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
 * The canonical id of the player a preview was captured with (`data-rc-baked-player`). Older
 * recordings used `cmp-android` for the embedded player and `java` for the view player, so only
 * here does `cmp-android` mean `androidx-embedded`.
 */
export function normalizeBakedPlayer(raw: string | null | undefined): string {
    const v = (raw || "").trim().toLowerCase();
    return v === RC_PLAYER.cmpAndroid
        ? RC_PLAYER.androidxEmbedded
        : normalizeRcPlayer(v);
}

/**
 * Lanes painting a running composition (daemon stream, Wasm app, both RC player lanes); what the
 * status dot reports.
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
 * The live lane the primary chip enters: the daemon stream if offered, else the Wasm app, else
 * nothing.
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
 * Whether a server-side RC lane must name its backend on `/render`: every lane except the one a
 * bare URL already produces, `bakedPlayer` (`data-rc-baked-player`, from
 * `ServeHost.bakedRcPlayer`). Naming that one would split the cache and read as a choice the
 * visitor never made. It isn't hardcoded: a preview pinning `RemoteViewPreviewWrapper` bakes
 * through the view player, so there `androidx-embedded` must name itself. An absent or unknown
 * `bakedPlayer` names everything. Compared as canonical ids.
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
 * The lane the picker is (or would be) on, in the combo's value space. The daemon stream is the
 * live form of the picked renderer, so it falls through to the static player lane, keeping the
 * chip's label across Live.
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
 * What the primary chip calls the current lane: "Live" while streaming, otherwise the matching
 * combo option's label. On the spec lane it keeps naming the render lane (the lit spec chip already
 * names the spec), which is where clicking it returns.
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
 * Whether to offer the live lane now: one predicate behind the chip's "▸ Live" verb, the stage hint
 * and the snapshot click handler. Only `mode === "png"` qualifies; fixed-frame lanes (spec, source,
 * motion) show something else, which clicking through would discard.
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
 * A lane chip's state: enabled when there is a lane to enter, or when its lane is on stage (the
 * only way back out).
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
