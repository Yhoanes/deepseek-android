/* DeepSeek for Android — WebView Compatibility Polyfills & Native Bridge Boot */
(function () {
  "use strict";

  // 0. Idempotent WebView Compatibility Polyfills (HarmonyOS 4.x / Chromium < 119)
  // No-op on modern WebViews where native implementations already exist.
  if (typeof AbortSignal !== "undefined" && typeof AbortSignal.any !== "function") {
    AbortSignal.any = function (signals) {
      var controller = new AbortController();
      var list = Array.prototype.slice.call(signals || []);
      for (var i = 0; i < list.length; i++) {
        var sig = list[i];
        if (sig && sig.aborted) {
          controller.abort(sig.reason);
          return controller.signal;
        }
      }
      for (var j = 0; j < list.length; j++) {
        (function (s) {
          if (!s || typeof s.addEventListener !== "function") return;
          s.addEventListener("abort", function () {
            if (!controller.signal.aborted) {
              controller.abort(s.reason);
            }
          }, { once: true });
        })(list[j]);
      }
      return controller.signal;
    };
  }

  if (typeof AbortSignal !== "undefined" && typeof AbortSignal.timeout !== "function") {
    AbortSignal.timeout = function (ms) {
      var controller = new AbortController();
      setTimeout(function () {
        try {
          controller.abort(new DOMException("The operation timed out.", "TimeoutError"));
        } catch (_) {
          controller.abort();
        }
      }, ms);
      return controller.signal;
    };
  }

  if (typeof Promise !== "undefined" && typeof Promise.withResolvers !== "function") {
    Promise.withResolvers = function () {
      var resolve, reject;
      var promise = new Promise(function (res, rej) {
        resolve = res;
        reject = rej;
      });
      return { promise: promise, resolve: resolve, reject: reject };
    };
  }

  if (typeof window.structuredClone !== "function") {
    var cloneValue = function (val, seen) {
      if (val === null || typeof val !== "object") return val;
      if (val instanceof Date) return new Date(val.getTime());
      if (val instanceof RegExp) return new RegExp(val.source, val.flags);
      if (typeof ArrayBuffer !== "undefined" && val instanceof ArrayBuffer) return val.slice(0);
      if (typeof ArrayBuffer !== "undefined" && ArrayBuffer.isView && ArrayBuffer.isView(val)) {
        try { return new val.constructor(val); } catch (_) { return val; }
      }
      if (seen.has(val)) return seen.get(val);
      if (Array.isArray(val)) {
        var arr = new Array(val.length);
        seen.set(val, arr);
        for (var i = 0; i < val.length; i++) arr[i] = cloneValue(val[i], seen);
        return arr;
      }
      if (typeof Map !== "undefined" && val instanceof Map) {
        var map = new Map();
        seen.set(val, map);
        val.forEach(function (v, k) { map.set(cloneValue(k, seen), cloneValue(v, seen)); });
        return map;
      }
      if (typeof Set !== "undefined" && val instanceof Set) {
        var set = new Set();
        seen.set(val, set);
        val.forEach(function (v) { set.add(cloneValue(v, seen)); });
        return set;
      }
      var out = {};
      seen.set(val, out);
      var keys = Object.keys(val);
      for (var k = 0; k < keys.length; k++) {
        var key = keys[k];
        out[key] = cloneValue(val[key], seen);
      }
      return out;
    };
    window.structuredClone = function (value) {
      return cloneValue(value, new Map());
    };
  }

  if (typeof Object.hasOwn !== "function") {
    Object.hasOwn = function (obj, prop) {
      return Object.prototype.hasOwnProperty.call(obj, prop);
    };
  }

  if (!Array.prototype.at) {
    Object.defineProperty(Array.prototype, "at", {
      value: function (n) {
        var len = this.length >>> 0;
        var rel = Math.trunc(n) || 0;
        var k = rel >= 0 ? rel : len + rel;
        return (k < 0 || k >= len) ? undefined : this[k];
      },
      writable: true,
      configurable: true
    });
  }

  if (!String.prototype.at) {
    Object.defineProperty(String.prototype, "at", {
      value: function (n) {
        var str = String(this);
        var len = str.length;
        var rel = Math.trunc(n) || 0;
        var k = rel >= 0 ? rel : len + rel;
        return (k < 0 || k >= len) ? undefined : str.charAt(k);
      },
      writable: true,
      configurable: true
    });
  }

  if (!Array.prototype.findLast) {
    Object.defineProperty(Array.prototype, "findLast", {
      value: function (predicate, thisArg) {
        for (var i = (this.length >>> 0) - 1; i >= 0; i--) {
          if (predicate.call(thisArg, this[i], i, this)) return this[i];
        }
        return undefined;
      },
      writable: true,
      configurable: true
    });
  }

  if (!Array.prototype.findLastIndex) {
    Object.defineProperty(Array.prototype, "findLastIndex", {
      value: function (predicate, thisArg) {
        for (var i = (this.length >>> 0) - 1; i >= 0; i--) {
          if (predicate.call(thisArg, this[i], i, this)) return i;
        }
        return -1;
      },
      writable: true,
      configurable: true
    });
  }

  if (!Array.prototype.toSorted) {
    Object.defineProperty(Array.prototype, "toSorted", {
      value: function (compareFn) {
        var copy = Array.prototype.slice.call(this);
        return compareFn !== undefined ? copy.sort(compareFn) : copy.sort();
      },
      writable: true,
      configurable: true
    });
  }

  if (!Array.prototype.toReversed) {
    Object.defineProperty(Array.prototype, "toReversed", {
      value: function () {
        return Array.prototype.slice.call(this).reverse();
      },
      writable: true,
      configurable: true
    });
  }

  if (!Array.prototype.toSpliced) {
    Object.defineProperty(Array.prototype, "toSpliced", {
      value: function () {
        var copy = Array.prototype.slice.call(this);
        Array.prototype.splice.apply(copy, arguments);
        return copy;
      },
      writable: true,
      configurable: true
    });
  }

  if (!Array.prototype.with) {
    Object.defineProperty(Array.prototype, "with", {
      value: function (index, value) {
        var copy = Array.prototype.slice.call(this);
        var len = copy.length;
        var rel = Math.trunc(index) || 0;
        var k = rel >= 0 ? rel : len + rel;
        if (k < 0 || k >= len) throw new RangeError("Invalid index");
        copy[k] = value;
        return copy;
      },
      writable: true,
      configurable: true
    });
  }

  if (!String.prototype.replaceAll) {
    Object.defineProperty(String.prototype, "replaceAll", {
      value: function (search, replacement) {
        var target = String(this);
        if (search instanceof RegExp) {
          if (search.flags.indexOf("g") === -1) {
            throw new TypeError("String.prototype.replaceAll called with a non-global RegExp");
          }
          return target.replace(search, replacement);
        }
        return target.split(String(search)).join(String(replacement));
      },
      writable: true,
      configurable: true
    });
  }

  if (typeof window.crypto !== "undefined" && typeof window.crypto.randomUUID !== "function"
      && typeof window.crypto.getRandomValues === "function") {
    window.crypto.randomUUID = function () {
      var buf = new Uint8Array(16);
      window.crypto.getRandomValues(buf);
      buf[6] = (buf[6] & 0x0f) | 0x40;
      buf[8] = (buf[8] & 0x3f) | 0x80;
      var hex = [];
      for (var i = 0; i < 16; i++) hex.push((buf[i] + 0x100).toString(16).slice(1));
      return hex.slice(0, 4).join("") + "-" + hex.slice(4, 6).join("") + "-"
        + hex.slice(6, 8).join("") + "-" + hex.slice(8, 10).join("") + "-" + hex.slice(10, 16).join("");
    };
  }

  if (typeof window.ResizeObserver === "undefined") {
    window.ResizeObserver = function (cb) { this._cb = cb; };
    window.ResizeObserver.prototype.observe = function (el) {
      if (!el || typeof this._cb !== "function") return;
      var cb = this._cb;
      requestAnimationFrame(function () {
        try { cb([{ target: el, contentRect: el.getBoundingClientRect() }]); } catch (_) {}
      });
    };
    window.ResizeObserver.prototype.unobserve = function () {};
    window.ResizeObserver.prototype.disconnect = function () {};
  }

  if (typeof window.IntersectionObserver === "undefined") {
    window.IntersectionObserver = function (cb) { this._cb = cb; };
    window.IntersectionObserver.prototype.observe = function (el) {
      if (!el || typeof this._cb !== "function") return;
      var cb = this._cb;
      requestAnimationFrame(function () {
        try { cb([{ target: el, isIntersecting: true, intersectionRatio: 1 }]); } catch (_) {}
      });
    };
    window.IntersectionObserver.prototype.unobserve = function () {};
    window.IntersectionObserver.prototype.disconnect = function () {};
  }
})();

