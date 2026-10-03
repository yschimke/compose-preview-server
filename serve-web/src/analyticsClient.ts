export function trackInteraction(
    name: string,
    properties: Record<string, unknown> = {},
): void {
    // Optional host bridge: disabled deployments and blocked scripts are ordinary operation.
    try {
        window.previewTelemetry?.track(name, JSON.stringify(properties));
    } catch {
        /* Analytics must never interrupt an action. */
    }
}

declare global {
    interface Window {
        previewTelemetry?: {
            track(name: string, propertiesJson: string): void;
        };
    }
}
