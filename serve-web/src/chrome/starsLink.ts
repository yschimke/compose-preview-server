// Share stars — a link that hands someone else your Starred row, and the prompt that takes it in.
//
// Stars are per-visitor (`stars.ts`), which is right for a shortlist and wrong the moment it is the
// thing you want to hand on: "these are the five chips we are reviewing" had no better channel than
// five links pasted one by one. The Starred row's Share action copies ONE link that carries the set,
// and a visitor who opens it is asked — never told — whether to add them to their own.
//
// The set rides the URL FRAGMENT (`#stars=…`), never the query: a browser does not send a fragment
// to the server, so a shared shortlist never reaches a request line, an access log or a referrer,
// and the page the link opens is byte for byte the page anyone else gets. Only an explicit Share
// writes one; an ordinary copied address still carries nothing.
//
// The fragment is untrusted input from whoever made the link, so decoding is strict and bounded:
// anything malformed is ignored rather than half-applied, every list is capped, and an accepted link
// only ever ADDS to the visitor's stars — it cannot unstar or reorder what they already chose.

/** The fragment key. `#stars=c:a,b;p:x,y` — `c` catalogs, `p` previews of the linked page's catalog. */
export const STARS_FRAGMENT = "stars";

/** A link carries at most this many ids per list; the rest are dropped, not rejected. */
export const MAX_SHARED_IDS = 50;

/** Ids are path segments and preview ids, neither of which is anywhere near this long. */
const MAX_ID_LENGTH = 200;

/**
 * The longest `stars=` value either side accepts. The decoder ignores anything longer unparsed, so
 * the encoder trims to fit it ({@link fitSharedStars}): every link Share writes must open.
 */
export const MAX_FRAGMENT_LENGTH = 8_000;

/** What a link shares: catalog ids, and preview ids belonging to the page the link opens. */
export interface SharedStars {
    catalogs: string[];
    previews: string[];
}

// A control character: no catalog path or preview id has one, and a label that rendered one would lie.
const BAD_ID = /[\u0000-\u001f\u007f]/;

function validIds(list: readonly string[]): string[] {
    const out: string[] = [];
    for (const id of list) {
        if (out.length >= MAX_SHARED_IDS) break;
        if (!id || id.length > MAX_ID_LENGTH || BAD_ID.test(id)) continue;
        if (!out.includes(id)) out.push(id);
    }
    return out;
}

function join(stars: SharedStars): string {
    const groups: string[] = [];
    if (stars.catalogs.length)
        groups.push(`c:${stars.catalogs.map(encodeURIComponent).join(",")}`);
    if (stars.previews.length)
        groups.push(`p:${stars.previews.map(encodeURIComponent).join(",")}`);
    return groups.join(";");
}

/**
 * What of [stars] a link can actually carry: valid ids, at most {@link MAX_SHARED_IDS} per list,
 * and no more than fits in {@link MAX_FRAGMENT_LENGTH} once encoded. Lists are newest-first, so a
 * trim drops the OLDEST stars — the link shares the N most recent — and never yields a value the
 * decoder would throw away whole. (A percent-encoded non-ASCII id can be nine times its length, so
 * the id caps alone do not bound the value.)
 */
export function fitSharedStars(stars: SharedStars): SharedStars {
    const out: SharedStars = { catalogs: [], previews: [] };
    for (const key of ["catalogs", "previews"] as const)
        for (const id of validIds(stars[key])) {
            out[key].push(id);
            if (join(out).length > MAX_FRAGMENT_LENGTH) {
                out[key].pop();
                break;
            }
        }
    return out;
}

/**
 * The fragment value for [stars], without the leading `#stars=`; `""` when there is nothing to
 * share. Each id is percent-encoded, so the `,` `:` `;` that structure the value never collide
 * with one, and the result stays readable for the ordinary ids catalogs actually use. Always
 * within {@link MAX_FRAGMENT_LENGTH}: see {@link fitSharedStars}.
 */
export function encodeSharedStars(stars: SharedStars): string {
    return join(fitSharedStars(stars));
}

/**
 * The stars a fragment value carries, or `null` when it is malformed or carries none. A group this
 * version does not know is skipped, so a later format can add one without old pages refusing the
 * whole link; a group that is present but broken (bad escape, empty id list) fails the link, since
 * a half-read shortlist would be worse than none.
 */
