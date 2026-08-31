import "./setup.js";
import assert from "node:assert/strict";
import { parseSpatialScene, spatialTextureUrl } from "../src/spatial/model.js";

const fixture = {
    version: 1,
    units: "dp",
    camera: {
        kind: "orbit",
        target: { x: 0, y: -10, z: 0 },
        distance: 1200,
        yawDeg: 0,
        pitchDeg: -10,
    },
    panels: [
        {
            id: "top",
            poseInRoot: {
                translation: { x: 0, y: 80, z: 0 },
                rotation: { x: 0, y: 0, z: 0, w: 1 },
            },
            sizeDp: { width: 560, height: 200 },
            texture: "top.png",
        },
    ],
};

describe("spatial scene model", () => {
    it("accepts the version-one dp contract", () => {
        assert.equal(parseSpatialScene(fixture).panels[0]?.id, "top");
    });

    it("rejects future versions and invalid dimensions", () => {
        assert.throws(() => parseSpatialScene({ ...fixture, version: 2 }));
        assert.throws(() =>
            parseSpatialScene({
                ...fixture,
                panels: [
                    {
                        ...fixture.panels[0],
                        sizeDp: { width: 0, height: 200 },
                    },
                ],
            }),
        );
    });

    it("keeps textures under the scene route and preserves its access query", () => {
        assert.equal(
            spatialTextureUrl(
                "/system/spatial/example/scene.json?token=secret&generation=7",
                "top.png",
            ),
            "https://preview.example/system/spatial/example/top.png?token=secret&generation=7",
        );
        assert.throws(() =>
            spatialTextureUrl(
                "/system/spatial/example/scene.json",
                "../secret.png",
            ),
        );
        assert.throws(() =>
            spatialTextureUrl(
                "/system/spatial/example/scene.json",
                "https://tracker.invalid/pixel.png",
            ),
        );
    });
});
