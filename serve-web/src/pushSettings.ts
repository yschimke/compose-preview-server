// Entry point for `push-settings.js`: the Settings menu's Notifications group. Emitted only beside
// a signed-in session on a host that sends Web Push, so no other page pays for it.

import { whenReady } from "./dom/whenReady.js";
import { installPushSettings } from "./push/settings.js";

whenReady(() => {
    void installPushSettings();
});
