// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Nicolas Vaughan <nicolasvaughan@proton.me>
// This file is part of Wiktionary Reader. See the LICENSE file for details.

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

  // Every heading gets a number of its own, for the contents list and the
  // reading point.  They are given in the page's own order, before the
  // languages are reordered, so that a number always means the same
  // heading, whatever the settings.
  Array.prototype.forEach.call(
    document.querySelectorAll("body > section > h2, section > h3, section > h4, section > h5"),
    function (h, i) { h.setAttribute("data-wk", i); });

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

  // Secciones de tablas de flexión: con "Tables" apagado se ocultan enteras,
  // no solo la tabla, para que no quede un título vacío.
  var TABLE_SECTIONS = ["declension", "conjugation", "inflection", "mutation"];
  Array.prototype.forEach.call(document.querySelectorAll("section > .wk-fold"), function (h) {
    if (TABLE_SECTIONS.indexOf(norm(h.textContent)) >= 0) h.parentNode.classList.add("wk-table-section");
  });

  // Quotations and tables can be switched off; the app changes these
  // classes of <body> without loading the page again.
  document.body.classList.toggle("wk-no-quotes", cfg.quotes === false);
  document.body.classList.toggle("wk-no-tables", cfg.tables === false);

  // Abre todo lo que esconde a [el]: su lengua, si estaba oculta, y las
  // secciones plegadas que lo contienen.
  function uncover(el) {
    var sec = el.closest("body > section");
    if (sec) sec.classList.remove("wk-hidden");
    for (var p = el.closest("section.wk-closed"); p; p = p.parentNode.closest("section.wk-closed")) {
      p.classList.remove("wk-closed");
    }
    refreshBar();
  }

  window.wk = {
    // Called by the app for links into this same page ("#Latin", "#Etymology_2").
    show: function (frag) {
      if (!frag) return;
      var w = norm(frag);
      var l = langs.filter(function (x) { return matches(x, w); })[0];
      if (l) { reveal(l); return; }
      var el = document.getElementById(frag.replace(/ /g, "_"));
      if (!el) return;
      uncover(el);
      el.scrollIntoView();
    },
    // La lista de títulos para el índice: [nivel, texto, id], en el orden
    // en que se ven (las lenguas ya reordenadas).
    outline: function () {
      var out = [];
      Array.prototype.forEach.call(
        document.querySelectorAll("body > section > h2, section > h3, section > h4, section > h5"),
        function (h) {
          var sec = h.parentNode;
          if (sec.classList.contains("wk-table-section") && document.body.classList.contains("wk-no-tables")) return;
          out.push([Number(h.tagName.charAt(1)), h.textContent.trim(), h.getAttribute("data-wk"),
                    !!sec.closest(".wk-hidden")]);
        });
      return JSON.stringify(out);
    },
    go: function (id) {
      var h = document.querySelector('[data-wk="' + id + '"]');
      if (!h) return;
      uncover(h);
      h.parentNode.classList.remove("wk-closed");
      h.scrollIntoView();
    },
    set: function (option, on) {
      document.body.classList.toggle("wk-no-" + option, !on);
    },
    // The text selected in the page, for "Look up".
    selection: function () {
      return String(window.getSelection() || "").trim();
    }
  };

  // El punto de lectura, como el último título visible que quedó por
  // encima del borde de arriba y la distancia a él.  Se le pasa a la app
  // mientras se desplaza la página (cada 300 ms como mucho), para volver
  // aquí con Atrás aunque lo de arriba cambie de altura al cargar.
  function anchorNow() {
    var best = null;
    var all = document.querySelectorAll("[data-wk]");
    for (var i = 0; i < all.length; i++) {
      var h = all[i];
      if (h.offsetParent === null) continue;
      if (h.getBoundingClientRect().top > 1) break;
      best = h;
    }
    return best ? [best.getAttribute("data-wk"), Math.round(best.getBoundingClientRect().top)] : null;
  }
  var pending = false;
  window.addEventListener("scroll", function () {
    if (pending || !window.WkBridge) return;
    pending = true;
    setTimeout(function () {
      pending = false;
      var a = anchorNow();
      window.WkBridge.anchor(a ? a[0] : "", a ? a[1] : 0);
    }, 300);
  }, { passive: true });

  function restore() {
    var h = cfg.anchor ? document.querySelector('[data-wk="' + cfg.anchor + '"]') : null;
    if (h) {
      uncover(h);
      h.parentNode.classList.remove("wk-closed");
      window.scrollTo(0, h.getBoundingClientRect().top + window.scrollY - (cfg.anchorOffset || 0));
    } else if (cfg.scroll) {
      window.scrollTo(0, cfg.scroll);
    }
  }

  // Al volver atrás, la página vuelve adonde estaba; otra vez cuando
  // terminan de cargar las imágenes, por si cambió la altura de lo de arriba.
  if (cfg.target) window.wk.show(cfg.target);
  else if (cfg.anchor || cfg.scroll) {
    restore();
    var start = window.scrollY;
    window.addEventListener("load", function () {
      if (Math.abs(window.scrollY - start) < 50) restore();
    });
  }
})();
