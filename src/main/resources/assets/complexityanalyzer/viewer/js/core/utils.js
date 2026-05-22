import {ITEM_FLAG, FLUID_FLAG, MOB_FLAG} from "./cabin.js";
import {switchTab} from "./state.js";

export const fmt = new Intl.NumberFormat("en-US", {maximumFractionDigits: 2});
export const fmtInt = new Intl.NumberFormat("en-US");
const fmtTooltip = new Intl.NumberFormat("de-DE", {maximumFractionDigits: 10});

export function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;"
    })[c]);
}

export function debounce(fn, ms) {
    let t;
    return (...args) => {
        clearTimeout(t);
        t = setTimeout(() => fn(...args), ms);
    };
}

export function formatComplexity(c) {
    if (c < 0) return "-";
    if (!isFinite(c)) return "-";
    if (c === 0) return "0";
    if (c >= 1e6) return c.toExponential(2);
    return fmt.format(c);
}

export function formatRawTooltip(val) {
    if (val === undefined || val === null || !isFinite(val)) return "";
    try {
        return fmtTooltip.format(val);
    } catch (e) {
        return String(val);
    }
}

export function getItemFlags(it, compact = false) {
    const out = [];
    const f = it.flags;
    if (f & ITEM_FLAG.HAS_CYCLE) {
        out.push(`<span class="flag cycle" title="cycle">⟲${compact ? "" : " Cycle"}</span>`);
    }
    if (f & ITEM_FLAG.IS_UNCALCULABLE) {
        out.push(`<span class="flag infinite" title="uncalculable">-${compact ? "" : " Uncalculable"}</span>`);
    }
    if (!(f & ITEM_FLAG.HAS_RECIPE)) {
        out.push(`<span class="flag no-recipe" title="no recipe">∅${compact ? "" : " No recipe"}</span>`);
    }
    if (f & ITEM_FLAG.IS_HARDCODED) {
        out.push(`<span class="flag hardcoded" title="hardcoded">H${compact ? "" : " Hardcoded"}</span>`);
    }
    return out.join("");
}

export function getFluidFlags(fl, compact = false) {
    const out = [];
    const f = fl.flags;
    if (f & FLUID_FLAG.HAS_CYCLE) {
        out.push(`<span class="flag cycle" title="cycle">⟲${compact ? "" : " Cycle"}</span>`);
    }
    if (f & FLUID_FLAG.IS_UNCALCULABLE) {
        out.push(`<span class="flag infinite" title="uncalculable">-${compact ? "" : " Uncalculable"}</span>`);
    }
    if (!(f & FLUID_FLAG.HAS_RECIPE)) {
        out.push(`<span class="flag no-recipe" title="no recipe">∅${compact ? "" : " No recipe"}</span>`);
    }
    if (f & FLUID_FLAG.IS_PROTECTED) {
        out.push(`<span class="flag hardcoded" title="protected">P${compact ? "" : " Protected"}</span>`);
    }
    return out.join("");
}

export function getMobFlags(m, fallbackOnEmpty = false) {
    const out = [];
    if (m.flags & MOB_FLAG.BOSS) out.push(`<span class="flag boss">B</span>`);
    if (m.flags & MOB_FLAG.MINIBOSS) out.push(`<span class="flag miniboss">m</span>`);
    return out.join("") || (fallbackOnEmpty ? `<span class="hint">—</span>` : "");
}

export function renderSubTabHeader(container, entity, subTabName, backButtonText, backTabName) {
    container.innerHTML = `
        <div class="sub-tab-panel-header">
            <button class="btn btn-back" id="back-btn">← ${backButtonText}</button>
            <div class="header-details">
                <h2>${escapeHtml(entity.name)}</h2>
                <span class="mono-code">${escapeHtml(entity.id)}</span>
                <span class="category-pill cat-${entity.categoryName || "Uncalculable"}">${entity.categoryName}</span>
                <span class="sub-tab-label-badge">${subTabName}</span>
            </div>
        </div>
        <div class="sub-tab-content-body"></div>
    `;

    container.querySelector("#back-btn").addEventListener("click", () => {
        switchTab(backTabName);
    });
}

export function renderMachineRecipes(machineSet, recipes, db, renderRecipeRow) {
    const html = [];
    for (const mi of machineSet) {
        const mItem = db.items.get(mi);
        const mRecipes = recipes.filter(r => r.machineItemIndex === mi);
        html.push(`
            <div class="detail-card" style="margin-bottom: 16px;">
                <div style="display:flex; justify-content:space-between; align-items:center; border-bottom: 1px solid var(--border); padding-bottom: 8px; margin-bottom: 8px;">
                    <span>
                        <strong style="color:var(--accent); cursor:pointer;" class="machine-link" data-index="${mi}">${escapeHtml(mItem ? mItem.name : "Unknown Machine")}</strong>
                        <span class="mono-code" style="font-size:11px;">${escapeHtml(mItem ? mItem.id : "")}</span>
                    </span>
                    <span class="chip">${fmtInt.format(mRecipes.length)} recipe(s)</span>
                </div>
                ${mRecipes.map(r => renderRecipeRow(r, db)).join("")}
            </div>
        `);
    }
    return html.join("");
}