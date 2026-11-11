(function () {
  "use strict";
  var root = document.documentElement;

  // ---- light / dark theme toggle ------------------------------------------------
  var toggle = document.querySelector("[data-theme-toggle]");
  var darkQuery = window.matchMedia("(prefers-color-scheme: dark)");
  function currentTheme() {
    return root.getAttribute("data-theme") || (darkQuery.matches ? "dark" : "light");
  }
  function syncToggle() {
    if (!toggle) return;
    var dark = currentTheme() === "dark";
    root.setAttribute("data-theme", dark ? "dark" : "light");
    toggle.setAttribute("aria-pressed", String(dark));
    toggle.querySelector(".theme-label").textContent = dark ? "Light mode" : "Dark mode";
    toggle.setAttribute("aria-label", dark ? "Switch to light mode" : "Switch to dark mode");
  }
  if (toggle) {
    toggle.addEventListener("click", function () {
      var next = currentTheme() === "dark" ? "light" : "dark";
      root.setAttribute("data-theme", next);
      localStorage.setItem("theme", next);
      syncToggle();
    });
    syncToggle();
  }

  // ---- dismissible messages, confirmations, dropdowns ---------------------------
  document.querySelectorAll("[data-dismiss]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var flash = btn.closest(".flash");
      if (flash) flash.remove();
      document.getElementById("main").focus();
    });
  });
  document.querySelectorAll("form[data-confirm]").forEach(function (form) {
    form.addEventListener("submit", function (e) {
      if (!window.confirm(form.getAttribute("data-confirm"))) e.preventDefault();
    });
  });
  // Escape closes any open <details> menu and returns focus to its toggle.
  document.addEventListener("keydown", function (e) {
    if (e.key !== "Escape") return;
    document.querySelectorAll("details[open].dropdown, details[open].reader-settings").forEach(function (d) {
      d.removeAttribute("open");
      var s = d.querySelector("summary");
      if (s) s.focus();
    });
  });
  document.addEventListener("click", function (e) {
    document.querySelectorAll("details[open].dropdown, details[open].reader-settings").forEach(function (d) {
      if (!d.contains(e.target)) d.removeAttribute("open");
    });
  });

  // ---- small screens: fold the library filters unless one is in use -----------
  var narrow = window.matchMedia("(max-width: 640px)");
  document.querySelectorAll("details[data-collapse-on-mobile]").forEach(function (d) {
    var sync = function () {
      if (!narrow.matches) d.open = true;
      else if (d.getAttribute("data-collapse-on-mobile") === "true") d.open = false;
    };
    sync();
    narrow.addEventListener("change", sync);
  });
  // "Done" buttons inside a <details> panel close it and return focus to its toggle.
  document.querySelectorAll("[data-close-details]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var d = btn.closest("details");
      if (!d) return;
      d.removeAttribute("open");
      var s = d.querySelector("summary");
      if (s) s.focus();
    });
  });

  // ---- comment character counters (announced only near the limit) --------------
  document.querySelectorAll("textarea[data-counter]").forEach(function (ta) {
    var out = document.getElementById(ta.getAttribute("data-counter"));
    var max = parseInt(ta.getAttribute("maxlength"), 10);
    if (!out || !max) return;
    var update = function () {
      var left = max - ta.value.length;
      out.textContent = left <= 500 ? left + " characters left." : "";
    };
    ta.addEventListener("input", update);
    update();
  });
  // Opening Reply/Edit moves focus into its text box.
  document.querySelectorAll("details.comment-action").forEach(function (d) {
    d.addEventListener("toggle", function () {
      if (d.open) { var ta = d.querySelector("textarea"); if (ta) ta.focus(); }
    });
  });

  // ---- reader -------------------------------------------------------------------
  var reader = document.querySelector("[data-reader]");
  if (!reader) return;

  var DEFAULTS = { size: 100, font: "serif", spacing: "normal", width: "medium", paper: "default" };
  var prefs;
  try { prefs = Object.assign({}, DEFAULTS, JSON.parse(localStorage.getItem("readerPrefs") || "{}")); }
  catch (_) { prefs = Object.assign({}, DEFAULTS); }

  var sizeOut = document.getElementById("size-value");
  function loadDyslexicFont() {
    if (document.getElementById("opendyslexic-font")) return;
    var link = document.createElement("link");
    link.id = "opendyslexic-font";
    link.rel = "stylesheet";
    link.href = "https://fonts.cdnfonts.com/css/opendyslexic";
    document.head.appendChild(link);
  }
  function apply() {
    reader.style.setProperty("--reader-size", (1.15 * prefs.size / 100).toFixed(3) + "rem");
    ["font", "spacing", "width", "paper"].forEach(function (k) { reader.setAttribute("data-" + k, prefs[k]); });
    if (prefs.font === "dyslexic") loadDyslexicFont();
    if (sizeOut) sizeOut.textContent = prefs.size + "%";
    document.querySelectorAll("[data-reader-pref]").forEach(function (el) {
      el.value = prefs[el.getAttribute("data-reader-pref")];
    });
  }
  function save() { localStorage.setItem("readerPrefs", JSON.stringify(prefs)); apply(); }

  document.querySelectorAll("[data-reader-size]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var step = parseInt(btn.getAttribute("data-reader-size"), 10) * 10;
      prefs.size = Math.min(200, Math.max(70, prefs.size + step));
      save();
    });
  });
  document.querySelectorAll("[data-reader-pref]").forEach(function (el) {
    el.addEventListener("change", function () { prefs[el.getAttribute("data-reader-pref")] = el.value; save(); });
  });
  var reset = document.querySelector("[data-reader-reset]");
  if (reset) reset.addEventListener("click", function () { prefs = Object.assign({}, DEFAULTS); save(); });
  apply();

  // Left/right arrow keys change chapter (ignored while typing or using a control).
  document.addEventListener("keydown", function (e) {
    if (e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return;
    var t = e.target;
    if (t.closest && t.closest("input, select, textarea, [contenteditable], details[open]")) return;
    var link = e.key === "ArrowLeft" ? document.querySelector('[data-nav="prev"]')
             : e.key === "ArrowRight" ? document.querySelector('[data-nav="next"]') : null;
    if (link) { e.preventDefault(); window.location.href = link.href; }
  });

  // Reading progress bar.
  var bar = document.querySelector(".read-progress span");
  if (bar) {
    var update = function () {
      var rect = reader.getBoundingClientRect();
      var total = rect.height - window.innerHeight;
      var done = total > 0 ? Math.min(1, Math.max(0, -rect.top / total)) : 1;
      bar.style.width = (done * 100).toFixed(1) + "%";
    };
    window.addEventListener("scroll", update, { passive: true });
    window.addEventListener("resize", update);
    update();
  }
})();