export function decodeSharedStars(value: string): SharedStars | null {
    if (!value || value.length > MAX_FRAGMENT_LENGTH) return null;
    const out: SharedStars = { catalogs: [], previews: [] };
    for (const group of value.split(";")) {
        const colon = group.indexOf(":");
        if (colon < 1) return null;
        const kind = group.slice(0, colon);
        const raw = group.slice(colon + 1).split(",");
        let ids: string[];
        try {
            ids = raw.map(decodeURIComponent);
        } catch {
            return null;
        }
        if (ids.some((id) => !id)) return null;
        if (kind === "c") out.catalogs.push(...ids);
        else if (kind === "p") out.previews.push(...ids);
    }
    out.catalogs = validIds(out.catalogs);
    out.previews = validIds(out.previews);
    return out.catalogs.length || out.previews.length ? out : null;
}

/** The `stars=` value in a `location.hash`, or `null` when the fragment is something else. */
export function sharedStarsValue(hash: string): string | null {
    const body = hash.startsWith("#") ? hash.slice(1) : hash;
    const prefix = `${STARS_FRAGMENT}=`;
    return body.startsWith(prefix) ? body.slice(prefix.length) : null;
}

/**
 * The shareable address of this page carrying [stars]: its own path and query, a fresh fragment.
 * Minus any `token`: that is the visitor's own browse credential, and a link made to hand on must
 * not hand it on too (`session` and the page's own state params stay, they say which page this is).
 */
export function sharedStarsUrl(
    stars: SharedStars,
    loc: Location = location,
): string {
    const value = encodeSharedStars(stars);
    const query = new URLSearchParams(loc.search);
    query.delete("token");
    const search = query.toString();
    const base = `${loc.origin}${loc.pathname}${search ? `?${search}` : ""}`;
    return value ? `${base}#${STARS_FRAGMENT}=${value}` : base;
}

/**
 * Drop the fragment from the address bar without navigating, so a reload (or the visitor copying
 * the address on) does not offer the same stars again.
 */
export function clearSharedStarsFragment(): void {
    if (sharedStarsValue(location.hash) === null) return;
    try {
        history.replaceState(
            history.state,
            "",
            location.pathname + location.search,
        );
    } catch {
        // An opaque origin throws here; the stale fragment is harmless.
    }
}

const plural = (n: number, one: string): string =>
    `${n} starred ${one}${n === 1 ? "" : "s"}`;

/** "3 starred previews", "1 starred catalog", or "4 starred items" for a mix. */
export function sharedStarsNoun(stars: SharedStars): string {
    const c = stars.catalogs.length;
    const p = stars.previews.length;
    if (c && p) return plural(c + p, "item");
    return c ? plural(c, "catalog") : plural(p, "preview");
}

/**
 * The small, dismissible offer a shared link raises: "Add N starred … from this link?" with Add and
 * Dismiss. Not a modal — the page underneath stays usable and nothing is added until Add — and it
 * takes no focus on arrival, only announcing itself politely; Escape inside it dismisses. Either
 * button clears the fragment and removes the offer.
 */
export function showSharedStarsOffer(
    stars: SharedStars,
    accept: () => void,
): HTMLElement {
    document.getElementById("cp-stars-offer")?.remove();
    const offer = document.createElement("div");
    offer.id = "cp-stars-offer";
    offer.className = "cp-stars-offer";
    offer.setAttribute("role", "region");
    offer.setAttribute("aria-label", "Starred items from a link");
    const text = document.createElement("p");
    text.className = "cp-stars-offer-text";
    text.setAttribute("aria-live", "polite");
    text.textContent = `Add ${sharedStarsNoun(stars)} from this link?`;
    const dismiss = document.createElement("button");
    dismiss.type = "button";
    dismiss.className = "cp-stars-offer-dismiss";
    dismiss.textContent = "Dismiss";
    const add = document.createElement("button");
    add.type = "button";
    add.className = "cp-stars-offer-add";
    add.textContent = "Add";
    const close = (): void => {
        offer.remove();
        clearSharedStarsFragment();
    };
    dismiss.addEventListener("click", close);
    add.addEventListener("click", () => {
        accept();
        close();
    });
    offer.addEventListener("keydown", (event) => {
        if (event.key === "Escape") close();
    });
    offer.append(text, dismiss, add);
    document.body.append(offer);
    return offer;
}
