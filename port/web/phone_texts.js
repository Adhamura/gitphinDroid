/* gitphinDroid page additions: phone words instead of Flipper words (page chrome only, never repo contents). */
(function () {
  "use strict";
  /*PT-BEGIN*/
  var PT_RULES = [
    [/Git repositories stored on the Flipper SD card\./g, "Git repositories stored on this phone."],
    [/Information collected from this PC by the bridge\./g, "Information collected from this phone."],
    [/^This PC$/, "This phone"],
    [/Could not read PC info/g, "Could not read phone info"],
    [/Files on the Flipper/g, "Files on the phone"],
    [/^SD free$/, "Storage free"],
    [/^no card$/, "unknown"],
    [/\bthe Flipper SD card\b/g, "this phone"],
    [/\bFlipper SD card\b/g, "phone storage"],
    [/\bthe SD card\b/g, "the phone storage"],
    [/\bSD card\b/g, "phone storage"],
    [/\bfrom the Flipper\b/g, "from this phone"],
    [/\bThe bridge does not answer/g, "The server does not answer"],
    [/\bthe bridge\b/g, "the server"],
    [/\bFlipper status unknown/g, "Phone status unknown"],
    [/\bFlipper idle\b/g, "Phone idle"],
    [/\bFlipper is (working|busy)/g, "The phone is $1"],
    [/\bon the Flipper\b/g, "on the phone"],
    [/\bThe Flipper\b/g, "The phone"],
    [/\bthe Flipper\b/g, "the phone"],
    [/\bqFlipper\b/g, "your file manager"],
    [/\bon the PC\b/g, "on a computer"],
    [/\bthis PC\b/g, "this phone"]
  ];
  function ptMap(s) {
    if (!s || s.length > 400) return s;
    var o = s;
    for (var i = 0; i < PT_RULES.length; i++) o = o.replace(PT_RULES[i][0], PT_RULES[i][1]);
    if (/^Unnamed repository(;.*)?$/.test(o)) o = "No description";
    return o;
  }
  /*PT-END*/

  /* Where the rules apply: page chrome. Never inside file views, READMEs, comments or diffs. */
  var ALLOW = "header.top,.side,#panel-files,#panel-collector,#repos-list-view>.bar,dialog,#toasts,.note,.status,.small.muted,.empty";
  var DENY = "pre,code,textarea,script,style,input,.codebox,.md,.cmt,.diff,.snip,.rn,.cell-desc,.vh,#rv-readme";
  var ATTRS = ["title", "placeholder", "aria-label"];

  function okEl(el) {
    return !!(el && el.closest && el.closest(ALLOW) && !el.closest(DENY));
  }

  /* The pixel-font texts keep the raw text in a hidden .vh span: rebuild the picture. */
  function repix(vh) {
    var pxt = vh.previousSibling;
    if (!pxt || !pxt.classList || !pxt.classList.contains("pxt")) return;
    if (typeof pxSvg !== "function") return;
    var t = vh.textContent.trim().replace(/…/g, "...").replace(/·/g, "-").replace(/\s+/g, " ");
    if (!/^[\x20-\x7e]{1,60}$/.test(t)) return;
    var scale = 2;
    try { if (typeof pxScale === "function" && vh.parentNode) scale = pxScale(vh.parentNode); } catch (e) {}
    while (pxt.firstChild) pxt.removeChild(pxt.firstChild);
    pxt.appendChild(pxSvg(t, scale));
  }

  function fixText(n) {
    var p = n.parentNode;
    if (!p || p.nodeType !== 1) return;
    var isVh = p.classList && p.classList.contains("vh");
    var host = isVh ? p.parentNode : p;
    if (!okEl(host)) {
      /* "No description" applies to the description cells too */
      if (!(host && host.closest && host.closest(".cell-desc,#rv-desc")) || n.nodeValue.trim().indexOf("Unnamed repository") !== 0) return;
    }
    var v = n.nodeValue, w = ptMap(v.trim());
    if (w === v.trim()) return;
    n.nodeValue = v.replace(v.trim(), w);
    if (isVh) repix(p);
  }

  function fixAttrs(el) {
    if (el.nodeType !== 1 || !okEl(el)) return;
    for (var i = 0; i < ATTRS.length; i++) {
      var v = el.getAttribute(ATTRS[i]);
      if (!v) continue;
      var w = ptMap(v);
      if (w !== v) el.setAttribute(ATTRS[i], w);
    }
  }

  function scan(root) {
    if (!root || !root.isConnected) return;
    if (root.nodeType === 3) { fixText(root); return; }
    if (root.nodeType !== 1) return;
    var tw = document.createTreeWalker(root, 4, null), n;
    while ((n = tw.nextNode())) fixText(n);
    fixAttrs(root);
    var els = root.querySelectorAll("[title],[placeholder],[aria-label]");
    for (var i = 0; i < els.length; i++) fixAttrs(els[i]);
  }

  var pending = [], timer = 0;
  function flush() {
    timer = 0;
    var list = pending; pending = [];
    for (var i = 0; i < list.length; i++) { try { scan(list[i]); } catch (e) {} }
  }
  try {
    var mo = new MutationObserver(function (ms) {
      for (var i = 0; i < ms.length; i++) {
        var m = ms[i];
        if (m.type === "childList") { for (var j = 0; j < m.addedNodes.length; j++) pending.push(m.addedNodes[j]); }
        else pending.push(m.target);
      }
      if (!timer) timer = setTimeout(flush, 40);
    });
    var go = function () {
      pending.push(document.body);
      flush();
      mo.observe(document.body, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ATTRS });
    };
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", go);
    else go();
  } catch (e) {}
})();
