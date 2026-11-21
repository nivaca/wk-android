// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Nicolas Vaughan <nicolasvaughan@proton.me>
// This file is part of wk. See the LICENSE file for details.

// Lo que se hace con la página una vez cargada: la barra de lenguas, el
// orden y el filtro de las lenguas según los ajustes, los títulos que se
// pliegan y las tablas anchas, que se desplazan de lado.

(function () {
  "use strict";
  var cfg = window.WK_CONFIG || {};
  var wanted = cfg.languages || [];          // ["la", "spanish"], en minúsculas
  var collapsed = cfg.collapsed || [];       // section titles closed at first

  function norm(s) {
    return (s || "").replace(/_/g, " ").replace(/\s+\d+$/, "").trim().toLowerCase();
  }

  // Una lengua por cada <section> cuyo primer hijo es un <h2>.
  var langs = [];
  Array.prototype.forEach.call(document.querySelectorAll("body > section"), function (sec) {
    var h = sec.firstElementChild;
    if (!h || h.tagName !== "H2") return;
    var hw = sec.querySelector(".headword[lang]");
    langs.push({ name: h.textContent.trim(), code: hw ? hw.getAttribute("lang") : "", sec: sec });
  });

  function matches(l, w) {
    return norm(l.name) === w || (l.code && l.code.toLowerCase() === w);
  }
  function rank(l) {
    for (var i = 0; i < wanted.length; i++) if (matches(l, wanted[i])) return i;
    return wanted.length;
  }

  // Las lenguas preferidas van primero, en el orden de los ajustes (sort
  // es estable, así que las demás conservan el orden de la página).
  if (wanted.length && langs.length) {
    var parent = langs[0].sec.parentNode;
    var mark = document.createComment("");
    parent.insertBefore(mark, langs[0].sec);
    langs.sort(function (a, b) { return rank(a) - rank(b); });
    langs.forEach(function (l) { parent.insertBefore(l.sec, mark); });
    parent.removeChild(mark);
    if (cfg.only && langs.some(function (l) { return rank(l) < wanted.length; })) {
      langs.forEach(function (l) {
        if (rank(l) === wanted.length) l.sec.classList.add("wk-hidden");
      });
    }
  }

  // The language bar: one chip per language, plus "all" when some are hidden.
  var bar = null;
  function refreshBar() {
    if (!bar) return;
    Array.prototype.forEach.call(bar.children, function (chip) {
      var l = chip.wkLang;
      chip.classList.toggle("wk-off", !!l && l.sec.classList.contains("wk-hidden"));
    });
    var all = bar.querySelector(".wk-all");
    if (all) all.hidden = !langs.some(function (l) { return l.sec.classList.contains("wk-hidden"); });
  }
  if (langs.length > 1) {
    bar = document.createElement("nav");
    bar.className = "wk-langs";
    langs.forEach(function (l) {
      var chip = document.createElement("button");
      chip.textContent = l.name;
      chip.wkLang = l;
      chip.onclick = function () { reveal(l); };
      bar.appendChild(chip);
    });
    var all = document.createElement("button");
    all.className = "wk-all";
    all.textContent = cfg.allLabel || "all";
    all.onclick = function () {
      langs.forEach(function (l) { l.sec.classList.remove("wk-hidden"); });
      refreshBar();
    };
    bar.appendChild(all);
    document.body.insertBefore(bar, document.body.firstChild);
    refreshBar();
  }

  function reveal(l) {
    l.sec.classList.remove("wk-hidden");
    refreshBar();
    l.sec.scrollIntoView();
  }

  // Titles below the language (h3…h6) open and close their section.
  Array.prototype.forEach.call(document.querySelectorAll("section > h3, section > h4, section > h5, section > h6"),
    function (h) {
      h.classList.add("wk-fold");
      if (collapsed.indexOf(norm(h.textContent)) >= 0) h.parentNode.classList.add("wk-closed");
      h.onclick = function () { h.parentNode.classList.toggle("wk-closed"); };
    });

  // Las tablas anchas se desplazan dentro de su propia caja, no la página entera.
  Array.prototype.forEach.call(document.querySelectorAll("table"), function (t) {
    if (t.parentNode.closest("table")) return;
    var box = document.createElement("div");
    box.className = "wk-scroll";
    t.parentNode.insertBefore(box, t);
    box.appendChild(t);
  });

  // Called by the app for links into this same page ("#Latin", "#Etymology_2").
  window.wk = {
    show: function (frag) {
      if (!frag) return;
      var w = norm(frag);
      var l = langs.filter(function (x) { return matches(x, w); })[0];
      if (l) { reveal(l); return; }
      var el = document.getElementById(frag.replace(/ /g, "_"));
      if (!el) return;
      var sec = el.closest("body > section");
      if (sec) sec.classList.remove("wk-hidden");
      for (var p = el.closest("section.wk-closed"); p; p = p.parentNode.closest("section.wk-closed")) {
        p.classList.remove("wk-closed");
      }
      refreshBar();
      el.scrollIntoView();
    }
  };

  // Al volver atrás, la página vuelve adonde estaba; otra vez cuando
  // terminan de cargar las imágenes, que cambian la altura de lo de arriba.
  if (cfg.target) window.wk.show(cfg.target);
  else if (cfg.scroll) {
    window.scrollTo(0, cfg.scroll);
    window.addEventListener("load", function () {
      if (window.scrollY < cfg.scroll + 50) window.scrollTo(0, cfg.scroll);
    });
  }
})();
