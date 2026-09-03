// Shared test helpers, imported first by every test file.
//
// `test/preload.ts` installs happy-dom and the shared Vue runtime before Mocha links any test.
// Custom-element registration then has `window`/`document`/`customElements` at module evaluation
// time, while this module remains the common reset/flush/storage utility.
//
// Same registrator and version as yschimke/compose-preview-vscode, so the two test suites
// can't drift onto different DOM semantics.

/**
 * Reset the document, the `<html>` classes and the real per-tab storage between tests.
 *
 * `sessionStorage` is cleared because it now carries the theme choice (`chrome/themeMemory.ts`) and
 * happy-dom keeps one instance for the whole process: a test that picks Dark would otherwise hand
 * Dark to every test that ran after it, in file order, which is precisely the cross-contamination
 * moving the theme off `localStorage` exists to stop.
 */
export function resetDom(): void {
    document.documentElement.className = "";
    document.body.innerHTML = "";
    // Only the REAL one. A suite holding a {@link stubStorage} owns its own lifetime — the drawer
    // tests reset the DOM mid-test precisely to prove a remembered choice survives a remount — and
    // clearing that here would erase what the test is about.
    if (!(sessionStorage as { cpStub?: boolean }).cpStub) {
        try {
            sessionStorage.clear();
        } catch {
            // Storage unavailable in this environment: nothing to clear.
        }
    }
}

/**
 * A minimal in-memory stand-in for BOTH web storages, with a throwing variant.
 *
 * Both, because a page's storage access is not split by which key it is reaching for: the Page
 * theme setting is `localStorage` while the theme choice beside it is `sessionStorage`, and a test
 * asserting "the pick was remembered" or "a blocked store is survivable" means the same thing
 * either way. `get` reads the one shared backing map, so a caller need not say which it meant.
 */
export function stubStorage(throwOnWrite = false): {
    get(key: string): string | null;
} {
    const store = new Map<string, string>();
    const stub = {
        /** Marks this as a test double, so {@link resetDom} leaves its contents to its owner. */
        cpStub: true,
        getItem: (key: string) => store.get(key) ?? null,
        setItem: (key: string, value: string) => {
            if (throwOnWrite) throw new Error("storage blocked");
            store.set(key, value);
        },
        removeItem: (key: string) => void store.delete(key),
        clear: () => store.clear(),
    };
    for (const name of ["localStorage", "sessionStorage"]) {
        Object.defineProperty(globalThis, name, {
            configurable: true,
            value: stub,
        });
    }
    return { get: (key) => store.get(key) ?? null };
}

/** Wait for Vue updates and queued browser work to flush. */
export async function flush(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, 0));
}
