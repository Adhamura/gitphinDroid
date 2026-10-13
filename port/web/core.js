/* gitphinDroid page additions, part 1: shared helpers and the render loop.
 * The parts (core, phone_texts) are joined by CMake into /port/extra.js.
 * Parts register work with GPX.onRender(fn(roots)) and GPX.onHash(fn); every part wraps its work in try/catch. */
window.GPX = (function () {
  "use strict";
  var G = { renders: [], hashes: [], dirty: [] };
  G.dec = function (s) {
    try { return decodeURIComponent(s); } catch (e) { return s; }
  };
  G.onRender = function (fn) { G.renders.push(fn); };
  G.onHash = function (fn) { G.hashes.push(fn); };
  function runAll(list, arg) {
    for (var i = 0; i < list.length; i++) { try { list[i](arg); } catch (e) {} }
  }
  G.render = function () { runAll(G.renders, null); };
  function start() {
    try {
      G.render();
      window.addEventListener("hashchange", function () {
        setTimeout(function () { runAll(G.hashes, null); G.render(); }, 0);
      });
      var scheduled = false;
      var mo = new MutationObserver(function () {
        if (scheduled) return;
        scheduled = true;
        setTimeout(function () { scheduled = false; G.render(); }, 50);
      });
      mo.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ["hidden"] });
    } catch (e) {}
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start);
  else start();
  return G;
})();
