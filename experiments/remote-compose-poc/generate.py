"""Disposable, strict semantic model -> AndroidX authoring JSON and compilable Compose.

This is a feasibility fixture, not a proposed production contract.
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).parent

def lower(model, density=1):
    if not isinstance(density, (int, float)) or not 0.5 <= density <= 4:
        raise ValueError("environment.density: expected a number in 0.5..4")
    density = float(density)
    allowed = {"schema", "title", "width", "height", "padding", "state", "onClick", "repeat", "component"}
    if set(model) != allowed:
        raise ValueError("document: unexpected or missing fields")
    if model["schema"] != "poc.interface/v1":
        raise ValueError("schema: expected poc.interface/v1")
    if model["state"] != {"name": "mode", "type": "int", "initial": 0, "cases": ["Off", "On"]}:
        raise ValueError("state: this proof supports exactly the two declared mode cases")
    if model["onClick"] != [{"type": "toggle", "state": "mode"}, {"type": "host", "name": "lighting.changed"}]:
        raise ValueError("onClick: expected ordered state update and host callback")
    for key in ["width", "height", "padding"]:
        if type(model[key]) is not int or not 0 <= model[key] <= 1000:
            raise ValueError(f"{key}: expected an integer in 0..1000")
    if model["width"] != 320 or model["height"] != 260:
        raise ValueError("environment: this proof has a fixed 320 x 260 dp frame")
    if not 0 <= model["padding"] <= 32:
        raise ValueError("padding: expected an integer in 0..32")
    if not isinstance(model["repeat"], dict) or set(model["repeat"]) != {"count"}:
        raise ValueError("repeat: expected only count")
    if not isinstance(model["component"], dict) or set(model["component"]) != {"name", "labels"}:
        raise ValueError("component: expected name and labels")
    count = model["repeat"]["count"]
    if type(count) is not int or not 1 <= count <= 12:
        raise ValueError("repeat.count: expected an integer in 1..12")
    if not isinstance(model["title"], str):
        raise ValueError("title: expected a string")
    labels = model["component"]["labels"]
    if model["component"]["name"] != "Badge" or len(labels) != 2 or not all(isinstance(x, str) for x in labels):
        raise ValueError("component: expected Badge with two string labels")
    def text(value, size=18, color="#FF17212B"):
        return {"type": "text", "value": value, "fontSize": size * density, "color": color}
    def spacer(height):
        return {"type": "box", "horizontalAlignment": "start", "verticalAlignment": "top", "modifiers": [{"height": height * density}, {"width": density}], "children": [text("")]}
    children = [text(model["title"], 24), spacer(16), {
        "type": "stateLayout", "indexId": "@mode",
        "modifiers": [{"width": 288 * density}, {"height": 72 * density}, {"onClick": [
            {"type": "valueIntegerExpressionChange", "target": "@mode", "expression": "1 - @mode"},
            {"type": "hostNamedAction", "name": "lighting.changed"}
        ]}],
        "children": [{"type": "box", "modifiers": ["fillMaxSize", {"background": color}, {"padding": 16 * density}],
                      "horizontalAlignment": "start", "verticalAlignment": "top", "children": [text(label, 24, "#FFFFFFFF")]}
                     for label, color in [("Off", "#FF455A64"), ("On", "#FF006C4C")]]
    }, spacer(12), {
        "type": "canvas", "modifiers": [{"width": 288 * density}, {"height": 28 * density}],
        "commands": [{"type": "setColor", "color": "#FF006C4C"},
                     {"type": "loop", "index": "i", "from": 0, "until": count, "step": 1,
                      "commands": [{"type": "drawCircle", "cx": f"(12 + @i * 24) * {density}", "cy": 12 * density, "radius": 8 * density}]}]
    }, spacer(8), {"type": "row", "children": [{"type": "box", "horizontalAlignment": "start", "verticalAlignment": "top", "modifiers": [{"width": 138 * density}, {"height": 36 * density}, {"background": "#FFE0E9E4"}, {"padding": 8 * density}], "children": [text(label, 16)]} for label in labels]}]
    return {"header": {"width": round(model["width"] * density), "height": round(model["height"] * density), "apiLevel": 7, "profiles": 513},
            "root": [{"type": "resources", "integers": {"mode": {"value": 0, "export": True}}},
                     {"type": "column", "modifiers": ["fillMaxSize", {"background": "#FFFFFFFF"}, {"padding": model["padding"] * density}], "children": children}]}

def kotlin_string(value):
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$")

def compose_source(m):
    source = (ROOT / "Interface.kt.template").read_text()
    values = {"TITLE": kotlin_string(m["title"]), "PADDING": str(m["padding"]),
              "COUNT": str(m["repeat"]["count"]),
              "LABEL0": kotlin_string(m["component"]["labels"][0]),
              "LABEL1": kotlin_string(m["component"]["labels"][1])}
    return re.sub(r"@@(\w+)@@", lambda match: values[match.group(1)], source)


if __name__ == "__main__":
    model = json.loads((ROOT / "model.json").read_text())
    (ROOT / "fixture.remote.json").write_text(json.dumps(lower(model), indent=2) + "\n")
    (ROOT / "fixture-density2.remote.json").write_text(json.dumps(lower(model, 2), indent=2) + "\n")
    output = ROOT / "src/commonMain/kotlin/poc/GeneratedInterface.kt"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(compose_source(model))
    print("Generated AndroidX authoring JSON and regular Compose source from the same model")
