// Complexity Analyzer — Viewer UI
// Virtual scrolling + lazy section loading + URL state.

import {
  CabinDatabase, ITEM_FLAG, MOB_FLAG
} from "./cabin.js";

const state = {
  db: null,
  itemsView: [],
  mobsView: [],
  filters: {
    query: "",
    sort: "complexity-desc",
    category: "",
    finiteOnly: false,
    hasRecipe: false,
  },
  mobFilters: {
    query: "",
    sort: "combat-desc",
    bossOnly: false,
  },
};

const $ = (id) => document.getElementById(id);
const fmt = new Intl.NumberFormat("en-US", { maximumFractionDigits: 2 });
const fmtInt = new Intl.NumberFormat("en-US");

function setStatus(cls, text) {
  const dot = $("status-dot");
  dot.className = "dot " + cls;
  $("status-text").textContent = text;
}

function humanBytes(n) {
  if (n < 1024) return n + " B";
  let k = n / 1024;
  if (k < 1024) return k.toFixed(1) + " KB";
  let m = k / 1024;
  if (m < 1024) return m.toFixed(1) + " MB";
  return (m / 1024).toFixed(2) + " GB";
}

function formatComplexity(c) {
  if (c < 0) return "—";
  if (!isFinite(c)) return "∞";
  if (c === 0) return "0";
  if (c >= 1e6) return c.toExponential(2);
  return fmt.format(c);
}

function categoryColor(cat) {
  return `var(--cat-${cat || "Uncalculable"})`;
}

function itemFlagChips(item) {
  const out = [];
  const f = item.flags;
  if (f & ITEM_FLAG.HAS_CYCLE) out.push(`<span class="flag cycle" title="has cycle">⟲</span>`);
  if (f & ITEM_FLAG.IS_INFINITE) out.push(`<span class="flag infinite" title="unobtainable">∞</span>`);
  if (!(f & ITEM_FLAG.HAS_RECIPE)) out.push(`<span class="flag no-recipe" title="no recipe">∅</span>`);
  if (f & ITEM_FLAG.IS_HARDCODED) out.push(`<span class="flag hardcoded" title="hardcoded">H</span>`);
  return out.join("");
}

function mobFlagChips(mob) {
  const out = [];
  if (mob.flags & MOB_FLAG.BOSS) out.push(`<span class="flag boss" title="boss">B</span>`);
  if (mob.flags & MOB_FLAG.MINIBOSS) out.push(`<span class="flag miniboss" title="mini-boss">m</span>`);
  return out.join("");
}

// ===== Tabs =====

function showTab(name) {
  document.querySelectorAll(".tab").forEach(t => t.classList.toggle("active", t.dataset.tab === name));
  document.querySelectorAll(".panel").forEach(p => p.hidden = p.id !== "tab-" + name);
  if (name === "overview") renderOverview();
  if (name === "items") renderItems();
  if (name === "mobs") renderMobs();
  if (name === "categories") renderCategories();
  if (name === "recipes") renderRecipes();
  if (name === "search") $("search-input").focus();
  updateUrlFromState();
}

document.querySelectorAll(".tab").forEach(t => {
  t.addEventListener("click", () => showTab(t.dataset.tab));
});

// ===== URL state =====

function updateUrlFromState() {
  const activeTab = document.querySelector(".tab.active")?.dataset.tab || "items";
  const params = new URLSearchParams();
  const url = new URL(location.href);
  const token = url.searchParams.get("token");
  if (token) params.set("token", token);
  params.set("tab", activeTab);
  if (state.filters.query) params.set("q", state.filters.query);
  if (state.filters.sort !== "complexity-desc") params.set("sort", state.filters.sort);
  if (state.filters.category) params.set("cat", state.filters.category);
  if (state.filters.finiteOnly) params.set("finite", "1");
  if (state.filters.hasRecipe) params.set("recipe", "1");
  const newHash = "#" + params.toString();
  if (location.hash !== newHash) history.replaceState(null, "", newHash);
}

