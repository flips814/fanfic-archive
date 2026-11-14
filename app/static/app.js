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

  // ---- machine translation (LibreTranslate) --------------------------------------
  // The chosen language is remembered, so the following chapters are translated too.
  var tMenu = document.querySelector("[data-translate]");
  var tStatus = document.querySelector("[data-translate-status]");
  if (!tMenu || !tStatus) return;
  var tSelect = tMenu.querySelector("[data-translate-to]");
  var tGo = tMenu.querySelector("[data-translate-go]");
  var tNote = tMenu.querySelector("[data-translate-note]");
  var tUrl = tMenu.getAttribute("data-url");
  var titleEl = reader.querySelector(".reader-head h1");
  var bodyEl = reader.querySelector(".chapter-body");
  var original = { title: titleEl.innerHTML, body: bodyEl.innerHTML, lang: reader.getAttribute("lang") };
  var langNames = {}, langsLoaded = null, active = null, pollTimer = null;

  function loadLanguages() {
    if (langsLoaded) return langsLoaded;
    var source = tMenu.getAttribute("data-source") || "";
    langsLoaded = fetchJson("GET", "/translate/languages?source=" + encodeURIComponent(source)).then(function (data) {
      var preferred = localStorage.getItem("translateTo");
      var browser = (navigator.language || "").toLowerCase().split("-")[0];
      tSelect.textContent = "";
      data.languages.forEach(function (lang) {
        langNames[lang.code] = lang.name;
        var opt = document.createElement("option");
        opt.value = lang.code;
        opt.textContent = lang.name;
        tSelect.appendChild(opt);
      });
      var pick = langNames[preferred] ? preferred
        : data.languages.filter(function (l) { return l.code.toLowerCase().split("-")[0] === browser; }).map(function (l) { return l.code; })[0];
      if (pick) tSelect.value = pick;
      tSelect.disabled = tGo.disabled = !data.languages.length;
      return data.languages;
    }, function (err) {
      tSelect.textContent = "";
      tSelect.appendChild(new Option("Unavailable", ""));
      tNote.textContent = err.message;
      langsLoaded = null;
      throw err;
    });
    return langsLoaded;
  }

  function fetchJson(method, url, form) {
    var opts = { method: method, credentials: "same-origin", headers: { Accept: "application/json" } };
    if (form) opts.body = new URLSearchParams(form);
    return fetch(url, opts).then(function (resp) {
      return resp.json().catch(function () { return {}; }).then(function (data) {
        if (!resp.ok) throw new Error(data.detail || "The server answered with an error (" + resp.status + ").");
        return data;
      });
    });
  }

  // Replaces the status line: a message, optional progress bar, optional buttons.
  function showStatus(text, actions, progress) {
    tStatus.textContent = "";
    var msg = document.createElement("span");
    msg.textContent = text;
    tStatus.appendChild(msg);
    if (progress != null) {
      var bar = document.createElement("progress");
      bar.max = 100;
      if (progress > 0) bar.value = progress;
      bar.setAttribute("aria-label", "Translation progress");
      tStatus.appendChild(bar);
    }
    (actions || []).forEach(function (a) {
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "link";
      btn.textContent = a.label;
      btn.addEventListener("click", a.run);
      tStatus.appendChild(btn);
    });
    tStatus.hidden = false;
  }

  function stopAction() { return { label: "Show original", run: function () { showOriginal(true); } }; }

  function translateTo(code) {
    clearTimeout(pollTimer);
    active = code;
    localStorage.setItem("translateTo", code);
    showStatus("Translating into " + (langNames[code] || code) + "…", [stopAction()], 0);
    fetchJson("POST", tUrl, { to: code }).then(function (data) { handle(code, data); }, failed(code));
  }

  function handle(code, data) {
    if (active !== code) return;
    if (data.status === "done") return showTranslation(code, data);
    if (data.status === "none") return translateTo(code);
    if (data.status === "failed") return failed(code)(new Error(data.error || "Translation failed."));
    var bar = tStatus.querySelector("progress");
    if (bar && data.progress > 0) bar.value = data.progress;
    pollTimer = setTimeout(function () {
      fetchJson("GET", tUrl + "?to=" + encodeURIComponent(code)).then(function (d) { handle(code, d); }, failed(code));
    }, 2000);
  }

  function failed(code) {
    return function (err) {
      if (active !== code) return;
      showStatus(err.message, [{ label: "Try again", run: function () { translateTo(code); } }, stopAction()]);
    };
  }

  function showTranslation(code, data) {
    titleEl.textContent = data.title || titleEl.textContent;
    bodyEl.innerHTML = data.content_html;
    reader.setAttribute("lang", code);
    showStatus("Machine-translated from " + (data.source_name || "the original") + " into " + (data.target_name || code) +
      ". It may get names, slang and tone wrong.", [stopAction()]);
    // Get the next chapter ready while this one is being read.
    var next = document.querySelector('[data-nav="next"]');
    if (next) fetchJson("POST", next.pathname + "/translation", { to: code }).catch(function () {});
  }

  function showOriginal(moveFocus) {
    clearTimeout(pollTimer);
    active = null;
    localStorage.removeItem("translateTo");
    titleEl.innerHTML = original.title;
    bodyEl.innerHTML = original.body;
    if (original.lang) reader.setAttribute("lang", original.lang); else reader.removeAttribute("lang");
    tStatus.hidden = true;
    tStatus.textContent = "";
    if (moveFocus) tMenu.querySelector("summary").focus();
  }

  tMenu.addEventListener("toggle", function () { if (tMenu.open) loadLanguages().catch(function () {}); });
  tGo.addEventListener("click", function () {
    if (!tSelect.value) return;
    tMenu.removeAttribute("open");
    translateTo(tSelect.value);
  });
  if (localStorage.getItem("translateTo")) {
    loadLanguages().then(function () {
      var code = localStorage.getItem("translateTo");
      if (langNames[code]) translateTo(code);  // not offered when the work is already in that language
    }, function () {});
  }
})();
