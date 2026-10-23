// Components used by catalog grids and component-browser catalog pages.
import "./components/BgToggle.js";
import "./components/CatalogLive.js";
import "./components/CatalogToolbar.js";
import { whenParsed } from "./dom/whenParsed.js";
import { installGridDensity } from "./components/GridDensity.js";
import { installWearPanes } from "./components/WearPanes.js";

// The S / M / L thumbnail size. Not a server-emitted element: the control means nothing without
// JavaScript, so the bundle adds it. Synchronously when it can — the served script tag sits after
// the grid, so the remembered size lands before first paint instead of the M grid flashing first —
// and otherwise once the toolbar row it joins has been parsed.
if (!installGridDensity()) void whenParsed().then(installGridDensity);

// The opt-in Wear panes beside Transparent in the `⋯` panel: after the parse, because it lays the
// panes into every card of the grid.
void whenParsed().then(installWearPanes);