function loadStateFromUrl() {
  const params = new URLSearchParams(location.hash.replace(/^#/, ""));
  if (params.has("q")) state.filters.query = params.get("q");
  if (params.has("sort")) state.filters.sort = params.get("sort");
  if (params.has("cat")) state.filters.category = params.get("cat");
  if (params.has("finite")) state.filters.finiteOnly = true;
  if (params.has("recipe")) state.filters.hasRecipe = true;
  return params.get("tab") || "items";
}

// ===== Overview =====

function renderOverview() {
  if (!state.db) return;
  const m = state.db.meta;
  const grid = $("overview-stats");
  grid.innerHTML = "";
  const stats = [
    { label: "Items", value: fmtInt.format(m.itemCount) },
    { label: "Mobs", value: fmtInt.format(m.mobCount) },
    { label: "Fluids", value: fmtInt.format(m.fluidCount) },
    { label: "Recipes", value: fmtInt.format(m.recipeCount) },
    { label: "Valid items", value: fmtInt.format(m.validItems) },
    { label: "Unobtainable", value: fmtInt.format(m.infiniteItems) },
  ];
  for (const s of stats) {
    const card = document.createElement("div");
    card.className = "card";
    card.innerHTML = `<div class="value">${s.value}</div><div class="label">${s.label}</div>`;
    grid.appendChild(card);
  }
  $("overview-hash").textContent = "0x" + state.db.file.fileHash.toString(16);
  $("overview-generated").textContent = m.timestampMs
    ? new Date(m.timestampMs).toLocaleString()
    : m.timestampStr;
  $("overview-viewer").textContent = "1.1 (format 0x" + state.db.file.version.toString(16) + ")";
  $("meta-subtitle").textContent = m.serverName
    ? `${m.serverName} · ${m.modId} ${m.modVersion}`
    : `${m.modId} ${m.modVersion}`;
}

// ===== Items =====

function applyItemFilters() {
  const items = state.db.items;
  const q = state.filters.query.trim().toLowerCase();
  const cat = state.filters.category;
  const finite = state.filters.finiteOnly;
  const recipe = state.filters.hasRecipe;
  const list = [];
  const n = items.count;
  for (let i = 0; i < n; i++) {
    const it = items.get(i);
    if (cat && it.categoryName !== cat) continue;
    if (finite && !isFinite(it.complexity)) continue;
    if (recipe && !(it.flags & ITEM_FLAG.HAS_RECIPE)) continue;
    if (q) {
      const hay = (it.name + " " + it.id + " " + it.categoryName).toLowerCase();
      if (!hay.includes(q)) continue;
    }
    list.push(it);
  }
  const [field, dir] = state.filters.sort.split("-");
  const sign = dir === "asc" ? 1 : -1;
  const getVal = {
    complexity: x => (isFinite(x.complexity) ? x.complexity : Number.MAX_VALUE),
    name: x => x.name,
    id: x => x.id,
    depth: x => x.depth,
    usage: x => x.usageCount,
  }[field] || (x => x.complexity);
  list.sort((a, b) => {
    const av = getVal(a), bv = getVal(b);
    if (typeof av === "number") return sign * (av - bv);
    return sign * String(av).localeCompare(String(bv));
  });
  state.itemsView = list;
}

function renderItems() {
  if (!state.db) return;
  applyItemFilters();
  $("items-count").textContent = `${fmtInt.format(state.itemsView.length)} / ${fmtInt.format(state.db.items.count)} items`;

  const cs = $("items-category");
  if (cs.options.length <= 1) {
    for (const cat of state.db.categories) {
      const o = document.createElement("option");
      o.value = cat.name;
      o.textContent = `${cat.name} (${cat.items.length})`;
      cs.appendChild(o);
    }
  }
  cs.value = state.filters.category;
  $("items-query").value = state.filters.query;
  $("items-sort").value = state.filters.sort;
  $("items-finite-only").checked = state.filters.finiteOnly;
  $("items-with-recipe").checked = state.filters.hasRecipe;

  virtualize({
    viewport: $("items-viewport"),
    rows: $("items-rows"),
    spacer: $("items-spacer"),
    itemCount: state.itemsView.length,
    itemHeight: 28,
    renderRow: (absIndex) => {
      const it = state.itemsView[absIndex];
      const el = document.createElement("div");
      el.className = "row items-grid";
      el.innerHTML = `
        <span class="idx">${absIndex + 1}</span>
        <span class="id" title="${it.id}">${it.id}</span>
        <span>${escapeHtml(it.name)}</span>
        <span class="num" style="color:${categoryColor(it.categoryName)}">${formatComplexity(it.complexity)}</span>
        <span class="num">${fmtInt.format(it.depth)}</span>
        <span class="num">${fmtInt.format(it.usageCount)}</span>
        <span><span class="category-pill" style="background-color:${categoryColor(it.categoryName)}20;color:${categoryColor(it.categoryName)};border:1px solid ${categoryColor(it.categoryName)}40">${it.categoryName}</span></span>
        <span class="flags">${itemFlagChips(it)}</span>
      `;
      el.addEventListener("click", () => openItemDialog(it.index));
      return el;
    }
  });
  updateUrlFromState();
}

// ===== Mobs =====

function applyMobFilters() {
  const mobs = state.db.mobs;
  const q = state.mobFilters.query.trim().toLowerCase();
  const bossOnly = state.mobFilters.bossOnly;
  const list = [];
  for (let i = 0; i < mobs.count; i++) {
    const m = mobs.get(i);
    if (bossOnly && !(m.flags & MOB_FLAG.BOSS)) continue;
    if (q) {
      const hay = (m.name + " " + m.id + " " + m.categoryName).toLowerCase();
      if (!hay.includes(q)) continue;
    }
    list.push(m);
  }
  const [field, dir] = state.mobFilters.sort.split("-");
  const sign = dir === "asc" ? 1 : -1;
  const getVal = {
    combat: m => m.combatPower,
    threat: m => m.threat,
    rarity: m => m.rarity,
    hp: m => m.health,
    name: m => m.name,
  }[field] || (m => m.combatPower);
  list.sort((a, b) => {
    const av = getVal(a), bv = getVal(b);
    if (typeof av === "number") return sign * (av - bv);
    return sign * String(av).localeCompare(String(bv));
  });
  state.mobsView = list;
}

function renderMobs() {
  if (!state.db) return;
  applyMobFilters();
  $("mobs-count").textContent = `${fmtInt.format(state.mobsView.length)} / ${fmtInt.format(state.db.mobs.count)} mobs`;
  $("mobs-query").value = state.mobFilters.query;
  $("mobs-sort").value = state.mobFilters.sort;
  $("mobs-boss-only").checked = state.mobFilters.bossOnly;
  virtualize({
    viewport: $("mobs-viewport"),
    rows: $("mobs-rows"),
    spacer: $("mobs-spacer"),
    itemCount: state.mobsView.length,
    itemHeight: 28,
    renderRow: (absIndex) => {
      const m = state.mobsView[absIndex];
      const el = document.createElement("div");
      el.className = "row mobs-grid";
      el.innerHTML = `
        <span class="idx">${absIndex + 1}</span>
        <span>${escapeHtml(m.name)}</span>
        <span class="id" title="${m.id}">${m.id}</span>
        <span class="num">${fmt.format(m.health)}</span>
        <span class="num">${fmt.format(m.damage)}</span>
        <span class="num">${fmt.format(m.armor)}</span>
        <span class="num">${fmt.format(m.combatPower)}</span>
        <span class="num">${fmt.format(m.rarity)}</span>
        <span class="flags">${mobFlagChips(m)}</span>
      `;
      el.addEventListener("click", () => openMobDialog(m.index));
      return el;
    }
  });
}

// ===== Virtual scroll engine =====

function virtualize({ viewport, rows, spacer, itemCount, itemHeight, renderRow }) {
  spacer.style.height = (itemCount * itemHeight) + "px";
  const render = () => {
    const top = viewport.scrollTop;
    const h = viewport.clientHeight;
    const first = Math.max(0, Math.floor(top / itemHeight) - 4);
    const last = Math.min(itemCount, Math.ceil((top + h) / itemHeight) + 4);
    rows.style.transform = `translateY(${first * itemHeight}px)`;
    rows.innerHTML = "";
    const frag = document.createDocumentFragment();
    for (let i = first; i < last; i++) {
      const row = renderRow(i);
      row.style.height = itemHeight + "px";
      frag.appendChild(row);
    }
    rows.appendChild(frag);
  };
  viewport._vtHandler && viewport.removeEventListener("scroll", viewport._vtHandler);
  viewport._vtHandler = render;
  viewport.addEventListener("scroll", render, { passive: true });
  render();
}

// ===== Item dialog =====

async function openItemDialog(itemIndex) {
  const item = state.db.items.get(itemIndex);
  if (!item) return;
  const dlg = $("item-dialog");
  $("item-dialog-title").innerHTML = `${escapeHtml(item.name)} <code class="mono-code" style="font-size:11px;margin-left:8px">${escapeHtml(item.id)}</code>`;
  const body = $("item-dialog-body");
  body.innerHTML = `<div class="hint">Loading sources and recipes…</div>`;
  dlg.showModal();

  try {
    const [base, sources, recipes, usage] = await Promise.all([
      state.db.getItemBaseData(itemIndex),
      state.db.getItemSources(itemIndex),
      state.db.getItemRecipes(itemIndex),
      state.db.getItemUsage(itemIndex),
    ]);

    const sections = [];
    sections.push(renderItemMain(item));
    sections.push(renderBaseData(base));
    sections.push(renderAltSources(sources));
    sections.push(renderRecipeList(recipes));
    sections.push(renderUsage(usage));
    body.innerHTML = sections.filter(Boolean).join("");
    body.querySelectorAll(".ingredient[data-idx]").forEach(el => {
      el.addEventListener("click", () => {
        const i = parseInt(el.dataset.idx, 10);
        if (!isNaN(i)) {
          dlg.close();
          openItemDialog(i);
        }
      });
    });
  } catch (e) {
    body.innerHTML = `<div class="card" style="color:var(--err)">Failed to load item details: ${escapeHtml(String(e))}</div>`;
  }
}

function renderItemMain(item) {
  return `
    <dl class="detail-grid">
      <dt>Complexity</dt><dd style="color:${categoryColor(item.categoryName)};font-weight:600">${formatComplexity(item.complexity)}</dd>
      <dt>Category</dt><dd><span class="category-pill" style="background-color:${categoryColor(item.categoryName)}20;color:${categoryColor(item.categoryName)};border:1px solid ${categoryColor(item.categoryName)}40">${item.categoryName}</span></dd>
      <dt>Depth</dt><dd>${fmtInt.format(item.depth)}</dd>
      <dt>Total ingredients</dt><dd>${fmtInt.format(item.totalIngredients)}</dd>
      <dt>Used in</dt><dd>${fmtInt.format(item.usageCount)} recipes</dd>
      <dt>Flags</dt><dd class="flags">${itemFlagChips(item) || '<span class="hint">—</span>'}</dd>
      ${item.errorMessage ? `<dt>Error</dt><dd style="color:var(--err)">${escapeHtml(item.errorMessage)}</dd>` : ""}
    </dl>
  `;
}

function renderBaseData(base) {
  if (!base) return "";
  const meta = Object.entries(base.metadata || {});
  return `
    <div class="section-sub">
      <h3>Base resource</h3>
      <div class="source-item">
        <div><strong>${escapeHtml(base.sourceType)}</strong>${base.isOverride ? ` <span class="flag hardcoded" title="overridden by ${escapeHtml(base.overrideModId)}">override</span>` : ""}</div>
        <div class="hint">${escapeHtml(base.details || base.sourceSpecifier || "")}</div>
        <div>Base factor: <strong>${fmt.format(base.baseFactor)}</strong></div>
        ${base.ingredients.length > 0 ? `
          <div class="ingredient-list">${base.ingredients.map(ing => renderIngredientRef(ing.itemIndex, ing.amount)).join("")}</div>
        ` : ""}
        ${meta.length > 0 ? `<div class="hint" style="margin-top:4px">${meta.map(([k,v]) => `<code>${escapeHtml(k)}=${escapeHtml(v)}</code>`).join(" ")}</div>` : ""}
      </div>
    </div>
  `;
}

function renderAltSources(sources) {
  if (!sources || sources.length === 0) return "";
  return `
    <div class="section-sub">
      <h3>All alternative sources (${sources.length})</h3>
      ${sources.map(s => `
        <div class="source-item">
          <div><strong>${escapeHtml(s.sourceType)}</strong> — base: ${fmt.format(s.baseFactor)}, est. cost: ${isFinite(s.estimatedCost) ? fmt.format(s.estimatedCost) : "∞"}</div>
          ${s.details ? `<div class="hint">${escapeHtml(s.details)}</div>` : ""}
          ${s.ingredients.length > 0 ? `
            <div class="ingredient-list">${s.ingredients.map(ing => renderIngredientRef(ing.itemIndex, ing.amount)).join("")}</div>
          ` : ""}
        </div>
      `).join("")}
    </div>
  `;
}

function renderRecipeList(recipes) {
  if (!recipes || recipes.length === 0) return "";
  return `
    <div class="section-sub">
      <h3>Recipes (${recipes.length})</h3>
      ${recipes.map(r => `
        <div class="recipe-card ${r.category === 0 ? "primary" : ""}">
          <div><strong>${escapeHtml(r.recipeType)}</strong> × ${r.resultCount}${r.recipeMultiplier !== 1 ? ` (mult ${fmt.format(r.recipeMultiplier)})` : ""}</div>
          ${r.ingredients.length > 0 ? `
            <div class="hint">Items needed:</div>
            <div class="ingredient-list">
              ${r.ingredients.flatMap(slot =>
                slot.variants.map(v => renderIngredientRef(v, slot.count))
              ).join("")}
            </div>` : ""}
          ${r.fluidIngredients.length > 0 ? `
            <div class="hint">Fluids:</div>
            <div class="ingredient-list">
              ${r.fluidIngredients.flatMap(slot =>
                slot.variants.map(v => `<span class="ingredient">fluid #${v} × ${slot.amount}</span>`)
              ).join("")}
            </div>` : ""}
          ${r.chemicalIngredients.length > 0 ? `
            <div class="hint">Chemicals:</div>
            <div class="ingredient-list">
              ${r.chemicalIngredients.map(c => `<span class="ingredient">${escapeHtml(c.id)} × ${c.amount}</span>`).join("")}
            </div>` : ""}
        </div>
      `).join("")}
    </div>
  `;
}

function renderUsage(usageList) {
  if (!usageList || usageList.length === 0) return "";
  const sample = usageList.slice(0, 30);
  return `
    <div class="section-sub">
      <h3>Used as ingredient in (${usageList.length})</h3>
      <div class="ingredient-list">
        ${sample.map(i => renderIngredientRef(i, null)).join("")}
        ${usageList.length > 30 ? `<span class="hint">...and ${usageList.length - 30} more</span>` : ""}
      </div>
    </div>
  `;
}

function renderIngredientRef(itemIndex, amount) {
  if (itemIndex < 0) return `<span class="ingredient" style="color:var(--text-muted)">?${amount ? ` × ${amount}` : ""}</span>`;
  const it = state.db.items.get(itemIndex);
  if (!it) return `<span class="ingredient" style="color:var(--text-muted)">#${itemIndex}</span>`;
  return `<span class="ingredient" data-idx="${itemIndex}" title="${escapeHtml(it.id)} — complexity ${formatComplexity(it.complexity)}">${escapeHtml(it.name)}${amount ? ` × ${fmt.format(amount)}` : ""}</span>`;
}