(function () {
  "use strict";

  function getHost() {
    var h = window.Android || window.AndroidBridge || null;
    if (h && !window.AndroidBridge) {
      try { window.AndroidBridge = h; } catch (_) {}
    }
    return h;
  }

  if (window.__DS_ANDROID_BOOTED__ && getHost()) return;

  var BRIDGE_TOKEN = typeof window.__DS_BRIDGE_TOKEN__ === "string" ? window.__DS_BRIDGE_TOKEN__ : "";
  try {
    delete window.__DS_BRIDGE_TOKEN__;
  } catch (e) {
    window.__DS_BRIDGE_TOKEN__ = undefined;
  }

  if (getHost()) {
    window.__DS_ANDROID_BOOTED__ = true;
  }

  // Unregister any stale ServiceWorker so fresh local assets always load
  try {
    if (navigator.serviceWorker && typeof navigator.serviceWorker.getRegistrations === "function") {
      navigator.serviceWorker.getRegistrations().then(function (regs) {
        regs.forEach(function (r) { r.unregister(); });
      }).catch(function () {});
    }
  } catch (_) {}

  var OLED_STORAGE_KEY = "ds_android_oled_mode";
  function isOledEnabled() {
    try {
      return localStorage.getItem(OLED_STORAGE_KEY) === "1";
    } catch (_) {
      return false;
    }
  }

  function applyOledStyle() {
    var existing = document.getElementById("ds-android-oled-css");
    if (!isOledEnabled()) {
      if (existing && existing.parentNode) existing.parentNode.removeChild(existing);
      return;
    }
    if (existing) return;
    var st = document.createElement("style");
    st.id = "ds-android-oled-css";
    st.textContent = [
      "[data-ds-dark-theme] {",
      "  --dsw-static-neutral-bluish-1000: #000000 !important;",
      "  --dsw-static-neutral-bluish-950: #000000 !important;",
      "  --dsw-static-neutral-bluish-900: #050506 !important;",
      "  --dsw-static-neutral-bluish-875: #0a0a0b !important;",
      "  --dsw-static-neutral-bluish-850: #101012 !important;",
      "  --dsw-static-neutral-bluish-800: #17171a !important;",
      "  --dsw-alias-bg-base: #000000 !important;",
      "}",
      "html[data-ds-dark-theme] body, body[data-ds-dark-theme] {",
      "  background-color: #000000 !important;",
      "}"
    ].join("\n");
    (document.head || document.documentElement).appendChild(st);
  }
  applyOledStyle();

  function setOledEnabled(enabled) {
    try {
      if (enabled) {
        localStorage.setItem(OLED_STORAGE_KEY, "1");
      } else {
        localStorage.removeItem(OLED_STORAGE_KEY);
      }
    } catch (_) {}
    applyOledStyle();
    lastVisual = "";
    syncTheme();
  }

  var pendingCallbacks = Object.create(null);
  var pendingTimers = Object.create(null);
  var cbSeq = 0;
  function registerCb(prefix, timeoutMs, fn) {
    var id = (prefix || "ds_cb_") + (++cbSeq) + "_" + Date.now();
    pendingCallbacks[id] = fn;
    pendingTimers[id] = setTimeout(function () {
      var expired = pendingCallbacks[id];
      delete pendingCallbacks[id];
      delete pendingTimers[id];
      if (expired) {
        try { expired({ ok: false, error: "timeout" }); } catch (_) {}
      }
    }, timeoutMs || 90000);
    return id;
  }

  function settleCb(cbId, payload) {
    var fn = pendingCallbacks[cbId];
    if (pendingTimers[cbId]) {
      clearTimeout(pendingTimers[cbId]);
      delete pendingTimers[cbId];
    }
    if (fn) {
      delete pendingCallbacks[cbId];
      fn(payload);
    }
  }

  window.__dsOnPluginOpDone = function (cbId, resJson) {
    var parsed = {};
    try {
      parsed = typeof resJson === "string" ? JSON.parse(resJson) : (resJson || {});
    } catch (_) {}
    settleCb(cbId, parsed);
  };

  window.__dsOnDirectoryPicked = function (cbId, pickedPath) {
    settleCb(cbId, typeof pickedPath === "string" ? pickedPath : "");
  };

  var latestUpdateState = null;
  var lastRenderedUpdateSig = "";
  function ensureUpdateStateHydrated() {
    if (latestUpdateState) return latestUpdateState;
    var host = getHost();
    try {
      if (host && typeof host.getUpdateStateJson === "function") {
        latestUpdateState = JSON.parse(host.getUpdateStateJson() || '{"type":"idle"}');
      }
    } catch (_) {}
    if (!latestUpdateState) latestUpdateState = { type: "idle" };
    return latestUpdateState;
  }

  window.__dsOnUpdaterStateChanged = function (st) {
    var next = st || { type: "idle" };
    var prevType = latestUpdateState ? latestUpdateState.type : "idle";
    latestUpdateState = next;
    var sig = [next.type || "", next.version || "", next.progress || "", next.result || "", next.error || ""].join("|");
    if (sig !== lastRenderedUpdateSig) {
      lastRenderedUpdateSig = sig;
      if (next.type === "ready" && prevType !== "ready") {
        showToast(isSpanish()
          ? "Actualización v" + (next.version || "") + " lista en Complementos"
          : "Update v" + (next.version || "") + " ready in Plugins");
      }
      renderPluginsHubIfMounted();
    }
  };

  function isSpanish() {
    var lang = ((document.documentElement && document.documentElement.lang) || navigator.language || "").toLowerCase();
    return lang.indexOf("es") === 0;
  }

  // Toast notification
  var toastEl = null;
  var toastTimer = null;
  function showToast(msg) {
    if (!msg || !document.body) return;
    if (!toastEl || !toastEl.isConnected) {
      toastEl = document.createElement("div");
      toastEl.id = "ds-android-toast";
      document.body.appendChild(toastEl);
    }
    toastEl.textContent = String(msg);
    toastEl.classList.add("visible");
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      if (toastEl) toastEl.classList.remove("visible");
    }, 3200);
  }

  // 1. Mobile CSS & Virtual Viewport Keyboard Adaptation + Native Complementos Styling
  function injectMobileCss() {
    if (document.getElementById("ds-android-mobile-css")) return;
    var st = document.createElement("style");
    st.id = "ds-android-mobile-css";
    st.textContent = [
      "button, a, [role='button'], [role='tab'], input, select, textarea { touch-action: manipulation; -webkit-tap-highlight-color: transparent; }",
      "html, body, #root { height: var(--ds-vvh, 100dvh) !important; max-height: var(--ds-vvh, 100dvh) !important; }",
      "@media (max-width: 767px) {",
      "  .ZuhsRW_dialog.ZuhsRW_dialog { max-width: calc(100vw - 16px) !important; max-height: min(560px, calc(var(--ds-vvh, 100dvh) - 24px)) !important; }",
      "  .ZuhsRW_header { padding: 14px 14px 8px 16px !important; }",
      "  .ZuhsRW_content { padding: 12px 12px 12px 16px !important; }",
      "  .ZuhsRW_footerBar { padding: 12px 16px !important; flex-wrap: wrap !important; gap: 8px !important; }",
      "}",
      /* Authentic PC Desktop OnboardingSurface backdrop (solid base surface when unauthenticated) */
      "body:has([data-shortcut-modal] ._76YFJG_dialog:not(:has(._76YFJG_close)), .jLrgrW_dialog) > div[role='presentation'] > div[aria-hidden='true'],",
      "body > div[role='presentation']:has(> ._76YFJG_dialog:not(:has(._76YFJG_close)), > .jLrgrW_dialog) > div[aria-hidden='true'] {",
      "  background: var(--dsw-alias-bg-base, #15161a) !important;",
      "  opacity: 1 !important;",
      "  backdrop-filter: none !important;",
      "  -webkit-backdrop-filter: none !important;",
      "}",
      "@media (max-width: 960px) {",
      "  section[data-desktop-onboarding] { overflow-y: auto !important; padding: 24px 20px !important; box-sizing: border-box !important; }",
      "  section[data-desktop-onboarding='welcome'] > div { position: relative !important; top: auto !important; left: auto !important; transform: none !important; display: flex !important; flex-direction: column !important; gap: 28px !important; padding-top: 32px !important; height: auto !important; }",
      "  section[data-desktop-onboarding='welcome'] > div > div:first-child { position: relative !important; top: auto !important; left: auto !important; transform: none !important; width: 100% !important; max-width: 520px !important; }",
      "  section[data-desktop-onboarding='welcome'] > div > div:last-child { position: relative !important; top: auto !important; left: auto !important; width: 100% !important; height: auto !important; }",
      "  section[data-desktop-onboarding='welcome'] img { position: relative !important; left: 0 !important; top: 0 !important; width: 100% !important; max-width: 720px !important; height: auto !important; }",
      "  section[data-desktop-onboarding='credit'] > div { grid-template-columns: 1fr !important; gap: 24px !important; position: relative !important; top: auto !important; left: auto !important; transform: none !important; padding-top: 24px !important; }",
      "}",
      "#ds-agy-plugins-hub { display: flex; flex-direction: column; gap: 10px; width: 100%; }",
      ".ds-sys-card {",
      "  display: flex; flex-direction: column; gap: 8px; padding: 11px 12px; border-radius: 12px;",
      "  background: var(--dsw-alias-bg-layer-2, rgba(255,255,255,0.035));",
      "  border: 0.5px solid var(--dsw-alias-border-l3, rgba(255,255,255,0.08));",
      "}",
      ".ds-sys-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; }",
      ".ds-sys-meta { display: flex; flex-direction: column; gap: 2px; min-width: 0; flex: 1; }",
      ".ds-sys-label { font-size: 13px; font-weight: 500; color: var(--dsw-alias-label-primary, #f5f6fa); display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }",
      ".ds-sys-sub { font-size: 12px; color: var(--dsw-alias-label-secondary, rgba(160,160,175,0.85)); }",
      ".ds-sys-sub.err { color: #ef4444; }",
      ".ds-sys-divider { height: 0.5px; background: var(--dsw-alias-border-l3, rgba(255,255,255,0.07)); width: 100%; }",
      ".ds-ota-bar { width: 100%; height: 5px; border-radius: 999px; background: rgba(140,140,160,0.22); overflow: hidden; }",
      ".ds-ota-fill { height: 100%; border-radius: 999px; background: #4D6BFE; transition: width 0.2s ease; }",
      ".ds-pm-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 2px 4px 0 4px; }",
      ".ds-pm-title { margin: 0; font-size: 13.5px; font-weight: 500; color: var(--dsw-alias-label-primary, #f5f6fa); }",
      ".ds-pm-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; }",
      ".ds-pm-row { display: flex; align-items: center; gap: 14px; padding: 8px; border-radius: 12px; transition: background 0.15s ease; }",
      "@media (hover: hover) { .ds-pm-row:hover { background: var(--dsw-alias-bg-fill-hover, rgba(255,255,255,0.04)); } }",
      ".ds-pm-icon {",
      "  width: 48px; height: 48px; flex: none; border-radius: 12px;",
      "  background: var(--dsw-alias-bg-layer-2, rgba(255,255,255,0.04));",
      "  border: 0.5px solid var(--dsw-alias-border-l3, rgba(255,255,255,0.07));",
      "  display: flex; align-items: center; justify-content: center; color: #4D6BFE;",
      "}",
      ".ds-pm-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }",
      ".ds-pm-title-row { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; }",
      ".ds-pm-name { font-size: 14px; font-weight: 500; color: var(--dsw-alias-label-primary, #f5f6fa); line-height: 1.3; }",
      ".ds-pm-tag {",
      "  font-size: 11px; font-weight: 500; line-height: 1.4; padding: 1px 6px; border-radius: 5px;",
      "  background: rgba(77, 107, 254, 0.16); color: #6e88ff;",
      "}",
      ".ds-pm-desc {",
      "  font-size: 12.5px; line-height: 1.42; color: var(--dsw-alias-label-secondary, rgba(160,160,175,0.85));",
      "  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; white-space: normal;",
      "}",
      ".ds-pm-end { flex: none; display: flex; align-items: center; gap: 8px; }",
      "@media (max-width: 520px) {",
      "  .ds-pm-row { align-items: flex-start; flex-wrap: wrap; gap: 10px; padding: 10px 8px; }",
      "  .ds-pm-icon { width: 40px; height: 40px; border-radius: 10px; }",
      "  .ds-pm-main { min-width: calc(100% - 54px); }",
      "  .ds-pm-end { width: 100%; justify-content: flex-end; padding-left: 50px; }",
      "}",
      ".ds-agy-btn {",
      "  height: 28px; padding: 0 12px; border-radius: 8px; font-size: 12.5px; font-weight: 500; cursor: pointer;",
      "  border: 0.5px solid var(--dsw-alias-border-l2, rgba(160,160,180,0.25));",
      "  background: var(--dsw-alias-bg-layer-2, rgba(255,255,255,0.04));",
      "  color: var(--dsw-alias-label-primary, #f5f6fa); display: inline-flex; align-items: center; gap: 5px;",
      "}",
      ".ds-agy-btn.primary { background: #4D6BFE; border-color: #4D6BFE; color: #ffffff; }",
      ".ds-agy-btn.ghost-danger { padding: 0 8px; border-color: rgba(239,68,68,0.32); color: #ef4444; background: transparent; }",
      ".ds-agy-btn:disabled { opacity: 0.5; cursor: default; }",
      ".ds-pm-switch {",
      "  position: relative; width: 36px; height: 20px; border-radius: 999px; border: none; padding: 0; cursor: pointer;",
      "  background: rgba(140,140,155,0.35); transition: background 0.18s ease; flex: none;",
      "}",
      ".ds-pm-switch[data-checked='1'] { background: #4D6BFE; }",
      ".ds-pm-switch::after {",
      "  content: ''; position: absolute; top: 2px; left: 2px; width: 16px; height: 16px; border-radius: 50%;",
      "  background: #ffffff; transition: transform 0.18s ease; box-shadow: 0 1px 2px rgba(0,0,0,0.28);",
      "}",
      ".ds-pm-switch[data-checked='1']::after { transform: translateX(16px); }",
      ".ds-pm-switch:disabled { opacity: 0.45; cursor: default; }",
      "#ds-android-toast {",
      "  position: fixed; bottom: 68px; left: 50%; transform: translateX(-50%) translateY(14px);",
      "  background: #1e1f24; color: #f5f6fa; border: 1px solid rgba(77,107,254,0.55);",
      "  padding: 8px 14px; border-radius: 999px; font-size: 12.5px; font-weight: 600;",
      "  opacity: 0; pointer-events: none; transition: all 0.2s ease; z-index: 99999;",
      "  max-width: 88vw; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;",
      "}",
      "#ds-android-toast.visible { opacity: 1; transform: translateX(-50%) translateY(0); }"
    ].join("\n");
    (document.head || document.documentElement).appendChild(st);
  }

  function updateVisualViewport() {
    if (!window.visualViewport) return;
    var h = Math.round(window.visualViewport.height);
    if (h > 120) {
      document.documentElement.style.setProperty("--ds-vvh", h + "px");
    }
  }
  if (window.visualViewport) {
    window.visualViewport.addEventListener("resize", updateVisualViewport);
    updateVisualViewport();
  }

  // 2. Dismiss native splash as soon as DeepSeek #root mounts
  var uiSignaled = false;
  function signalReady() {
    if (uiSignaled) return;
    var host = getHost();
    if (!host) return;
    uiSignaled = true;
    window.__DS_ANDROID_BOOTED__ = true;
    try {
      if (typeof host.onUiReady === "function") {
        host.onUiReady();
      }
    } catch (_) {}
  }

  function checkRootMounted() {
    var root = document.getElementById("root");
    if (root && root.childNodes && root.childNodes.length > 0) {
      requestAnimationFrame(function () {
        requestAnimationFrame(signalReady);
      });
      return true;
    }
    return false;
  }

  // 3. Bi-directional theme sync (including pure-black OLED mode in dark theme)
  var lastVisual = "";
  var lastSource = "";
  function syncTheme() {
    var host = getHost();
    if (!host || !document.body) return;
    try {
      applyOledStyle();
      var isDark = document.body.hasAttribute("data-ds-dark-theme")
        || (document.documentElement && document.documentElement.hasAttribute("data-ds-dark-theme"));
      var visual = isDark ? (isOledEnabled() ? "oled" : "dark") : "light";
      if (visual !== lastVisual && typeof host.onVisualThemeChanged === "function") {
        lastVisual = visual;
        host.onVisualThemeChanged(visual);
      }
      var src = (document.documentElement && document.documentElement.getAttribute("data-ds-theme-source")) || "system";
      if (src !== lastSource && typeof host.setTheme === "function") {
        lastSource = src;
        host.setTheme(src);
      }
    } catch (_) {}
  }

  // 4. Native Android Folder Picker button inside DeepSeek's DirectoryBrowser modal
  function findDirectoryBrowserFooter() {
    var footer = document.querySelector(".ZuhsRW_footerBar");
    if (footer) return footer;
    var dialogs = document.querySelectorAll("[role='dialog']");
    for (var i = 0; i < dialogs.length; i++) {
      var dlg = dialogs[i];
      if (dlg.querySelector(".ZuhsRW_content, [data-directory-browser]")) {
        var f = dlg.querySelector("footer") || dlg.lastElementChild;
        if (f) return f;
      }
    }
    return null;
  }

  function enhanceDirectoryBrowserModal() {
    var host = getHost();
    if (!host || !BRIDGE_TOKEN || typeof host.pickProjectDirectoryAuth !== "function") return;
    var footer = findDirectoryBrowserFooter();
    if (!footer || footer.querySelector("[data-ds-android-picker='1']")) return;

    var btn = document.createElement("button");
    btn.type = "button";
    btn.setAttribute("data-ds-android-picker", "1");
    btn.className = "ds-agy-btn primary";
    btn.textContent = isSpanish() ? "Selector Android" : "Android Picker";
    btn.addEventListener("click", function () {
      var cbId = registerCb("ds_dir_", 120000, function (pickedPath) {
        if (typeof pickedPath !== "string" || !pickedPath) return;
        applyPickedPathToModal(pickedPath, 0);
      });
      try {
        host.pickProjectDirectoryAuth(BRIDGE_TOKEN, cbId);
      } catch (_) {
        settleCb(cbId, "");
      }
    });
    footer.insertBefore(btn, footer.firstChild);
  }

  function applyPickedPathToModal(pickedPath, attempt) {
    try {
      var input = document.querySelector(".ZuhsRW_pathInput")
        || document.querySelector("[role='dialog'] input[type='text']");
      if (!input && attempt === 0) {
        var editBtn = document.querySelector(".ZuhsRW_crumbEditZone");
        if (editBtn) editBtn.click();
      }
      if (!input && attempt < 8) {
        setTimeout(function () {
          applyPickedPathToModal(pickedPath, attempt + 1);
        }, 45);
        return;
      }
      if (!input) {
        showToast(pickedPath);
        return;
      }
      var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value");
      if (setter && setter.set) {
        setter.set.call(input, pickedPath);
      } else {
        input.value = pickedPath;
      }
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true }));
      showToast((isSpanish() ? "Ruta cargada: " : "Path loaded: ") + pickedPath);
    } catch (_) {}
  }

  // 5. Native Android System Card (OTA + OLED) & ARM64 Plugins inside [data-plugin-panel]
  var busyPluginMap = Object.create(null);
  var cachedCatalog = null;
  var cachedAppVersion = "";

  var PLUGIN_SVG = {
    "craft-anything": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#6e88ff" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>',
    "agy-video-gen": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#a855f7" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2.5"/></svg>',
    "agy-video-studio": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#ec4899" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="3" width="20" height="18" rx="2.5"/><line x1="7" y1="3" x2="7" y2="21"/><line x1="17" y1="3" x2="17" y2="21"/><line x1="2" y1="12" x2="22" y2="12"/></svg>',
    "game-studio": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><line x1="6" y1="12" x2="10" y2="12"/><line x1="8" y1="10" x2="8" y2="14"/><line x1="15" y1="13" x2="15.01" y2="13"/><line x1="18" y1="11" x2="18.01" y2="11"/><rect x="2" y="6" width="20" height="12" rx="4"/></svg>',
    "unity-android": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#38bdf8" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>',
    "github": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#e2e8f0" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="18" cy="18" r="3"/><circle cx="6" cy="6" r="3"/><path d="M13 6h3a2 2 0 0 1 2 2v7"/><line x1="6" y1="9" x2="6" y2="21"/></svg>',
    "agy-adb-bridge": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>',
    "chrome-devtools-plugin": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#f59e0b" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="4"/><line x1="21.17" y1="8" x2="12" y2="8"/></svg>',
    "android-cli-plugin": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#34d399" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="6" y="4" width="12" height="16" rx="2"/><line x1="10" y1="17" x2="14" y2="17"/></svg>',
    "science": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#60a5fa" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M9 3h6"/><path d="M10 3v6l-5 8a2 2 0 0 0 1.7 3h10.6a2 2 0 0 0 1.7-3l-5-8V3"/></svg>'
  };

  function getInstalledAppVersion() {
    if (cachedAppVersion) return cachedAppVersion;
    var host = getHost();
    try {
      if (host && typeof host.getAppVersion === "function") {
        cachedAppVersion = host.getAppVersion() || "";
      }
    } catch (_) {}
    return cachedAppVersion || "0.2.2-r3";
  }

  function loadCatalog() {
    if (cachedCatalog) return cachedCatalog;
    var host = getHost();
    try {
      if (host && typeof host.getPluginsCatalogJson === "function") {
        var parsed = JSON.parse(host.getPluginsCatalogJson() || "{}");
        if (Array.isArray(parsed.plugins)) {
          cachedCatalog = parsed.plugins;
          return cachedCatalog;
        }
      }
    } catch (_) {}
    return [];
  }

  function loadInstalledMap() {
    var host = getHost();
    try {
      if (host && BRIDGE_TOKEN && typeof host.listAndroidPluginsAuth === "function") {
        return JSON.parse(host.listAndroidPluginsAuth(BRIDGE_TOKEN) || "{}") || {};
      }
    } catch (_) {}
    return {};
  }

  function buildAndroidSystemCard(es, upd) {
    var card = document.createElement("div");
    card.className = "ds-sys-card";

    var otaRow = document.createElement("div");
    otaRow.className = "ds-sys-row";

    var otaMeta = document.createElement("div");
    otaMeta.className = "ds-sys-meta";

    var otaTitle = document.createElement("div");
    otaTitle.className = "ds-sys-label";
    var verBadge = document.createElement("span");
    verBadge.className = "ds-pm-tag";
    verBadge.textContent = "v" + (upd.installedVersion || getInstalledAppVersion());
    otaTitle.appendChild(document.createTextNode("DeepSeek Android "));
    otaTitle.appendChild(verBadge);
    otaMeta.appendChild(otaTitle);

    var otaSub = document.createElement("div");
    otaSub.className = "ds-sys-sub";
    var stType = (upd && upd.type) || "idle";
    if (stType === "ready") {
      otaSub.textContent = es
        ? "Nueva versión v" + (upd.version || "") + " descargada y lista para instalar"
        : "New release v" + (upd.version || "") + " downloaded and ready to install";
    } else if (stType === "downloading" || stType === "available for download") {
      var dlPct = typeof upd.progress === "number" && upd.progress >= 0 ? upd.progress + "%" : "…";
      otaSub.textContent = es
        ? "Descargando actualización v" + (upd.version || "") + " (" + dlPct + ")"
        : "Downloading update v" + (upd.version || "") + " (" + dlPct + ")";
    } else if (stType === "checking for updates") {
      otaSub.textContent = es ? "Comprobando lanzamientos en GitHub…" : "Checking GitHub Releases…";
    } else if (upd && upd.error) {
      otaSub.className = "ds-sys-sub err";
      otaSub.textContent = (es ? "Error OTA: " : "OTA error: ") + upd.error;
    } else if (upd && upd.result === "up-to-date") {
      otaSub.textContent = es ? "Estás en la versión más reciente" : "You are on the latest release";
    } else {
      otaSub.textContent = es
        ? "Actualizaciones OTA automáticas desde GitHub Releases"
        : "Automatic OTA updates from GitHub Releases";
    }
    otaMeta.appendChild(otaSub);
    otaRow.appendChild(otaMeta);

    if (stType === "ready") {
      var installBtn = document.createElement("button");
      installBtn.type = "button";
      installBtn.className = "ds-agy-btn primary";
      installBtn.textContent = es
        ? "Instalar" + (upd.version ? " v" + upd.version : "")
        : "Install" + (upd.version ? " v" + upd.version : "");
      installBtn.addEventListener("click", function () {
        try { getHost().applyAppUpdateAuth(BRIDGE_TOKEN); } catch (_) {}
      });
      otaRow.appendChild(installBtn);
    } else if (stType === "downloading" || stType === "available for download") {
      var dlBtn = document.createElement("button");
      dlBtn.type = "button";
      dlBtn.className = "ds-agy-btn";
      dlBtn.disabled = true;
      dlBtn.textContent = typeof upd.progress === "number" && upd.progress >= 0
        ? upd.progress + "%"
        : (es ? "Descargando…" : "Downloading…");
      otaRow.appendChild(dlBtn);
    } else {
      var chkBtn = document.createElement("button");
      chkBtn.type = "button";
      chkBtn.className = "ds-agy-btn";
      chkBtn.disabled = stType === "checking for updates";
      chkBtn.textContent = es ? "Buscar actualización" : "Check updates";
      chkBtn.addEventListener("click", function () {
        try { getHost().checkForAppUpdates(true); } catch (_) {}
      });
      otaRow.appendChild(chkBtn);
    }

    card.appendChild(otaRow);

    if (stType === "downloading") {
      var bar = document.createElement("div");
      bar.className = "ds-ota-bar";
      var fill = document.createElement("div");
      fill.className = "ds-ota-fill";
      var pctNum = typeof upd.progress === "number" && upd.progress >= 0 ? Math.min(100, Math.max(2, upd.progress)) : 8;
      fill.style.width = pctNum + "%";
      bar.appendChild(fill);
      card.appendChild(bar);
    }

    var div = document.createElement("div");
    div.className = "ds-sys-divider";
    card.appendChild(div);

    var oledRow = document.createElement("div");
    oledRow.className = "ds-sys-row";
    var oledMeta = document.createElement("div");
    oledMeta.className = "ds-sys-meta";
    var oledTitle = document.createElement("div");
    oledTitle.className = "ds-sys-label";
    oledTitle.textContent = es ? "Modo Negro Puro (OLED)" : "Pure Black Mode (OLED)";
    var oledSub = document.createElement("div");
    oledSub.className = "ds-sys-sub";
    oledSub.textContent = es
      ? "Fondo #000000 en tema oscuro para ahorrar batería en pantallas AMOLED"
      : "True #000000 dark surface for AMOLED power savings";
    oledMeta.appendChild(oledTitle);
    oledMeta.appendChild(oledSub);
    oledRow.appendChild(oledMeta);

    var oledActive = isOledEnabled();
    var oledSw = document.createElement("button");
    oledSw.type = "button";
    oledSw.setAttribute("role", "switch");
    oledSw.setAttribute("aria-checked", oledActive ? "true" : "false");
    oledSw.setAttribute("data-checked", oledActive ? "1" : "0");
    oledSw.className = "ds-pm-switch";
    oledSw.addEventListener("click", function () {
      var nextOled = oledSw.getAttribute("data-checked") !== "1";
      setOledEnabled(nextOled);
      renderPluginsHubIfMounted();
    });
    oledRow.appendChild(oledSw);
    card.appendChild(oledRow);

    return card;
  }

  function renderPluginsHubIfMounted() {
    var host = getHost();
    if (!host) return;

    var anchorGroup = document.querySelector("[data-plugin-panel] [data-plugin-group='extensions']")
      || document.querySelector("[data-plugin-panel] [data-plugin-group='basic']");
    if (!anchorGroup || !anchorGroup.parentElement) return;

    var hostList = anchorGroup.parentElement;
    var container = hostList.querySelector("#ds-agy-plugins-hub");
    if (!container) {
      container = document.createElement("section");
      container.id = "ds-agy-plugins-hub";
      if (anchorGroup.nextSibling) {
        hostList.insertBefore(container, anchorGroup.nextSibling);
      } else {
        hostList.appendChild(container);
      }
    }

    var es = isSpanish();
    var upd = ensureUpdateStateHydrated();
    var catalog = loadCatalog();
    var installed = loadInstalledMap();

    container.innerHTML = "";
    container.appendChild(buildAndroidSystemCard(es, upd));

    var head = document.createElement("div");
    head.className = "ds-pm-head";

    var heading = document.createElement("h3");
    heading.className = "ds-pm-title";
    heading.textContent = es ? "Complementos nativos Android" : "Android Native Plugins";
    head.appendChild(heading);

    container.appendChild(head);

    var ul = document.createElement("ul");
    ul.className = "ds-pm-list";

    catalog.forEach(function (item) {
      var st = installed[item.name] || null;
      var isInst = !!(st && st.installed);
      var isEn = !!(st && st.enabled);
      var isBusy = !!busyPluginMap[item.name];

      var li = document.createElement("li");
      li.className = "ds-pm-row";

      var iconWrap = document.createElement("span");
      iconWrap.className = "ds-pm-icon";
      iconWrap.innerHTML = PLUGIN_SVG[item.name] || PLUGIN_SVG["craft-anything"];
      li.appendChild(iconWrap);

      var main = document.createElement("div");
      main.className = "ds-pm-main";

      var titleRow = document.createElement("div");
      titleRow.className = "ds-pm-title-row";

      var nameSpan = document.createElement("span");
      nameSpan.className = "ds-pm-name";
      nameSpan.textContent = item.displayName || item.name;
      titleRow.appendChild(nameSpan);

      var tag = document.createElement("span");
      tag.className = "ds-pm-tag";
      tag.textContent = "ARM64 · v" + (item.version || "1.0.0");
      titleRow.appendChild(tag);

      main.appendChild(titleRow);

      var desc = document.createElement("span");
      desc.className = "ds-pm-desc";
      var descText = (es && item.descriptionEs) ? item.descriptionEs : (item.description || "");
      desc.textContent = descText;
      desc.title = descText;
      main.appendChild(desc);

      li.appendChild(main);

      var end = document.createElement("div");
      end.className = "ds-pm-end";

      if (!isInst) {
        var instBtn = document.createElement("button");
        instBtn.type = "button";
        instBtn.className = "ds-agy-btn";
        instBtn.disabled = isBusy;
        instBtn.textContent = isBusy ? (es ? "Instalando…" : "Installing…") : (es ? "Instalar" : "Install");
        instBtn.addEventListener("click", function () {
          busyPluginMap[item.name] = true;
          renderPluginsHubIfMounted();
          var cbId = registerCb("ds_plg_", 180000, function (res) {
            delete busyPluginMap[item.name];
            if (res && res.ok) {
              showToast((item.displayName || item.name) + (es ? " instalado" : " installed"));
            } else {
              showToast((es ? "Error: " : "Failed: ") + ((res && res.error) || "unknown"));
            }
            renderPluginsHubIfMounted();
          });
          try {
            getHost().installMarketplacePluginAsync(
              BRIDGE_TOKEN, cbId, item.name, item.archiveUrl || "", item.name, "android-arm64", item.version || "1.0.0"
            );
          } catch (err) {
            settleCb(cbId, { ok: false, error: String(err) });
          }
        });
        end.appendChild(instBtn);
      } else {
        var delBtn = document.createElement("button");
        delBtn.type = "button";
        delBtn.className = "ds-agy-btn ghost-danger";
        delBtn.disabled = isBusy;
        delBtn.title = es ? "Desinstalar" : "Uninstall";
        delBtn.textContent = es ? "Quitar" : "Remove";
        delBtn.addEventListener("click", function () {
          busyPluginMap[item.name] = true;
          renderPluginsHubIfMounted();
          var cbId = registerCb("ds_del_", 60000, function (res) {
            delete busyPluginMap[item.name];
            if (!res || res.error !== "timeout") {
              showToast((item.displayName || item.name) + (es ? " quitado" : " removed"));
            }
            renderPluginsHubIfMounted();
          });
          try {
            getHost().uninstallMarketplacePluginAsync(BRIDGE_TOKEN, cbId, item.name);
          } catch (err) {
            settleCb(cbId, { ok: false, error: String(err) });
          }
        });
        end.appendChild(delBtn);

        var sw = document.createElement("button");
        sw.type = "button";
        sw.setAttribute("role", "switch");
        sw.setAttribute("aria-checked", isEn ? "true" : "false");
        sw.setAttribute("data-checked", isEn ? "1" : "0");
        sw.className = "ds-pm-switch";
        sw.disabled = isBusy;
        sw.addEventListener("click", function () {
          var currentEn = sw.getAttribute("data-checked") === "1";
          busyPluginMap[item.name] = true;
          renderPluginsHubIfMounted();
          var cbId = registerCb("ds_tog_", 45000, function () {
            delete busyPluginMap[item.name];
            renderPluginsHubIfMounted();
          });
          try {
            getHost().toggleAndroidPluginAsync(BRIDGE_TOKEN, cbId, item.name, !currentEn);
          } catch (err) {
            settleCb(cbId, { ok: false, error: String(err) });
          }
        });
        end.appendChild(sw);
      }

      li.appendChild(end);
      ul.appendChild(li);
    });

    container.appendChild(ul);
  }

  // 6. Lifecycle & zero-churn DOM observation (disconnects subtree observer after #root mounts)
  var domSyncTimer = null;
  var bootMountObs = null;

  function scheduleDomSync() {
    if (domSyncTimer || document.visibilityState === "hidden") return;
    domSyncTimer = setTimeout(function () {
      domSyncTimer = null;
      if (!uiSignaled && checkRootMounted() && bootMountObs) {
        try { bootMountObs.disconnect(); } catch (_) {}
        bootMountObs = null;
      }
      syncTheme();
      enhanceDirectoryBrowserModal();
      if (document.querySelector("[data-plugin-panel]") && !document.getElementById("ds-agy-plugins-hub")) {
        renderPluginsHubIfMounted();
      }
    }, 160);
  }

  function boot() {
    injectMobileCss();
    applyOledStyle();
    updateVisualViewport();
    if (!checkRootMounted()) {
      try {
        bootMountObs = new MutationObserver(function () {
          if (checkRootMounted() && bootMountObs) {
            try { bootMountObs.disconnect(); } catch (_) {}
            bootMountObs = null;
          }
        });
        bootMountObs.observe(document.documentElement, { childList: true, subtree: true });
      } catch (_) {}
    }
    syncTheme();

    try {
      var themeObs = new MutationObserver(syncTheme);
      themeObs.observe(document.documentElement, {
        attributes: true,
        attributeFilter: ["data-ds-theme-source", "data-ds-dark-theme"]
      });
      if (document.body) {
        themeObs.observe(document.body, {
          attributes: true,
          attributeFilter: ["data-ds-dark-theme"]
        });
        var portalObs = new MutationObserver(scheduleDomSync);
        portalObs.observe(document.body, { childList: true, subtree: false });
      }
    } catch (_) {}

    document.addEventListener("click", scheduleDomSync, true);
    window.addEventListener("popstate", scheduleDomSync);
    window.addEventListener("hashchange", scheduleDomSync);

    setTimeout(function () {
      if (bootMountObs) {
        try { bootMountObs.disconnect(); } catch (_) {}
        bootMountObs = null;
      }
      signalReady();
    }, 4500);
  }

  var lastResumeTs = 0;
  function onHostResume() {
    var now = Date.now();
    if (now - lastResumeTs < 250) return;
    lastResumeTs = now;
    try {
      if (navigator.onLine !== false) {
        window.dispatchEvent(new Event("online"));
      }
      window.dispatchEvent(new Event("focus"));
      syncTheme();
      scheduleDomSync();
    } catch (_) {}
  }

  window.addEventListener("ds:host-resume", onHostResume);
  window.addEventListener("ds:host-theme", syncTheme);
  document.addEventListener("visibilitychange", function () {
    if (document.visibilityState === "visible") onHostResume();
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
