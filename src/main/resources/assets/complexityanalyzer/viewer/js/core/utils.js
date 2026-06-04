/*
 * Complexity Analyzer
 * Copyright (C) 2025-2026 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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

export function formatComplexityDetail(c) {
    if (c < 0) return "-";
    if (!isFinite(c)) return "-";
    if (c === 0) return "0";
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
    let metadataHtml;

    if (backTabName === "items") {
        const flagsHtml = getItemFlags(entity, false);
        metadataHtml = `
            <div class="header-metadata-row" style="display: flex; flex-wrap: wrap; gap: 16px; align-items: center; margin-top: 12px; padding: 10px 16px; background: rgba(255, 255, 255, 0.02); border-radius: 6px; border: 1px solid var(--border); font-size: 13px;">
                <div style="display: flex; align-items: center; gap: 6px;">
                    <span style="color: var(--text-dim); text-transform: uppercase; font-size: 10px; font-weight: 500; letter-spacing: 1px;">Complexity:</span>
                    <strong class="cat-${entity.categoryName || "Uncalculable"}" style="font-size: 14px; font-weight: 600;" title="${formatRawTooltip(entity.complexity)}">${formatComplexityDetail(entity.complexity)}</strong>
                </div>
                <div style="width: 1px; height: 16px; background: var(--border);"></div>
                <div style="display: flex; align-items: center; gap: 6px;">
                    <span style="color: var(--text-dim); text-transform: uppercase; font-size: 10px; font-weight: 500; letter-spacing: 1px;">Category:</span>
                    <span class="category-pill cat-${entity.categoryName || "Uncalculable"}" style="font-size: 11px; padding: 2px 8px; margin: 0;">${entity.categoryName}</span>
                </div>
                <div style="width: 1px; height: 16px; background: var(--border);"></div>
                <div style="display: flex; align-items: center; gap: 6px;">
                    <span style="color: var(--text-dim); text-transform: uppercase; font-size: 10px; font-weight: 500; letter-spacing: 1px;">Depth:</span>
                    <strong style="color: var(--text);" title="${formatRawTooltip(entity.depth)}">${fmtInt.format(entity.depth)}</strong>
                </div>
                <div style="width: 1px; height: 16px; background: var(--border);"></div>
                <div style="display: flex; align-items: center; gap: 6px;">
                    <span style="color: var(--text-dim); text-transform: uppercase; font-size: 10px; font-weight: 500; letter-spacing: 1px;">Total Ingredients:</span>
                    <strong style="color: var(--text);" title="${formatRawTooltip(entity.totalIngredients)}">${fmtInt.format(entity.totalIngredients)}</strong>
                </div>
                <div style="width: 1px; height: 16px; background: var(--border);"></div>
                <div style="display: flex; align-items: center; gap: 6px;">
                    <span style="color: var(--text-dim); text-transform: uppercase; font-size: 10px; font-weight: 500; letter-spacing: 1px;">Recipe Usages:</span>
                    <span style="color: var(--text);">Used in <strong style="color: var(--accent);">${fmtInt.format(entity.usageCount)}</strong> recipe${entity.usageCount === 1 ? "" : "s"}</span>
                </div>
                ${flagsHtml ? `
                <div style="width: 1px; height: 16px; background: var(--border);"></div>
                <div style="display: flex; align-items: center; gap: 6px;">
                    <span style="color: var(--text-dim); text-transform: uppercase; font-size: 10px; font-weight: 500; letter-spacing: 1px;">Flags:</span>
                    <span class="flags" style="display: flex; gap: 4px;">${flagsHtml}</span>
                </div>
                ` : ""}
                ${entity.errorMessage ? `
                <div style="width: 1px; height: 16px; background: var(--border);"></div>
                <div style="display: flex; align-items: center; gap: 6px; color: var(--err)">
                    <span style="color: var(--text-dim); text-transform: uppercase; font-size: 10px; font-weight: 500; letter-spacing: 1px;">Error:</span>
                    <span style="font-size: 12px; font-weight: 500;">${escapeHtml(entity.errorMessage)}</span>
                </div>
                ` : ""}
                
                <div style="margin-left: auto; display: flex; align-items: center;">
                    <span class="sub-tab-label-badge" style="margin: 0; padding: 4px 10px; background: rgba(94, 199, 255, 0.1); color: var(--accent); border: 1px solid rgba(94, 199, 255, 0.2); border-radius: 4px; font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 1px; white-space: nowrap;">${subTabName}</span>
                </div>
            </div>
        `;
    } else if (backTabName === "fluids") {
        const flagsHtml = getFluidFlags(entity, false);
        metadataHtml = `
            <div class="header-metadata-row" style="display: flex; flex-wrap: wrap; gap: 16px; align-items: center; margin-top: 12px; padding: 10px 16px; background: rgba(255, 255, 255, 0.02); border-radius: 6px; border: 1px solid var(--border); font-size: 13px;">
                <div style="display: flex; align-items: center; gap: 6px;">
                    <span style="color: var(--text-dim); text-transform: uppercase; font-size: 10px; font-weight: 500; letter-spacing: 1px;">Complexity:</span>
                    <strong class="cat-${entity.categoryName || "Uncalculable"}" style="font-size: 14px; font-weight: 600;" title="${formatRawTooltip(entity.complexity)}">${formatComplexityDetail(entity.complexity)}</strong>
                </div>
                <div style="width: 1px; height: 16px; background: var(--border);"></div>
                <div style="display: flex; align-items: center; gap: 6px;">
                    <span style="color: var(--text-dim); text-transform: uppercase; font-size: 10px; font-weight: 500; letter-spacing: 1px;">Category:</span>
                    <span class="category-pill cat-${entity.categoryName || "Uncalculable"}" style="font-size: 11px; padding: 2px 8px; margin: 0;">${entity.categoryName}</span>
                </div>
                <div style="width: 1px; height: 16px; background: var(--border);"></div>
                <div style="display: flex; align-items: center; gap: 6px;">
                    <span style="color: var(--text-dim); text-transform: uppercase; font-size: 10px; font-weight: 500; letter-spacing: 1px;">Recipe Usages:</span>
                    <span style="color: var(--text);">Used as ingredient in <strong style="color: var(--accent);">${fmtInt.format(entity.usageCount)}</strong> recipe${entity.usageCount === 1 ? "" : "s"}</span>
                </div>
                ${flagsHtml ? `
                <div style="width: 1px; height: 16px; background: var(--border);"></div>
                <div style="display: flex; align-items: center; gap: 6px;">
                    <span style="color: var(--text-dim); text-transform: uppercase; font-size: 10px; font-weight: 500; letter-spacing: 1px;">Flags:</span>
                    <span class="flags" style="display: flex; gap: 4px;">${flagsHtml}</span>
                </div>
                ` : ""}
                
                <div style="margin-left: auto; display: flex; align-items: center;">
                    <span class="sub-tab-label-badge" style="margin: 0; padding: 4px 10px; background: rgba(94, 199, 255, 0.1); color: var(--accent); border: 1px solid rgba(94, 199, 255, 0.2); border-radius: 4px; font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 1px; white-space: nowrap;">${subTabName}</span>
                </div>
            </div>
        `;
    } else {
        const flagsHtml = getMobFlags(entity, false);
        metadataHtml = `
            <div class="header-metadata-row" style="display: flex; flex-wrap: wrap; gap: 16px; align-items: center; margin-top: 12px; padding: 10px 16px; background: rgba(255, 255, 255, 0.02); border-radius: 6px; border: 1px solid var(--border); font-size: 13px;">
                <div style="display: flex; align-items: center; gap: 6px;">
                    <span style="color: var(--text-dim); text-transform: uppercase; font-size: 10px; font-weight: 500; letter-spacing: 1px;">Category:</span>
                    <span class="category-pill cat-${entity.categoryName || "Uncalculable"}" style="font-size: 11px; padding: 2px 8px; margin: 0;">${entity.categoryName || "Mob"}</span>
                </div>
                ${flagsHtml ? `
                <div style="width: 1px; height: 16px; background: var(--border);"></div>
                <div style="display: flex; align-items: center; gap: 6px;">
                    <span style="color: var(--text-dim); text-transform: uppercase; font-size: 10px; font-weight: 500; letter-spacing: 1px;">Flags:</span>
                    <span class="flags" style="display: flex; gap: 4px;">${flagsHtml}</span>
                </div>
                ` : ""}
                
                <div style="margin-left: auto; display: flex; align-items: center;">
                    <span class="sub-tab-label-badge" style="margin: 0; padding: 4px 10px; background: rgba(94, 199, 255, 0.1); color: var(--accent); border: 1px solid rgba(94, 199, 255, 0.2); border-radius: 4px; font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 1px; white-space: nowrap;">${subTabName}</span>
                </div>
            </div>
        `;
    }

    container.innerHTML = `
        <div class="sub-tab-panel-header" style="margin-bottom: 20px;">
            <button class="btn btn-back" id="back-btn" style="margin-bottom: 14px;">← ${backButtonText}</button>
            <div class="header-details" style="display: flex; flex-direction: column; gap: 4px;">
                <div style="display: flex; align-items: center; gap: 12px; flex-wrap: wrap;">
                    <h2 style="margin: 0; font-size: 24px; font-weight: 600; color: var(--text);">${escapeHtml(entity.name)}</h2>
                    <code class="mono-code" style="font-size: 13px; color: var(--accent); background: rgba(94, 199, 255, 0.05); padding: 3px 8px; border-radius: 4px; border: 1px solid rgba(94, 199, 255, 0.15);">${escapeHtml(entity.id)}</code>
                </div>
                ${metadataHtml}
            </div>
        </div>
        <div class="sub-tab-content-body"></div>
    `;

    container.querySelector("#back-btn").addEventListener("click", () => {
        switchTab(backTabName);
    });
}