// ===== Mob dialog =====

async function openMobDialog(mobIndex) {
  const mob = state.db.mobs.get(mobIndex);
  if (!mob) return;
  const dlg = $("mob-dialog");
  $("mob-dialog-title").innerHTML = `${escapeHtml(mob.name)} <code class="mono-code" style="font-size:11px;margin-left:8px">${escapeHtml(mob.id)}</code>`;
  const body = $("mob-dialog-body");
  body.innerHTML = `<div class="hint">Loading drops…</div>`;
  dlg.showModal();

  try {
    const drops = await state.db.getMobDrops(mobIndex);
    body.innerHTML = `
      <dl class="detail-grid">
        <dt>Category</dt><dd>${escapeHtml(mob.categoryName)}</dd>
        <dt>Health</dt><dd>${fmt.format(mob.health)}</dd>
        <dt>Damage</dt><dd>${fmt.format(mob.damage)}</dd>
        <dt>Armor</dt><dd>${fmt.format(mob.armor)}</dd>
        <dt>Survivability</dt><dd>${fmt.format(mob.survivability)}</dd>
        <dt>Threat</dt><dd>${fmt.format(mob.threat)}</dd>
        <dt>Combat power</dt><dd>${fmt.format(mob.combatPower)}</dd>
        <dt>Rarity</dt><dd>${fmt.format(mob.rarity)}</dd>
        <dt>Flags</dt><dd class="flags">${mobFlagChips(mob) || '<span class="hint">—</span>'}</dd>
      </dl>
      <div class="section-sub">
        <h3>Drops (${drops.length})</h3>
        ${drops.length === 0
          ? `<div class="hint">No drops recorded.</div>`
          : drops.map(d => `
            <div class="drop-row">
              <strong>${escapeHtml(d.itemName || "?")}</strong>
              <span class="hint"> — ${fmt.format(d.yieldPerKill)}/kill${d.killMethod ? ` · ${escapeHtml(d.killMethod)}` : ""}</span>
              ${d.itemId ? `<div class="mono-code" style="font-size:11px">${escapeHtml(d.itemId)}</div>` : ""}
            </div>`).join("")}
      </div>
    `;
  } catch (e) {
    body.innerHTML = `<div class="card" style="color:var(--err)">Failed to load mob details: ${escapeHtml(String(e))}</div>`;
  }
}

