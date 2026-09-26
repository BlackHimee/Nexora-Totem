/* =====================================================================
   Nexora Analytics — application du dashboard
   ===================================================================== */
(() => {
  "use strict";

  const PAGES = {
    overview: "Vue d'ensemble",
    players: "Joueurs",
    engagement: "Rétention & activité",
    economy: "Économie",
    islands: "Îles & progression",
    resources: "Ressources",
    network: "Réseau & serveurs",
    incidents: "Incidents",
  };
  const PALETTE = ["#8b5cf6", "#22d3ee", "#34d399", "#fbbf24", "#f472b6", "#60a5fa", "#f87171", "#a3e635", "#fb923c", "#c084fc"];
  const DAYS = ["Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim"];
  const INCIDENT_TYPES = {
    CRASH: { label: "Crash", cls: "red", icon: "💥" },
    FATAL: { label: "Fatal", cls: "red", icon: "☠️" },
    ERROR: { label: "Erreur", cls: "amber", icon: "⚠️" },
    DOWN: { label: "Coupure", cls: "red", icon: "🔌" },
    UP: { label: "Rétabli", cls: "green", icon: "✅" },
  };

  const state = {
    page: "overview",
    range: 30,
    data: null,
    live: null,
    charts: {},
    players: { q: "", sort: "last", page: 0 },
    incidentFilter: "",
    openIncidents: new Set(),
  };

  const $ = (id) => document.getElementById(id);
  const nf = new Intl.NumberFormat("fr-FR");
  const nf1 = new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 1 });
  const nf2 = new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 2 });
  const compactFmt = new Intl.NumberFormat("fr-FR", { notation: "compact", maximumFractionDigits: 1 });

  // ------------------------------------------------------------------ utilitaires
  const esc = (v) =>
    String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  const num = (n) => nf.format(Math.round(n || 0));
  const dec = (n) => nf1.format(n || 0);
  const compact = (n) => (Math.abs(n) >= 10000 ? compactFmt.format(n) : nf2.format(n || 0));
  const money = (n) => compact(n || 0) + " ⛁";
  const pad = (n) => String(n).padStart(2, "0");

  function dur(ms, short) {
    if (!ms || ms < 0) return "0 min";
    const m = Math.floor(ms / 60000);
    if (m < 1) return Math.round(ms / 1000) + " s";
    if (m < 60) return m + " min";
    const h = Math.floor(m / 60);
    if (h < 24 || short) return h + " h " + pad(m % 60);
    const d = Math.floor(h / 24);
    return d + " j " + (h % 24) + " h";
  }
  function dateTime(ts) {
    if (!ts) return "—";
    const d = new Date(ts);
    return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }
  function date(ts) {
    if (!ts) return "—";
    const d = new Date(ts);
    return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()}`;
  }
  function ago(ts) {
    if (!ts) return "—";
    const s = Math.max(0, (Date.now() - ts) / 1000);
    if (s < 60) return "à l'instant";
    if (s < 3600) return `il y a ${Math.floor(s / 60)} min`;
    if (s < 86400) return `il y a ${Math.floor(s / 3600)} h`;
    const d = Math.floor(s / 86400);
    return d === 1 ? "hier" : `il y a ${d} j`;
  }
  const avatar = (uuid, size = 32) => `https://mc-heads.net/avatar/${encodeURIComponent(uuid)}/${size}`;
  const prettyMaterial = (m) =>
    String(m || "").toLowerCase().split("_").map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
  function hexA(hex, a) {
    const h = hex.replace("#", "");
    const n = parseInt(h, 16);
    return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`;
  }
  const ls = {
    get(k) { try { return localStorage.getItem(k); } catch { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch { /* stockage indisponible */ } },
  };

  async function api(path, options) {
    const res = await fetch(path, { credentials: "same-origin", ...options });
    if (res.status === 401) {
      showLogin();
      throw new Error("unauthorized");
    }
    if (!res.ok) throw new Error("HTTP " + res.status);
    return res.json();
  }

  function toast(msg) {
    const t = $("toast");
    t.textContent = msg;
    t.classList.remove("hidden");
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => t.classList.add("hidden"), 3500);
  }

  // ------------------------------------------------------------------ compteurs animés
  function animateCount(el, to, format) {
    const from = parseFloat(el.dataset.current || "0") || 0;
    el.dataset.current = to;
    if (from === to) { el.textContent = format(to); return; }
    const start = performance.now();
    const duration = 1100;
    const step = (now) => {
      const p = Math.min(1, (now - start) / duration);
      const eased = 1 - Math.pow(1 - p, 4);
      el.textContent = format(from + (to - from) * eased);
      if (p < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }

  const FORMATS = { num, dec, dur: (v) => dur(v), money, pct: (v) => dec(v) + " %" };

  /** Grille de KPI : {icon, label, value, fmt, sub, color, raw}. */
  function renderKpis(containerId, items) {
    const box = $(containerId);
    const existing = box.children.length === items.length;
    if (!existing) {
      box.innerHTML = items
        .map(
          (k, i) => `
        <div class="kpi" style="--kpi-color:${k.color || PALETTE[i % PALETTE.length]};animation-delay:${i * 45}ms">
          <div class="kpi-top"><div class="kpi-icon">${k.icon}</div></div>
          <div class="kpi-label">${esc(k.label)}</div>
          <div class="kpi-value">0</div>
          <div class="kpi-sub"></div>
        </div>`
        )
        .join("");
    }
    items.forEach((k, i) => {
      const card = box.children[i];
      card.querySelector(".kpi-label").textContent = k.label;
      const valueEl = card.querySelector(".kpi-value");
      if (k.raw !== undefined) valueEl.textContent = k.raw;
      else animateCount(valueEl, Number(k.value) || 0, FORMATS[k.fmt || "num"]);
      card.querySelector(".kpi-sub").innerHTML = k.sub || "";
    });
  }

  function trend(current, previous, suffix = "") {
    if (previous === undefined || previous === null) return "";
    const diff = current - previous;
    if (diff === 0) return `<span class="trend flat">= ${suffix}</span>`;
    const up = diff > 0;
    return `<span class="trend ${up ? "up" : "down"}"><svg><use href="#i-${up ? "up" : "down"}"/></svg>${num(Math.abs(diff))}${suffix}</span>`;
  }

  // ------------------------------------------------------------------ graphiques
  Chart.defaults.color = "#a4acc9";
  Chart.defaults.font.family = "Inter, system-ui, sans-serif";
  Chart.defaults.font.size = 12;
  Chart.defaults.borderColor = "rgba(148,163,214,0.08)";
  Chart.defaults.plugins.legend.labels.usePointStyle = true;
  Chart.defaults.plugins.legend.labels.boxWidth = 8;
  Chart.defaults.plugins.legend.labels.padding = 16;
  Chart.defaults.plugins.tooltip.backgroundColor = "rgba(14,17,34,0.95)";
  Chart.defaults.plugins.tooltip.borderColor = "rgba(148,163,214,0.2)";
  Chart.defaults.plugins.tooltip.borderWidth = 1;
  Chart.defaults.plugins.tooltip.padding = 12;
  Chart.defaults.plugins.tooltip.cornerRadius = 10;
  Chart.defaults.plugins.tooltip.titleFont = { weight: "600" };
  Chart.defaults.plugins.tooltip.usePointStyle = true;
  Chart.defaults.animation.duration = 900;
  Chart.defaults.animation.easing = "easeOutQuart";
  Chart.defaults.maintainAspectRatio = false;

  const fill = (color) => (ctx) => {
    const { chart } = ctx;
    const area = chart.chartArea;
    if (!area) return hexA(color, 0.2);
    const g = chart.ctx.createLinearGradient(0, area.top, 0, area.bottom);
    g.addColorStop(0, hexA(color, 0.35));
    g.addColorStop(1, hexA(color, 0));
    return g;
  };

  const baseScales = (opts = {}) => ({
    x: { grid: { display: false }, ticks: { maxRotation: 0, autoSkipPadding: 12 }, stacked: !!opts.stacked },
    y: { beginAtZero: true, grid: { color: "rgba(148,163,214,0.07)" }, border: { display: false }, stacked: !!opts.stacked,
         ticks: { precision: 0, callback: opts.yFormat || ((v) => compact(v)) } },
  });

  function chart(id, config) {
    const existing = state.charts[id];
    if (existing) {
      // Mise à jour en place : seules les valeurs changent, le graphique glisse vers les nouvelles
      // données au lieu d'être entièrement redessiné.
      const next = config.data;
      existing.data.labels = next.labels;
      if (existing.data.datasets.length === next.datasets.length) {
        next.datasets.forEach((ds, i) => Object.assign(existing.data.datasets[i], ds));
      } else {
        existing.data.datasets = next.datasets;
      }
      existing.update();
      return existing;
    }
    const canvas = $(id);
    if (!canvas) return null;
    state.charts[id] = new Chart(canvas, config);
    return state.charts[id];
  }

  function line(id, labels, datasets, opts = {}) {
    return chart(id, {
      type: "line",
      data: {
        labels,
        datasets: datasets.map((d, i) => {
          const color = d.color || PALETTE[i];
          return {
            label: d.label,
            data: d.data,
            borderColor: color,
            backgroundColor: d.fill === false ? color : fill(color),
            fill: d.fill !== false ? (opts.stacked ? (i === 0 ? "origin" : "-1") : "origin") : false,
            tension: 0.38,
            borderWidth: 2.2,
            pointRadius: 0,
            pointHoverRadius: 5,
            pointHoverBackgroundColor: color,
            pointHoverBorderColor: "#fff",
            pointHoverBorderWidth: 2,
            borderDash: d.dash || [],
            yAxisID: d.axis || "y",
            order: d.order ?? i,
          };
        }),
      },
      options: {
        interaction: { mode: "index", intersect: false },
        plugins: { legend: { display: datasets.length > 1, position: "top", align: "end" },
                   tooltip: { callbacks: opts.tooltip ? { label: opts.tooltip } : {} } },
        scales: baseScales(opts),
      },
    });
  }

  function bars(id, labels, datasets, opts = {}) {
    return chart(id, {
      type: "bar",
      data: {
        labels,
        datasets: datasets.map((d, i) => ({
          type: d.type || "bar",
          label: d.label,
          data: d.data,
          backgroundColor: d.type === "line" ? d.color : Array.isArray(d.color) ? d.color : hexA(d.color || PALETTE[i], 0.85),
          hoverBackgroundColor: d.color || PALETTE[i],
          borderColor: d.color || PALETTE[i],
          borderWidth: d.type === "line" ? 2.2 : 0,
          borderRadius: opts.horizontal ? 6 : 6,
          borderSkipped: false,
          maxBarThickness: opts.horizontal ? 18 : 26,
          tension: 0.38,
          pointRadius: 0,
          order: d.type === "line" ? 0 : 1,
        })),
      },
      options: {
        indexAxis: opts.horizontal ? "y" : "x",
        interaction: { mode: "index", intersect: false },
        plugins: { legend: { display: datasets.length > 1, position: "top", align: "end" },
                   tooltip: { callbacks: opts.tooltip ? { label: opts.tooltip } : {} } },
        scales: opts.horizontal
          ? { x: { beginAtZero: true, grid: { color: "rgba(148,163,214,0.07)" }, border: { display: false }, ticks: { callback: (v) => compact(v) } },
              y: { grid: { display: false }, ticks: { autoSkip: false } } }
          : baseScales(opts),
      },
    });
  }

  function doughnut(id, labels, values, opts = {}) {
    return chart(id, {
      type: "doughnut",
      data: { labels, datasets: [{ data: values, backgroundColor: labels.map((_, i) => PALETTE[i % PALETTE.length]), borderColor: "#141830", borderWidth: 3, hoverOffset: 10 }] },
      options: {
        cutout: "68%",
        plugins: {
          legend: { position: "right", labels: { padding: 12 } },
          tooltip: { callbacks: { label: opts.tooltip || ((c) => ` ${c.label} : ${num(c.parsed)}`) } },
        },
        animation: { animateRotate: true, animateScale: true },
      },
    });
  }

  // ------------------------------------------------------------------ composants
  function rings(containerId, retention) {
    const items = [
      { key: "d1", label: "Rétention J1", color: "#8b5cf6" },
      { key: "d7", label: "Rétention J7", color: "#22d3ee" },
      { key: "d30", label: "Rétention J30", color: "#34d399" },
    ];
    const box = $(containerId);
    const R = 46;
    const C = 2 * Math.PI * R;
    if (!box.children.length) {
      box.innerHTML = items
        .map(
          (it) => `
        <div class="ring" data-key="${it.key}">
          <div class="ring-wrap">
            <svg viewBox="0 0 108 108"><circle class="track" cx="54" cy="54" r="${R}"/>
              <circle class="bar" cx="54" cy="54" r="${R}" stroke="${it.color}" stroke-dasharray="${C}" stroke-dashoffset="${C}"/></svg>
            <div class="ring-value">—</div>
          </div>
          <div class="ring-label">${it.label}</div>
          <div class="ring-sub"></div>
        </div>`
        )
        .join("");
    }
    items.forEach((it) => {
      const r = retention[it.key] || {};
      const el = box.querySelector(`[data-key="${it.key}"]`);
      const rate = r.rate;
      requestAnimationFrame(() => {
        el.querySelector(".bar").style.strokeDashoffset = rate == null ? C : C * (1 - Math.min(100, rate) / 100);
      });
      const valueEl = el.querySelector(".ring-value");
      if (rate == null) valueEl.textContent = "—";
      else animateCount(valueEl, rate, (v) => dec(v) + "%");
      el.querySelector(".ring-sub").textContent = r.cohort ? `${num(r.retained)} / ${num(r.cohort)} joueurs` : "Pas encore de données";
    });
  }

  function leaderboard(containerId, rows, valueFn, barFn, empty) {
    const box = $(containerId);
    if (!rows || !rows.length) {
      box.innerHTML = `<div class="empty">${esc(empty || "Aucune donnée pour le moment")}</div>`;
      return;
    }
    const max = Math.max(...rows.map(barFn), 1);
    const settled = isSettled(box);
    box.innerHTML = rows
      .map(
        (r, i) => `
      <div class="lb-row" data-uuid="${esc(r.uuid || "")}" style="animation-delay:${i * 40}ms">
        <div class="lb-rank">${i + 1}</div>
        ${r.uuid ? `<img class="avatar" src="${avatar(r.uuid)}" alt="" loading="lazy">` : ""}
        <div class="lb-name">${esc(r.name)}<div class="lb-bar"><i data-w="${(100 * barFn(r)) / max}" ${settled ? `style="width:${(100 * barFn(r)) / max}%"` : ""}></i></div></div>
        <div class="lb-value">${valueFn(r)}</div>
      </div>`
      )
      .join("");
    if (!settled) requestAnimationFrame(() => box.querySelectorAll(".lb-bar i").forEach((b) => (b.style.width = b.dataset.w + "%")));
  }

  function playerRow(p) {
    return `<div class="player-cell"><img class="avatar" src="${avatar(p.uuid)}" alt="" loading="lazy">${esc(p.name)}</div>`;
  }

  // ------------------------------------------------------------------ pages
  function renderOverview() {
    const d = state.data;
    const k = d.kpis;
    const yesterdayActive = d.daily.length > 1 ? d.daily[d.daily.length - 2].active : null;
    renderKpis("kpi-overview", [
      { icon: "👤", label: "Joueurs uniques", value: k.uniquePlayers, color: "#8b5cf6", sub: `${num(k.activeRange)} actifs sur ${d.range} j` },
      { icon: "🆕", label: "Nouveaux joueurs aujourd'hui", value: k.newToday, color: "#22d3ee", sub: `${trend(k.newToday, k.newYesterday)} vs hier (${num(k.newYesterday)})` },
      { icon: "📈", label: "Actifs aujourd'hui", value: k.activeToday, color: "#34d399", sub: `${trend(k.activeToday, yesterdayActive)} vs hier` },
      { icon: "🗓️", label: "Actifs 7 jours / 30 jours", raw: `${num(k.active7)} / ${num(k.active30)}`, color: "#60a5fa", sub: "Joueurs distincts" },
      { icon: "🟢", label: "En ligne maintenant", value: state.live ? state.live.online : k.online, color: "#34d399", sub: `Pic du jour : <b>${num(k.peakToday)}</b>` },
      { icon: "📊", label: "Record de joueurs simultanés", value: k.peakAll, color: "#fbbf24", sub: k.peakAllTs ? `le ${dateTime(k.peakAllTs)}` : "—" },
      { icon: "⏱️", label: "Temps de jeu moyen / jour", value: k.avgPlaytimeDayMs, fmt: "dur", color: "#f472b6", sub: `Session moyenne : <b>${dur(k.avgSessionMs)}</b>` },
      { icon: "🚪", label: "Connexions / déconnexions (auj.)", raw: `${num(k.connectionsToday)} / ${num(k.disconnectionsToday)}`, color: "#fb923c", sub: `${num(k.connectionsRange)} connexions sur ${d.range} j` },
    ]);

    const labels = d.daily.map((r) => r.label);
    bars("c-daily", labels, [
      { label: "Joueurs actifs", data: d.daily.map((r) => r.active), color: "#8b5cf6", type: "line" },
      { label: "Nouveaux joueurs", data: d.daily.map((r) => r.new), color: "#22d3ee" },
    ]);
    rings("rings-overview", d.retention);

    const tl = d.timeline;
    const servers = tl.length ? Object.keys(tl[0].servers) : [];
    const tlLabels = tl.map((p) => { const t = new Date(p.ts); return `${pad(t.getHours())}:${pad(t.getMinutes())}`; });
    if (servers.length > 1) {
      line("c-timeline", tlLabels, servers.map((s, i) => ({ label: s, data: tl.map((p) => p.servers[s] || 0), color: PALETTE[i % PALETTE.length] })), { stacked: true });
    } else {
      line("c-timeline", tlLabels, [{ label: "Joueurs en ligne", data: tl.map((p) => p.online), color: "#22d3ee" }]);
    }
    const peakHour = d.hours.indexOf(Math.max(...d.hours));
    bars("c-hours", d.hours.map((_, h) => `${pad(h)}h`), [
      { label: "Joueurs en ligne (moy.)", data: d.hours, color: d.hours.map((_, h) => (h === peakHour ? "#fbbf24" : hexA("#8b5cf6", 0.75))) },
    ], { tooltip: (c) => ` ${dec(c.parsed.y)} joueurs en moyenne` });
    renderLive();
  }

  function renderLive() {
    const live = state.live;
    if (!live) return;
    $("live-online").textContent = num(live.online);
    $("live-count-chip").textContent = `${num(live.online)} joueur${live.online > 1 ? "s" : ""}`;
    const t = $("t-live");
    t.innerHTML = live.players.length
      ? `<thead><tr><th>Joueur</th><th>Serveur</th><th>Version</th><th>Client</th><th class="num">Ping</th></tr></thead><tbody>${live.players
          .map(
            (p) => `<tr data-uuid="${esc(p.uuid)}"><td>${playerRow(p)}</td><td><span class="tag violet">${esc(p.server || "—")}</span></td>
          <td><span class="tag cyan">${esc(p.version)}</span></td><td class="muted">${esc(p.brand)}</td><td class="num">${num(p.ping)} ms</td></tr>`
          )
          .join("")}</tbody>`
      : `<tbody><tr><td class="empty">Aucun joueur connecté</td></tr></tbody>`;
    t.classList.add("table-hover");
    $("servers-mini").innerHTML = live.servers.length
      ? live.servers
          .map((s) => {
            const dot = s.status === "online" ? "on" : s.status === "offline" ? "off" : "warn";
            const tps = s.tps != null ? `<span class="tag ${s.tps >= 18 ? "green" : s.tps >= 15 ? "amber" : "red"}">${dec(s.tps)} TPS</span>` : "";
            return `<div class="server-row"><div class="name"><span class="dot ${dot}"></span>${esc(s.name)}</div>
              <div class="meta">${tps}<span class="tag">${num(s.online)} joueur${s.online > 1 ? "s" : ""}</span></div></div>`;
          })
          .join("")
      : `<div class="empty">Aucun serveur enregistré</div>`;
    if (state.page === "network") renderServerCards();
  }

  function renderEngagement() {
    const d = state.data;
    const k = d.kpis;
    const ret = d.returning;
    const st = d.streaks;
    const ina = d.inactivity;
    renderKpis("kpi-engagement", [
      { icon: "🔁", label: "Revenus après ≥ 1 jour d'absence", value: ret.after1, color: "#8b5cf6", sub: `sur les ${d.range} derniers jours` },
      { icon: "📆", label: "Revenus après ≥ 7 jours", value: ret.after7, color: "#22d3ee", sub: "Joueurs de retour" },
      { icon: "🌙", label: "Revenus après ≥ 30 jours", value: ret.after30, color: "#60a5fa", sub: "Retours de longue absence" },
      { icon: "🔥", label: "Streaks en cours (≥ 2 j)", value: st.activeStreaks, color: "#fb923c", sub: `Moyenne : <b>${dec(st.average)} j</b> · record <b>${num(st.best)} j</b>${st.bestName ? " (" + esc(st.bestName) + ")" : ""}` },
      { icon: "💤", label: `Inactifs (> ${ina.thresholdDays} j)`, value: ina.inactive, color: "#f87171", sub: `${num(ina.recentlyInactive)} cette semaine` },
      { icon: "⚠️", label: "Joueurs à risque", value: ina.atRisk, color: "#fbbf24", sub: "Réguliers absents depuis quelques jours" },
      { icon: "⏱️", label: "Temps de jeu total", value: k.totalPlaytimeRangeMs, fmt: "dur", color: "#f472b6", sub: `sur ${d.range} jours` },
      { icon: "🎮", label: "Session moyenne", value: k.avgSessionMs, fmt: "dur", color: "#34d399", sub: `${num(k.disconnectionsRange)} sessions terminées` },
    ]);
    rings("rings-engagement", d.retention);

    // Cohortes
    const offsets = d.retention.offsets;
    const rows = d.retention.cohorts;
    $("t-cohorts").innerHTML = `<thead><tr><th>Cohorte</th><th>Joueurs</th>${offsets.map((o) => `<th>J${o}</th>`).join("")}</tr></thead><tbody>${rows
      .map(
        (r) => `<tr><td>${esc(r.label)}</td><td>${num(r.size)}</td>${r.values
          .map((v) => (v == null ? `<td class="muted">—</td>` : `<td><span class="cell" style="background:${hexA("#8b5cf6", 0.1 + (0.75 * v) / 100)}">${dec(v)}%</span></td>`))
          .join("")}</tr>`
      )
      .join("")}</tbody>`;

    const labels = d.daily.map((r) => r.label);
    bars("c-connections", labels, [
      { label: "Connexions", data: d.daily.map((r) => r.connections), color: "#34d399" },
      { label: "Déconnexions", data: d.daily.map((r) => r.disconnections), color: "#f472b6" },
    ]);
    line("c-playtime", labels, [{ label: "Temps de jeu moyen", data: d.daily.map((r) => Math.round(r.avgPlaytimeMs / 60000)), color: "#fbbf24" }], {
      yFormat: (v) => v + " min",
      tooltip: (c) => ` ${dur(c.parsed.y * 60000)} par joueur`,
    });

    // Carte de chaleur
    const max = Math.max(1, ...d.heatmap.flat());
    let html = `<div></div>${Array.from({ length: 24 }, (_, h) => `<div class="hh">${h % 3 === 0 ? pad(h) : ""}</div>`).join("")}`;
    d.heatmap.forEach((row, di) => {
      html += `<div class="hl">${DAYS[di]}</div>`;
      row.forEach((v, h) => {
        const a = v === 0 ? 0.05 : 0.15 + (0.85 * v) / max;
        const color = v / max > 0.75 ? hexA("#22d3ee", a) : hexA("#8b5cf6", a);
        html += `<div class="hc" style="background:${color};animation-delay:${(di * 24 + h) * 3}ms" title="${DAYS[di]} ${pad(h)}h — ${dec(v)} joueurs en moyenne"></div>`;
      });
    });
    $("heatmap").innerHTML = html;

    leaderboard("streaks", st.top, (r) => `🔥 ${r.streak} j`, (r) => r.streak, "Aucune série en cours");

    $("inactive-sub").textContent = `Sans connexion depuis plus de ${ina.thresholdDays} jours`;
    bars("c-inactive", labels, [{ label: "Devenus inactifs", data: d.daily.map((r) => r.becameInactive), color: "#f87171" }]);
    const lost = $("t-lost");
    lost.innerHTML = ina.lost.length
      ? `<thead><tr><th>Joueur réguliers perdus</th><th>Dernière venue</th><th class="num">Temps de jeu</th><th class="num">Jours actifs</th></tr></thead><tbody>${ina.lost
          .map((p) => `<tr data-uuid="${esc(p.uuid)}"><td>${playerRow(p)}</td><td class="muted">${ago(p.lastSeen)}</td><td class="num">${dur(p.playtimeMs)}</td><td class="num">${num(p.activeDays)}</td></tr>`)
          .join("")}</tbody>`
      : `<tbody><tr><td class="empty">Aucun joueur régulier perdu récemment 🎉</td></tr></tbody>`;
  }

  function renderEconomy() {
    const d = state.data;
    const e = d.economy;
    const days = d.daily;
    const today = days[days.length - 1] || {};
    renderKpis("kpi-economy", [
      { icon: "💰", label: "Argent généré", value: e.earned, fmt: "money", color: "#34d399", sub: `sur ${d.range} jours · aujourd'hui <b>${money(today.earned)}</b>` },
      { icon: "💸", label: "Argent dépensé", value: e.spent, fmt: "money", color: "#f87171", sub: `aujourd'hui <b>${money(today.spent)}</b>` },
      { icon: "⚖️", label: "Solde net injecté", value: e.net, fmt: "money", color: e.net >= 0 ? "#22d3ee" : "#fbbf24", sub: e.net >= 0 ? "Plus d'argent créé que dépensé" : "Plus d'argent dépensé que créé" },
      { icon: "📉", label: "Ratio dépensé / généré", raw: e.earned > 0 ? dec((100 * e.spent) / e.earned) + " %" : "—", color: "#8b5cf6", sub: "Équilibre de l'économie" },
    ]);
    bars("c-economy", days.map((r) => r.label), [
      { label: "Généré", data: days.map((r) => r.earned), color: "#34d399" },
      { label: "Dépensé", data: days.map((r) => r.spent), color: "#f87171" },
      { label: "Net", data: days.map((r) => Math.round((r.earned - r.spent) * 100) / 100), color: "#22d3ee", type: "line" },
    ], { tooltip: (c) => ` ${c.dataset.label} : ${money(c.parsed.y)}` });
    bars("c-eco-servers", e.servers.map((s) => s.server), [
      { label: "Généré", data: e.servers.map((s) => s.earned), color: "#34d399" },
      { label: "Dépensé", data: e.servers.map((s) => s.spent), color: "#f87171" },
    ], { tooltip: (c) => ` ${c.dataset.label} : ${money(c.parsed.y)}` });
    leaderboard("top-earners", e.topEarners, (r) => money(r.earned), (r) => r.earned, "Aucun gain enregistré");
    leaderboard("top-spenders", e.topSpenders, (r) => money(r.spent), (r) => r.spent, "Aucune dépense enregistrée");
  }

  function renderIslands() {
    const d = state.data;
    const is = d.islands;
    $("islands-missing").classList.toggle("hidden", is.available);
    if (!is.available) {
      const reports = d.servers.filter((s) => s.islandsStatus);
      $("islands-missing-detail").innerHTML = reports.length
        ? "État remonté par les serveurs : " + reports.map((s) => `<b>${esc(s.name)}</b> → ${esc(s.islandsStatus)}`).join(" · ")
        : "Aucun serveur Paper n'envoie encore de données au proxy : voir la page <b>Réseau & serveurs</b> et la commande <b>/nanalytics</b>.";
    }
    renderKpis("kpi-islands", [
      { icon: "🏝️", label: "Îles existantes", value: is.total, color: "#22d3ee", sub: `${num(is.playersWithIsland)} joueurs membres (${dec(is.playersWithIslandPct)} %)` },
      { icon: "✨", label: "Îles créées aujourd'hui", value: is.createdToday, color: "#8b5cf6", sub: `${num(is.created7)} sur 7 j · ${num(is.createdRange)} sur ${d.range} j` },
      { icon: "⛏️", label: "Chunks débloqués (total)", value: is.chunksTotal, fmt: "dec", color: "#34d399", sub: `+${dec(is.chunksRange)} sur ${d.range} jours` },
      { icon: "🏆", label: "Progression moyenne des joueurs", value: is.avgPlayerLevel, fmt: "dec", color: "#fbbf24", sub: "Niveau moyen de l'île des joueurs" },
      { icon: "📐", label: "Niveau moyen des îles", value: is.avgLevel, fmt: "dec", color: "#f472b6", sub: `Taille moyenne : <b>${dec(is.avgChunks)} chunks</b>` },
      is.avgMilestones > 0
        ? { icon: "🎯", label: "Succès moyens par île", value: is.avgMilestones, fmt: "dec", color: "#60a5fa", sub: `${dec(is.avgMembers)} membres en moyenne` }
        : { icon: "👥", label: "Membres moyens par île", value: is.avgMembers, fmt: "dec", color: "#60a5fa", sub: `${num(is.playersWithIsland)} joueurs au total` },
    ]);
    const labels = d.daily.map((r) => r.label);
    bars("c-islands", labels, [{ label: "Îles créées", data: d.daily.map((r) => r.islandsCreated), color: "#22d3ee" }]);
    line("c-chunks", labels, [{ label: "Chunks débloqués", data: d.daily.map((r) => r.chunks), color: "#34d399" }], { tooltip: (c) => ` ${dec(c.parsed.y)} chunks` });
    bars("c-levels", is.levels.map((l) => "Niv. " + l.level), [
      { label: "Îles", data: is.levels.map((l) => l.count), color: is.levels.map((_, i) => PALETTE[i % PALETTE.length]) },
    ]);
    const t = $("t-islands");
    t.innerHTML = is.top.length
      ? `<thead><tr><th>#</th><th>Île</th><th>Propriétaire</th><th class="num">Niveau</th><th class="num">Chunks</th><th class="num">Membres</th><th class="num">Succès</th></tr></thead><tbody>${is.top
          .map(
            (r, i) => `<tr><td>${i + 1}</td><td><b>${esc(r.name || "Sans nom")}</b> <span class="muted small">${esc(r.server)}</span></td><td>${esc(r.owner)}</td>
          <td class="num"><span class="tag violet">${num(r.level)}</span></td><td class="num">${dec(r.chunks)}</td><td class="num">${num(r.members)}</td><td class="num">${num(r.milestones)}</td></tr>`
          )
          .join("")}</tbody>`
      : `<tbody><tr><td class="empty">Aucune île pour le moment</td></tr></tbody>`;
  }

  function renderResources() {
    const d = state.data;
    const r = d.resources;
    const t = r.totals;
    renderKpis("kpi-resources", [
      { icon: "⛏️", label: "Blocs minés", value: t.BREAK || 0, color: "#fbbf24", sub: `sur ${d.range} jours` },
      { icon: "🧱", label: "Blocs posés", value: t.PLACE || 0, color: "#8b5cf6", sub: `sur ${d.range} jours` },
      { icon: "🛠️", label: "Objets fabriqués", value: t.CRAFT || 0, color: "#22d3ee", sub: `sur ${d.range} jours` },
      { icon: "📦", label: "Ressources utilisées", value: (t.BREAK || 0) + (t.PLACE || 0) + (t.CRAFT || 0), color: "#34d399", sub: "Total des actions" },
    ]);
    const draw = (id, rows, color) =>
      bars(id, rows.map((x) => prettyMaterial(x.material)), [{ label: "Quantité", data: rows.map((x) => x.count), color }], { horizontal: true });
    draw("c-res-break", r.break || [], "#fbbf24");
    draw("c-res-place", r.place || [], "#8b5cf6");
    draw("c-res-craft", r.craft || [], "#22d3ee");
  }

  function renderServerCards() {
    const d = state.data;
    const liveServers = state.live ? state.live.servers : [];
    const byName = Object.fromEntries((d ? d.servers : []).map((s) => [s.name, s]));
    const list = (liveServers.length ? liveServers : d ? d.servers : []).slice();
    if (d) d.servers.forEach((s) => { if (!list.some((x) => x.name === s.name) && s.collector !== "absent") list.push(s); });
    $("server-cards").innerHTML = list.length
      ? list
          .map((s, i) => {
            const status = s.status === "online" ? ["on", "En ligne", "green"] : s.status === "offline" ? ["off", "Hors ligne", "red"] : ["warn", "Inconnu", "amber"];
            const collector = { ok: ["green", "Collecteur actif"], stale: ["amber", "Collecteur silencieux"], absent: ["", "Collecteur absent"] }[s.collector] || ["", "—"];
            const tpsPct = s.tps != null ? Math.min(100, (s.tps / 20) * 100) : 0;
            const tpsColor = s.tps == null ? "#6b7394" : s.tps >= 18 ? "#34d399" : s.tps >= 15 ? "#fbbf24" : "#f87171";
            const memPct = s.maxMemory ? Math.min(100, (100 * s.usedMemory) / s.maxMemory) : 0;
            const playtime = byName[s.name] ? byName[s.name].playtimeMs : 0;
            return `<div class="server-card" style="animation-delay:${i * 60}ms">
              <h4><span><span class="dot ${status[0]}"></span>${esc(s.name)}</span><span class="tag ${status[2]}">${status[1]}</span></h4>
              <div class="players">${num(s.online)} <span class="muted small">joueur${s.online > 1 ? "s" : ""}</span></div>
              <div class="muted small">${esc(s.serverVersion || "Version inconnue")} · <span class="tag ${collector[0]}">${collector[1]}</span></div>
              <div class="stat-line"><span>TPS</span><b>${s.tps != null ? dec(s.tps) : "—"}</b></div>
              <div class="meter"><i style="background:${tpsColor}" data-w="${tpsPct}"></i></div>
              <div class="stat-line"><span>Mémoire</span><b>${s.maxMemory ? `${num(s.usedMemory / 1048576)} / ${num(s.maxMemory / 1048576)} Mo` : "—"}</b></div>
              <div class="meter"><i style="background:${memPct > 85 ? "#f87171" : "#8b5cf6"}" data-w="${memPct}"></i></div>
              <div class="stat-line"><span>MSPT</span><b>${s.mspt != null ? dec(s.mspt) + " ms" : "—"}</b></div>
              <div class="stat-line"><span>Chunks / entités</span><b>${s.loadedChunks != null ? num(s.loadedChunks) + " / " + num(s.entities) : "—"}</b></div>
              <div class="stat-line"><span>Temps de jeu (${d ? d.range : "?"} j)</span><b>${dur(playtime, true)}</b></div>
              ${diagnostics(s)}
            </div>`;
          })
          .join("")
      : `<div class="card empty">Aucun serveur enregistré sur le proxy</div>`;
    const meters = $("server-cards").querySelectorAll(".meter i");
    if (isSettled($("server-cards"))) meters.forEach((m) => (m.style.width = m.dataset.w + "%"));
    else requestAnimationFrame(() => meters.forEach((m) => (m.style.width = m.dataset.w + "%")));
  }

  /** Aide au diagnostic affichée sous chaque carte serveur. */
  function diagnostics(s) {
    const lines = [];
    if (s.registered === false) {
      lines.push(`<div class="diag warn">⚠ Aucun serveur « ${esc(s.name)} » dans velocity.toml : corrigez <b>server-name</b> dans le config.yml de NexoraAnalytics sur ce serveur.</div>`);
    } else if (s.collector === "absent") {
      lines.push(`<div class="diag warn">Plugin Paper non connecté. Sur ce serveur : installez NexoraAnalytics-Paper avec <b>server-name: "${esc(s.name)}"</b>, puis tapez <b>/nanalytics</b> pour le diagnostic.</div>`);
    } else if (s.collector === "stale") {
      lines.push(`<div class="diag warn">Plus de données depuis ${ago(s.lastHeartbeat)}. Tapez <b>/nanalytics</b> sur ce serveur.</div>`);
    }
    if (s.economyStatus) lines.push(`<div class="diag">💰 Économie : ${esc(s.economyStatus)}</div>`);
    if (s.islandsStatus) lines.push(`<div class="diag">🏝️ Îles : ${esc(s.islandsStatus)}</div>`);
    return lines.join("");
  }

  function renderNetwork() {
    const d = state.data;
    renderServerCards();
    const srv = d.servers.filter((s) => s.playtimeMs > 0);
    doughnut("c-srv-playtime", srv.map((s) => s.name), srv.map((s) => Math.round(s.playtimeMs / 60000)), {
      tooltip: (c) => ` ${c.label} : ${dur(c.parsed * 60000, true)}`,
    });
    doughnut("c-versions", d.versions.map((v) => v.version), d.versions.map((v) => v.players), { tooltip: (c) => ` ${c.label} : ${num(c.parsed)} joueurs` });
    doughnut("c-brands", d.brands.map((b) => b.brand), d.brands.map((b) => b.players), { tooltip: (c) => ` ${c.label} : ${num(c.parsed)} joueurs` });
    bars("c-peaks", d.daily.map((r) => r.label), [{ label: "Pic de joueurs", data: d.daily.map((r) => r.peak), color: "#fbbf24" }]);
  }

  function renderIncidents() {
    const d = state.data;
    const inc = d.incidents;
    const l = inc.last24h;
    renderKpis("kpi-incidents", [
      { icon: "💥", label: "Crashs (24 h)", value: (l.CRASH || 0) + (l.FATAL || 0), color: "#f87171", sub: "Arrêts anormaux et erreurs fatales" },
      { icon: "⚠️", label: "Erreurs (24 h)", value: l.ERROR || 0, color: "#fbbf24", sub: "Erreurs console des serveurs" },
      { icon: "🔌", label: "Coupures (24 h)", value: l.DOWN || 0, color: "#fb923c", sub: `${num(l.UP || 0)} retour(s) en ligne` },
      { icon: "🩺", label: "Incidents sur la période", value: d.daily.reduce((s, r) => s + r.errors, 0), color: "#8b5cf6", sub: `sur ${d.range} jours` },
    ]);
    bars("c-incidents", d.daily.map((r) => r.label), [{ label: "Incidents", data: d.daily.map((r) => r.errors), color: "#f87171" }]);
    renderIncidentList();
  }

  function renderIncidentList() {
    const list = state.data.incidents.recent.filter((i) => {
      const f = state.incidentFilter;
      if (!f) return true;
      if (f === "CRASH") return i.type === "CRASH" || i.type === "FATAL";
      if (f === "DOWN") return i.type === "DOWN" || i.type === "UP";
      return i.type === f;
    });
    $("incident-list").innerHTML = list.length
      ? list
          .map((i, idx) => {
            const t = INCIDENT_TYPES[i.type] || { label: i.type, cls: "", icon: "•" };
            const open = state.openIncidents.has(i.id);
            return `<div class="incident" data-id="${i.id}" style="animation-delay:${Math.min(idx, 15) * 30}ms">
              <div class="incident-head"><span>${t.icon}</span><span class="tag ${t.cls}">${t.label}</span>
                <div class="incident-msg"><b>${esc(i.server)}</b> — ${esc(i.message)}</div><span class="muted small" title="${dateTime(i.ts)}">${ago(i.ts)}</span></div>
              ${open ? `<pre>${esc(dateTime(i.ts) + "\n" + (i.details || i.message))}</pre>` : ""}
            </div>`;
          })
          .join("")
      : `<div class="empty">Aucun incident 🎉</div>`;
  }

  // ------------------------------------------------------------------ joueurs
  async function loadPlayers() {
    const p = state.players;
    const data = await api(`/api/players?q=${encodeURIComponent(p.q)}&sort=${p.sort}&page=${p.page}`);
    $("players-total").textContent = `${num(data.total)} joueur${data.total > 1 ? "s" : ""}${p.q ? " trouvé(s)" : " enregistrés"}`;
    $("t-players").innerHTML = data.rows.length
      ? `<thead><tr><th>Joueur</th><th>Statut</th><th>Première connexion</th><th>Dernière connexion</th><th class="num">Temps de jeu</th>
          <th class="num">Connexions</th><th class="num">Streak</th><th>Version</th><th>Serveur</th></tr></thead><tbody>${data.rows
          .map(
            (r) => `<tr data-uuid="${esc(r.uuid)}"><td>${playerRow(r)}</td>
            <td>${r.online ? '<span class="tag green"><span class="dot on"></span>En ligne</span>' : `<span class="muted">${ago(r.lastSeen)}</span>`}</td>
            <td>${date(r.firstSeen)}</td><td>${dateTime(r.lastSeen)}</td><td class="num">${dur(r.playtimeMs, true)}</td>
            <td class="num">${num(r.sessions)}</td><td class="num">${r.streak > 0 ? "🔥 " + r.streak : "—"}</td>
            <td><span class="tag cyan">${esc(r.version || "?")}</span></td><td><span class="tag violet">${esc(r.lastServer || "—")}</span></td></tr>`
          )
          .join("")}</tbody>`
      : `<tbody><tr><td class="empty">Aucun joueur trouvé</td></tr></tbody>`;
    const pages = Math.max(1, Math.ceil(data.total / data.size));
    $("players-pager").innerHTML = `<button data-p="${p.page - 1}" ${p.page <= 0 ? "disabled" : ""}>← Précédent</button>
      <span>Page ${p.page + 1} / ${pages}</span><button data-p="${p.page + 1}" ${p.page + 1 >= pages ? "disabled" : ""}>Suivant →</button>`;
  }

  async function openPlayer(uuid) {
    if (!uuid) return;
    $("modal").classList.remove("hidden");
    $("modal-body").innerHTML = `<div class="skeleton" style="height:80px"></div><div class="skeleton" style="height:180px;margin-top:16px"></div>`;
    let p;
    try {
      p = await api(`/api/player?uuid=${encodeURIComponent(uuid)}`);
    } catch (e) {
      $("modal-body").innerHTML = `<div class="empty">Joueur introuvable</div>`;
      return;
    }
    const byDay = Object.fromEntries(p.calendar.map((c) => [c.day, c.playtimeMs]));
    const maxDay = Math.max(1, ...p.calendar.map((c) => c.playtimeMs));
    const today = new Date();
    const start = new Date(today);
    start.setDate(start.getDate() - 111 - ((today.getDay() + 6) % 7));
    let cells = "";
    for (let dt = new Date(start); dt <= today; dt.setDate(dt.getDate() + 1)) {
      const key = `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}`;
      const v = byDay[key];
      const style = v !== undefined ? `background:${hexA("#8b5cf6", 0.25 + (0.75 * v) / maxDay)}` : "";
      cells += `<i style="${style}" title="${pad(dt.getDate())}/${pad(dt.getMonth() + 1)} — ${v !== undefined ? dur(v) : "absent"}"></i>`;
    }
    $("modal-body").innerHTML = `
      <div class="profile"><img src="${avatar(p.uuid, 72)}" alt="">
        <div><h2>${esc(p.name)}</h2>
          <div class="muted small mono">${esc(p.uuid)}</div>
          <div style="margin-top:6px">${p.online ? `<span class="tag green"><span class="dot on"></span>En ligne sur ${esc(p.currentServer || "?")}</span>` : `<span class="tag">Vu ${ago(p.lastSeen)}</span>`}
          <span class="tag cyan">${esc(p.version || "?")}</span> <span class="tag">${esc(p.brand)}</span></div></div></div>
      <div class="mini-stats">
        <div class="mini-stat"><div class="l">🌍 Première connexion</div><div class="v">${dateTime(p.firstSeen)}</div></div>
        <div class="mini-stat"><div class="l">🕐 Dernière connexion</div><div class="v">${dateTime(p.lastSeen)}</div></div>
        <div class="mini-stat"><div class="l">⏱️ Temps de jeu</div><div class="v">${dur(p.playtimeMs)}</div></div>
        <div class="mini-stat"><div class="l">🚪 Connexions</div><div class="v">${num(p.sessions)}</div></div>
        <div class="mini-stat"><div class="l">🔥 Streak actuel / record</div><div class="v">${num(p.streak)} j / ${num(p.bestStreak)} j</div></div>
        <div class="mini-stat"><div class="l">📅 Jours actifs</div><div class="v">${num(p.activeDays)}</div></div>
        <div class="mini-stat"><div class="l">💰 Gagné / dépensé</div><div class="v">${money(p.earned)} / ${money(p.spent)}</div></div>
        <div class="mini-stat"><div class="l">🔌 Dernier serveur</div><div class="v">${esc(p.lastServer || "—")}</div></div>
      </div>
      <div class="section-title">Activité — 16 dernières semaines</div>
      <div class="calendar">${cells}</div>
      <div class="section-title">Dernières sessions</div>
      <div class="table-wrap"><table class="table"><thead><tr><th>Début</th><th>Fin</th><th class="num">Durée</th><th>Version</th></tr></thead><tbody>
        ${p.recentSessions
          .map((s) => `<tr><td>${dateTime(s.start)}</td><td>${s.end ? dateTime(s.end) : '<span class="tag green">En cours</span>'}</td>
          <td class="num">${dur((s.end || Date.now()) - s.start)}</td><td>${esc(s.version || "?")}</td></tr>`)
          .join("")}</tbody></table></div>`;
  }

  // ------------------------------------------------------------------ navigation & chargement
  const RENDERERS = {
    overview: renderOverview,
    engagement: renderEngagement,
    economy: renderEconomy,
    islands: renderIslands,
    resources: renderResources,
    network: renderNetwork,
    incidents: renderIncidents,
  };

  /** Vrai une fois la page affichée et animée : les rafraîchissements suivants ne rejouent pas les animations. */
  function isSettled(el) {
    const page = el.closest(".page");
    return !!page && page.classList.contains("settled");
  }

  function renderPage() {
    if (state.page === "players") return;
    if (!state.data) return;
    try {
      RENDERERS[state.page]();
    } catch (e) {
      console.error(e);
    }
    const section = $("page-" + state.page);
    if (!section.classList.contains("settled")) setTimeout(() => section.classList.add("settled"), 1600);
  }

  function route() {
    const page = (location.hash || "#overview").slice(1);
    state.page = PAGES[page] ? page : "overview";
    document.querySelectorAll(".page").forEach((p) => p.classList.toggle("active", p.id === "page-" + state.page));
    document.querySelectorAll("#nav a").forEach((a) => a.classList.toggle("active", a.dataset.page === state.page));
    $("page-title").textContent = PAGES[state.page];
    $("sidebar").classList.remove("open");
    if (state.page === "players") loadPlayers().catch(() => {});
    else renderPage();
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  async function loadDashboard(manual) {
    const btn = $("refresh");
    btn.classList.add("spin");
    try {
      state.data = await api(`/api/dashboard?range=${state.range}`);
      const l = state.data.incidents.last24h;
      const count = (l.CRASH || 0) + (l.FATAL || 0) + (l.ERROR || 0) + (l.DOWN || 0);
      $("incident-badge").textContent = count > 99 ? "99+" : count;
      $("incident-badge").classList.toggle("hidden", count === 0);
      const t = new Date(state.data.generatedAt);
      $("updated").textContent = `Mis à jour à ${pad(t.getHours())}:${pad(t.getMinutes())}:${pad(t.getSeconds())} · ${state.data.timezone}`;
      renderPage();
      if (manual) toast("Données actualisées");
    } catch (e) {
      if (e.message !== "unauthorized") $("updated").textContent = "Impossible de charger les données — nouvel essai bientôt";
    } finally {
      setTimeout(() => btn.classList.remove("spin"), 400);
    }
  }

  async function loadLive() {
    try {
      state.live = await api("/api/live");
      renderLive();
    } catch (e) {
      /* nouvel essai au prochain cycle */
    }
  }

  function showLogin() {
    $("app").classList.add("hidden");
    $("login").classList.remove("hidden");
    clearInterval(showLogin.t1);
    clearInterval(showLogin.t2);
  }

  async function start() {
    const params = new URLSearchParams(location.search);
    if (params.get("error")) {
      $("login-error").classList.remove("hidden");
      history.replaceState(null, "", location.pathname + location.hash);
    }
    let me;
    try {
      me = await (await fetch("/api/me", { credentials: "same-origin" })).json();
    } catch {
      me = { authenticated: false };
    }
    if (!me.authenticated) {
      showLogin();
      return;
    }
    $("login").classList.add("hidden");
    $("app").classList.remove("hidden");
    $("admin-name").textContent = me.admin;
    if (me.admin && me.admin !== "Console") {
      $("admin-avatar").innerHTML = `<img src="https://mc-heads.net/avatar/${encodeURIComponent(me.admin)}/34" alt="">`;
    } else {
      $("admin-avatar").textContent = (me.admin || "?").charAt(0);
    }

    const saved = parseInt(ls.get("nexora.range"), 10);
    if ([7, 14, 30, 90].includes(saved)) state.range = saved;
    document.querySelectorAll("#range button").forEach((b) => b.classList.toggle("active", +b.dataset.range === state.range));

    bindEvents();
    route();
    await Promise.all([loadLive(), loadDashboard()]);
    showLogin.t1 = setInterval(loadLive, 10000);
    showLogin.t2 = setInterval(() => loadDashboard(false), 60000);
  }

  function bindEvents() {
    window.addEventListener("hashchange", route);
    $("range").addEventListener("click", (e) => {
      const b = e.target.closest("button");
      if (!b) return;
      state.range = +b.dataset.range;
      ls.set("nexora.range", state.range);
      document.querySelectorAll("#range button").forEach((x) => x.classList.toggle("active", x === b));
      loadDashboard(false);
    });
    $("refresh").addEventListener("click", () => {
      loadDashboard(true);
      loadLive();
      if (state.page === "players") loadPlayers().catch(() => {});
    });
    $("menu-btn").addEventListener("click", () => $("sidebar").classList.toggle("open"));
    $("logout").addEventListener("click", async () => {
      try { await fetch("/api/logout", { method: "POST", credentials: "same-origin" }); } catch { /* ignoré */ }
      showLogin();
    });

    let searchTimer;
    $("player-search").addEventListener("input", (e) => {
      clearTimeout(searchTimer);
      searchTimer = setTimeout(() => {
        state.players.q = e.target.value.trim();
        state.players.page = 0;
        loadPlayers().catch(() => {});
      }, 250);
    });
    $("player-sort").addEventListener("change", (e) => {
      state.players.sort = e.target.value;
      state.players.page = 0;
      loadPlayers().catch(() => {});
    });
    $("players-pager").addEventListener("click", (e) => {
      const b = e.target.closest("button[data-p]");
      if (!b || b.disabled) return;
      state.players.page = +b.dataset.p;
      loadPlayers().catch(() => {});
    });

    // Ouverture de la fiche joueur depuis n'importe quel tableau ou classement.
    document.addEventListener("click", (e) => {
      const row = e.target.closest("[data-uuid]");
      if (row && row.dataset.uuid && !e.target.closest(".modal")) openPlayer(row.dataset.uuid);
      if (e.target.closest("[data-close]")) $("modal").classList.add("hidden");
    });
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape") $("modal").classList.add("hidden");
    });

    $("incident-filter").addEventListener("click", (e) => {
      const b = e.target.closest("button");
      if (!b) return;
      state.incidentFilter = b.dataset.type;
      document.querySelectorAll("#incident-filter button").forEach((x) => x.classList.toggle("active", x === b));
      renderIncidentList();
    });
    $("incident-list").addEventListener("click", (e) => {
      const head = e.target.closest(".incident-head");
      if (!head) return;
      const id = +head.parentElement.dataset.id;
      if (state.openIncidents.has(id)) state.openIncidents.delete(id);
      else state.openIncidents.add(id);
      renderIncidentList();
    });
  }

  start();
})();
