// The "Surprise me" pick, kept free of the DOM so it can be tested with a list and a seeded rng.
//
// The one rule beyond "uniformly at random": never the same preview twice in a row. With a
// handful of cards on screen a plain random pick repeats often enough to feel broken — press the
// die, land on the preview you just came back from — so the previous pick is set aside whenever
// there is anything else to choose. With exactly one candidate it is still the answer: the button
// should go somewhere, and "the only preview showing" is where.

/**
 * Picks one of [items] at random, skipping the one whose [key] equals [previous] when any other
 * remains. [rng] returns a number in `[0, 1)`, as `Math.random` does. Null only for an empty list.
 */
export function pickSurprise<T>(
    items: readonly T[],
    key: (item: T) => string,
    previous: string | null,
    rng: () => number = Math.random,
): T | null {
    const fresh =
        previous === null
            ? items
            : items.filter((item) => key(item) !== previous);
    const pool = fresh.length ? fresh : items;
    if (!pool.length) return null;
    // Clamped, so an rng that returns exactly 1 (a badly behaved stub) cannot index past the end.
    const index = Math.min(pool.length - 1, Math.floor(rng() * pool.length));
    return pool[index] ?? null;
}