// ===== Categories =====

function renderCategories() {
  if (!state.db) return;
  const list = $("categories-list");
  list.innerHTML = "";
  for (const cat of state.db.categories) {
    if (cat.items.length === 0) continue;
    const el = document.createElement("div");
    el.className = "category-card";
    el.style.borderLeftColor = `var(--cat-${cat.name})`;
    el.innerHTML = `
      <div class="count" style="color:var(--cat-${cat.name})">${fmtInt.format(cat.items.length)}</div>
      <div class="name">${escapeHtml(cat.name)}</div>
    `;
    el.addEventListener("click", () => {
      state.filters.category = cat.name;
      showTab("items");
    });
    list.appendChild(el);
  }
}

// ===== Recipes tab placeholder =====

function renderRecipes() {
}

// ===== Search =====

function renderSearchResults() {
  const q = $("search-input").value.trim().toLowerCase();
  const results = [];
  if (q.length >= 1 && state.db) {
    const limit = 300;
    const itemN = state.db.items.count;
    for (let i = 0; i < itemN && results.length < limit; i++) {
      const it = state.db.items.get(i);
      const hay = (it.name + " " + it.id).toLowerCase();
      if (hay.includes(q)) results.push({ kind: "item", data: it });
    }
    const mobN = state.db.mobs.count;
    for (let i = 0; i < mobN && results.length < limit; i++) {
      const m = state.db.mobs.get(i);
      const hay = (m.name + " " + m.id).toLowerCase();
      if (hay.includes(q)) results.push({ kind: "mob", data: m });
    }
  }
  $("search-count").textContent = `${results.length} results`;
  const out = $("search-results");
  out.innerHTML = "";
  const frag = document.createDocumentFragment();
  for (const r of results) {
    const el = document.createElement("div");
    el.className = "search-result";
    if (r.kind === "item") {
      el.innerHTML = `
        <span class="kind">item</span>
        <span class="name">${escapeHtml(r.data.name)}</span>
        <span class="id">${escapeHtml(r.data.id)}</span>
        <span style="color:${categoryColor(r.data.categoryName)}">${formatComplexity(r.data.complexity)}</span>
      `;
      el.addEventListener("click", () => openItemDialog(r.data.index));
    } else {
      el.innerHTML = `
        <span class="kind">mob</span>
        <span class="name">${escapeHtml(r.data.name)}</span>
        <span class="id">${escapeHtml(r.data.id)}</span>
        <span>${fmt.format(r.data.combatPower)} CP</span>
      `;
      el.addEventListener("click", () => openMobDialog(r.data.index));
    }
    frag.appendChild(el);
  }
  out.appendChild(frag);
}

