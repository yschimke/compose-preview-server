// Appended to the Remote Compose player bundle at build time (`stageRcPlayerJs`), after the bundle
// has defined `window.RcdPlayer` / `window.RC`.
//
// The player installs a `WebCustomHost` into every document it loads. That host turns the
// layout-custom operation (opcode 93) into live browser capabilities: a `camera:` config calls
// `navigator.mediaDevices.getUserMedia`, and an embed path is fetched from the page's own origin.
// This server plays documents it did not write — anonymously uploaded `.rc` files on `/d/<id>`,
// catalog captures in the viewer — so a document must never be able to ask for the camera or make
// requests just by being opened. Every player gets an inert host instead: a custom component paints
// as an empty box, exactly as it did before hosts existed.
//
// Done on the class's prototype rather than by swapping `RC.RcdPlayer`: `RC` is the bundle's
// module object (getter-only properties), and `createPlayer` / `<rc-player>` construct the original
// class directly. The constructor's `this.customHost = new WebCustomHost()` is a plain assignment,
// so this accessor's setter swallows it on every construction path.
(function () {
  "use strict";
  var Player = window.RcdPlayer || (window.RC && window.RC.RcdPlayer);
  if (typeof Player !== "function") return;
  // The whole surface the player and the layout-custom operation call on a host.
  var INERT_HOST = Object.freeze({
    pending: 0,
    retire: function () {},
    setResolver: function () {},
    drawCustom: function () {},
  });
  Object.defineProperty(Player.prototype, "customHost", {
    configurable: false,
    get: function () {
      return INERT_HOST;
    },
    set: function () {},
  });
})();
