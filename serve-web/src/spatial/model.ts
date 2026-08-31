export interface SpatialVector3 {
    x: number;
    y: number;
    z: number;
}

export interface SpatialQuaternion extends SpatialVector3 {
    w: number;
}

export interface SpatialPanel {
    id: string;
    label?: string;
    poseInRoot: {
        translation: SpatialVector3;
        rotation: SpatialQuaternion;
    };
    sizeDp: { width: number; height: number };
    texture: string;
}

export interface SpatialSceneDocument {
    version: 1;
    units: "dp";
    camera: {
        kind: "orbit";
        target: SpatialVector3;
        distance: number;
        yawDeg: number;
        pitchDeg: number;
    };
    panels: SpatialPanel[];
    orbiters?: SpatialPanel[];
    environment?: {
        kind?: string;
        color?: string;
        sky?: string;
        horizon?: string;
        floor?: string;
        preset?: string;
    };
}

const finite = (value: unknown): value is number =>
    typeof value === "number" && Number.isFinite(value);

const vector = (value: unknown): value is SpatialVector3 => {
    const item = value as Partial<SpatialVector3> | null;
    return !!item && finite(item.x) && finite(item.y) && finite(item.z);
};

const panel = (value: unknown): value is SpatialPanel => {
    const item = value as Partial<SpatialPanel> | null;
    return (
        !!item &&
        typeof item.id === "string" &&
        typeof item.texture === "string" &&
        !!item.sizeDp &&
        finite(item.sizeDp.width) &&
        item.sizeDp.width > 0 &&
        finite(item.sizeDp.height) &&
        item.sizeDp.height > 0 &&
        !!item.poseInRoot &&
        vector(item.poseInRoot.translation) &&
        vector(item.poseInRoot.rotation) &&
        finite(item.poseInRoot.rotation.w)
    );
};

export function parseSpatialScene(value: unknown): SpatialSceneDocument {
    const scene = value as Partial<SpatialSceneDocument> | null;
    if (
        !scene ||
        scene.version !== 1 ||
        scene.units !== "dp" ||
        !scene.camera ||
        scene.camera.kind !== "orbit" ||
        !vector(scene.camera.target) ||
        !finite(scene.camera.distance) ||
        scene.camera.distance <= 0 ||
        !finite(scene.camera.yawDeg) ||
        !finite(scene.camera.pitchDeg) ||
        !Array.isArray(scene.panels) ||
        !scene.panels.every(panel) ||
        (scene.orbiters !== undefined &&
            (!Array.isArray(scene.orbiters) || !scene.orbiters.every(panel)))
    ) {
        throw new Error("Unsupported or malformed spatial scene");
    }
    return scene as SpatialSceneDocument;
}

/** Resolve a texture while preserving the scene's auth/session query and containing it to its dir. */
export function spatialTextureUrl(sceneUrl: string, texture: string): string {
    if (
        !texture ||
        texture.includes("\\") ||
        texture.startsWith("/") ||
        texture.split("/").some((part) => part === "." || part === "..")
    ) {
        throw new Error("Spatial texture must be a contained relative path");
    }
    const scene = new URL(sceneUrl, window.location.href);
    const basePath = scene.pathname.slice(
        0,
        scene.pathname.lastIndexOf("/") + 1,
    );
    const resolved = new URL(texture, scene);
    if (
        resolved.origin !== scene.origin ||
        !resolved.pathname.startsWith(basePath)
    ) {
        throw new Error("Spatial texture escaped its scene directory");
    }
    resolved.search = scene.search;
    return resolved.href;
}
