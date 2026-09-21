import { a11yEntries, type Entry } from "../inspect/entries.js";
import { matchAnnotationItems, type AnnotationItem } from "./match.js";

export interface A11yDifference {
    marker: string;
    reference?: AnnotationItem;
    actual?: AnnotationItem;
    fields: string[];
}

/** Turn the hierarchy's screen-reader stops into the common, geometry-aware pairing shape. */
export function a11yComparisonItems(payload: unknown): AnnotationItem[] {
    return a11yEntries(payload as never).map((entry: Entry) => ({
        kind: "a11y",
        bounds: entry.bounds,
        label: entry.title,
        // The first detail segment is the role when the hierarchy supplied one. The remainder
        // carries states, target size, and findings and is compared as one semantic description.
        role: entry.detail.split(" · ")[0] || "",
        detail: { summary: entry.detail, level: entry.level },
    }));
}

function value(item: AnnotationItem | undefined, field: string): string {
    if (!item) return "—";
    if (field === "label") return String(item.label ?? "");
    if (field === "role") return String(item.role ?? "");
    if (field === "semantics") return String(item.detail?.summary ?? "");
    return String(item.detail?.level ?? "info");
}

/**
 * Pair focus stops on the two rendered frames and retain missing stops as meaningful differences.
 */
export function a11yDifferences(
    referencePayload: unknown,
    actualPayload: unknown,
): A11yDifference[] {
    const matched = matchAnnotationItems(
        a11yComparisonItems(referencePayload),
        a11yComparisonItems(actualPayload),
    );
    const reference = new Map(
        matched.reference.map((item) => [item.comparisonOrdinal, item]),
    );
    const actual = new Map(
        matched.actual.map((item) => [item.comparisonOrdinal, item]),
    );
    const ordinals = Array.from(
        new Set([...reference.keys(), ...actual.keys()]),
    ).sort((a, b) => (a ?? 0) - (b ?? 0));
    return ordinals.flatMap((ordinal) => {
        const left = reference.get(ordinal);
        const right = actual.get(ordinal);
        const fields =
            !left || !right
                ? [left ? "missing actual stop" : "missing reference stop"]
                : (["label", "role", "semantics", "severity"] as const).filter(
                      (field) => value(left, field) !== value(right, field),
                  );
        return fields.length
            ? [
                  {
                      marker: String(ordinal),
                      reference: left,
                      actual: right,
                      fields,
                  },
              ]
            : [];
    });
}

export function a11yDifferenceValue(
    difference: A11yDifference,
    side: "reference" | "actual",
    field: string,
): string {
    return value(difference[side], field);
}