// ===== Export current view =====

function exportCurrentItemsCsv() {
  const rows = [["#", "id", "name", "complexity", "depth", "usage", "category", "hasRecipe", "hasCycle", "isHardcoded"]];
  state.itemsView.forEach((it, i) => rows.push([
    i + 1,
    it.id,
    it.name,
    isFinite(it.complexity) ? it.complexity : "Infinity",
    it.depth,
    it.usageCount,
    it.categoryName,
    (it.flags & ITEM_FLAG.HAS_RECIPE) ? "true" : "false",
    (it.flags & ITEM_FLAG.HAS_CYCLE) ? "true" : "false",
    (it.flags & ITEM_FLAG.IS_HARDCODED) ? "true" : "false",
  ]));
  const csv = rows.map(r => r.map(c => `"${String(c).replace(/"/g, '""')}"`).join(",")).join("\n");
  const blob = new Blob([csv], { type: "text/csv" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `items_${new Date().toISOString().replace(/[:.]/g, "-")}.csv`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

// ===== Event wiring =====

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  })[c]);
}

function wireEvents() {
  $("items-query").addEventListener("input", debounce(() => {
    state.filters.query = $("items-query").value;
    renderItems();
  }, 120));
  $("items-sort").addEventListener("change", () => {
    state.filters.sort = $("items-sort").value;
    renderItems();
  });
  $("items-category").addEventListener("change", () => {
    state.filters.category = $("items-category").value;
    renderItems();
  });
  $("items-finite-only").addEventListener("change", () => {
    state.filters.finiteOnly = $("items-finite-only").checked;
    renderItems();
  });
  $("items-with-recipe").addEventListener("change", () => {
    state.filters.hasRecipe = $("items-with-recipe").checked;
    renderItems();
  });
  $("items-export").addEventListener("click", exportCurrentItemsCsv);

  $("mobs-query").addEventListener("input", debounce(() => {
    state.mobFilters.query = $("mobs-query").value;
    renderMobs();
  }, 120));
  $("mobs-sort").addEventListener("change", () => {
    state.mobFilters.sort = $("mobs-sort").value;
    renderMobs();
  });
  $("mobs-boss-only").addEventListener("change", () => {
    state.mobFilters.bossOnly = $("mobs-boss-only").checked;
    renderMobs();
  });

  $("search-input").addEventListener("input", debounce(renderSearchResults, 120));

  window.addEventListener("keydown", (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
      e.preventDefault();
      showTab("search");
    } else if (e.key === "Escape") {
      document.querySelectorAll("dialog[open]").forEach(d => d.close());
    }
  });
}

