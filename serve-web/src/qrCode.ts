// Entry point for the QR bundle (`assets/qr-code.js`).
//
// Its own bundle rather than part of `viewer.js`: the viewer is at its gzip budget, and only the
// visits that open the export bar's "On phone" popover need an encoder. `viewer/phoneQr.ts` injects
// this script on that button's first click and hands the button to the API left on `window.cpQr`.

import { mountPhoneQr } from "./qr/phonePopover.js";
import type { QrApi } from "./viewer/phoneQr.js";

const api: QrApi = { mount: mountPhoneQr };
(window as { cpQr?: QrApi }).cpQr = api;
