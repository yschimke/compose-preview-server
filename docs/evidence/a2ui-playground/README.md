# A2UI playground evidence

- `playground.png`: `GET /a2ui-catalog/a2ui` from the server under test, with the textarea holding
  a2ui-catalog's default playground document and the output pane showing that document's real
  render (a2ui-catalog `docs/evidence/playground.png`, drawn by `material3-a2ui` through the live
  lane). The page was captured from a file dump, so the image was placed into `#a2ui-image` the way
  a successful `POST /a2ui-catalog/render/…` places it.
- `viewer-long-knob.png`: the viewer's Overrides panel for the same preview, where a string knob
  whose default is multi-line now edits in a `<textarea>` instead of a single-line input (a
  help pop-over is cropped off the top).