function debounce(fn, ms) {
  let t;
  return (...args) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...args), ms);
  };
}

// ===== Bootstrap =====

async function main() {
  setStatus("loading", "opening cabin…");
  const token = new URL(location.href).searchParams.get("token");
  const cabinUrl = `/api/cabin?token=${encodeURIComponent(token || "")}`;
  try {
    state.db = new CabinDatabase(cabinUrl);
    await state.db.open();
    setStatus("ready", `${fmtInt.format(state.db.meta.itemCount)} items, ${fmtInt.format(state.db.meta.mobCount)} mobs`);
    $("footer-left").textContent = `${state.db.meta.modId} ${state.db.meta.modVersion} · file 0x${state.db.file.fileHash.toString(16)}`;
    wireEvents();
    const initialTab = loadStateFromUrl();
    showTab(initialTab);
  } catch (e) {
    console.error(e);
    setStatus("error", "failed to load cabin");
    const mainEl = document.querySelector("main");
    mainEl.innerHTML = `<section class="panel active" style="justify-content:center;align-items:center">
      <div class="card" style="color:var(--err);max-width:600px">
        <h3 style="color:var(--err)">Failed to load cabin</h3>
        <div>${escapeHtml(String(e))}</div>
        <div class="hint" style="margin-top:10px">Make sure you launched the viewer via <code>/cabin open</code> and have a valid token.</div>
      </div>
    </section>`;
  }
}

main().catch(err => console.error("Bootstrap failed:", err));
