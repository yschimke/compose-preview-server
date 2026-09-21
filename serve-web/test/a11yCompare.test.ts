import assert from "node:assert/strict";
import { describe, it } from "mocha";
import {
    a11yDifferences,
    a11yDifferenceValue,
} from "../src/annotate/a11yCompare.js";

const payload = (label: string, role = "Button", findings: string[] = []) => ({
    nodes: [
        {
            label,
            role,
            boundsInScreen: "0,0,48,48",
            states: ["clickable"],
        },
    ],
    findings: findings.map((message) => ({
        boundsInScreen: "0,0,48,48",
        type: "TouchTargetSizeCheck",
        message,
        level: "WARNING",
    })),
    touchTargets: [],
});

describe("a11yDifferences", () => {
    it("compares parallel focus stops by their semantics", () => {
        const differences = a11yDifferences(
            payload("Save"),
            payload("Save changes", "Button", ["Too small"]),
        );

        assert.deepEqual(differences[0].fields, [
            "label",
            "semantics",
            "severity",
        ]);
        assert.equal(
            a11yDifferenceValue(differences[0], "reference", "label"),
            "Save",
        );
        assert.equal(
            a11yDifferenceValue(differences[0], "actual", "severity"),
            "warning",
        );
    });

    it("retains a focus stop that exists on only one parallel render", () => {
        const differences = a11yDifferences(payload("Save"), {
            nodes: [],
            findings: [],
            touchTargets: [],
        });

        assert.deepEqual(differences[0].fields, ["missing actual stop"]);
    });

    it("retains every one-sided focus stop with its own marker", () => {
        const differences = a11yDifferences(
            {
                nodes: [
                    {
                        label: "Save",
                        role: "Button",
                        boundsInScreen: "0,0,48,48",
                    },
                    {
                        label: "Cancel",
                        role: "Button",
                        boundsInScreen: "48,0,96,48",
                    },
                ],
                findings: [],
                touchTargets: [],
            },
            { nodes: [], findings: [], touchTargets: [] },
        );

        assert.deepEqual(
            differences.map(({ marker, fields }) => ({ marker, fields })),
            [
                { marker: "1", fields: ["missing actual stop"] },
                { marker: "2", fields: ["missing actual stop"] },
            ],
        );
    });
});
