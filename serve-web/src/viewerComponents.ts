// Components and shared globals used by the preview viewer.
import "./components/BackendBadge.js";
import "./components/BgToggle.js";
import "./components/ElementSelection.js";
import "./components/GroupMemory.js";
import "./components/HistoryMenu.js";
import "./components/InspectLayers.js";
import "./components/RevisionRuns.js";
import "./components/SpecCompare.js";
import "./components/ViewerDrawers.js";
import "./rcFonts.js";
import { whenParsed } from "./dom/whenParsed.js";
import { installWearPanes } from "./components/WearPanes.js";

// The opt-in Wear panes: a chip beside Transparent in the View group, the panes under the render.
void whenParsed().then(installWearPanes);
