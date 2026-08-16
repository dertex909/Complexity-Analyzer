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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

import {escapeHtml, formatComplexity} from "../../core/utils.js";
import {clearRecentItems, getRecentItems, saveRecentItem} from "./tree-recents.js";

export function getTopComplexTargets(db, limit = 6) {
    const list = [];
    for (let i = 0; i < db.items.count; i++) {
        const it = db.items.get(i);
        if (it?.complexity > 0 && !(it.flags & 0x10) && (it.flags & 0x01)) {
            list.push({
                kind: "item",
                index: i,
                name: it.name,
                id: it.id,
                complexity: it.complexity,
                cat: it.categoryName || "Uncalculable"
            });
        }
    }
    return list.sort((a, b) => b.complexity - a.complexity).slice(0, limit);
}

export function renderLandingHub(container, db, selectedFormat, onSelectRoot, onSelectFormat) {
    const topItems = getTopComplexTargets(db, 6);
    const recentItems = getRecentItems();

    container.innerHTML = `
        <style>
            .craft-landing-wrapper {
                display: flex;
                flex-direction: column;
                justify-content: center;
                align-items: center;
                height: 100%;
                width: 100%;
                box-sizing: border-box;
                padding: 16px 24px;
                position: relative;
                overflow: hidden;
            }
            .craft-landing-content {
                width: 100%;
                max-width: 1250px;
                display: flex;
                flex-direction: column;
                gap: 16px;
                z-index: 1;
            }
            .craft-landing-header {
                display: flex;
                align-items: center;
                justify-content: space-between;
                gap: 16px;
                padding-bottom: 12px;
                border-bottom: 1px solid var(--border);
                flex-wrap: wrap;
            }
            .craft-landing-title-block {
                display: flex;
                align-items: center;
                gap: 12px;
            }
            .craft-landing-title-block h1 {
                margin: 0;
                font-size: 20px;
                font-weight: 700;
                color: var(--text-bright);
                letter-spacing: -0.01em;
            }
            .craft-landing-title-block p {
                margin: 0;
                font-size: 12px;
                color: var(--text-dim);
            }
            .format-landing-btn {
                font-size: 11px;
                padding: 5px 12px;
                display: inline-flex;
                align-items: center;
                gap: 6px;
                border: 1px solid var(--border);
                background: var(--bg-raised);
                color: var(--text-dim);
                border-radius: 6px;
                cursor: pointer;
                transition: all 0.15s ease;
            }
            .format-landing-btn:hover {
                background: var(--bg-hover);
                color: var(--text);
            }
            .format-landing-btn.active {
                border-color: var(--accent) !important;
                background: rgba(91, 192, 255, 0.12) !important;
                color: var(--accent) !important;
                box-shadow: 0 0 10px rgba(91, 192, 255, 0.15);
            }
            .landing-search-bar {
                display: flex;
                align-items: center;
                background: var(--bg-panel);
                border: 1px solid var(--border) !important;
                border-radius: 6px;
                padding: 0 12px;
                box-shadow: none !important;
                outline: none !important;
                position: relative;
            }
            .craft-search-input,
            .craft-search-input:focus {
                border: none !important;
                outline: none !important;
                box-shadow: none !important;
                background: transparent !important;
            }
            .landing-recent-pill {
                display: inline-flex;
                align-items: center;
                gap: 6px;
                padding: 4px 10px;
                border-radius: 6px;
                background: var(--bg-panel);
                border: 1px solid var(--border);
                font-size: 11px;
                cursor: pointer;
                transition: all 0.15s ease;
                white-space: nowrap;
            }
            .landing-recent-pill:hover {
                border-color: var(--accent);
                transform: translateY(-1px);
            }
            .landing-top-card {
                display: flex;
                flex-direction: column;
                justify-content: space-between;
                padding: 10px 12px;
                border-radius: 6px;
                background: var(--bg-panel);
                border: 1px solid var(--border);
                gap: 6px;
                cursor: pointer;
                transition: all 0.15s ease;
                box-shadow: var(--shadow-sm);
                min-width: 0;
            }
            .landing-top-card:hover {
                border-color: var(--accent);
                transform: translateY(-2px);
                box-shadow: 0 4px 15px rgba(0,0,0,0.3);
            }
            .clear-history-btn {
                background: none;
                border: none;
                color: var(--text-muted);
                font-size: 10px;
                cursor: pointer;
                text-decoration: underline;
                transition: color 0.15s;
            }
            .clear-history-btn:hover {
                color: var(--err);
            }
            .random-recipe-btn {
                display: flex;
                align-items: center;
                gap: 5px;
                background: rgba(91,192,255,0.08);
                border: 1px solid rgba(91,192,255,0.2);
                color: var(--accent);
                font-size: 11px;
                font-weight: 600;
                padding: 3px 8px;
                border-radius: 4px;
                cursor: pointer;
                transition: background 0.15s;
            }
            .random-recipe-btn:hover {
                background: rgba(91,192,255,0.18);
            }
        </style>

        <div class="craft-landing-wrapper">
            <div style="position: absolute; width: 600px; height: 600px; background: radial-gradient(circle, rgba(91, 192, 255, 0.05) 0%, rgba(92, 217, 154, 0.02) 40%, transparent 70%); top: 50%; left: 50%; transform: translate(-50%, -50%); pointer-events: none; z-index: 0;"></div>

            <div class="craft-landing-content">
                <div class="craft-landing-header">
                    <div class="craft-landing-title-block">
                        <span style="font-size: 28px; filter: drop-shadow(0 0 10px rgba(91, 192, 255, 0.3));">🌿</span>
                        <div>
                            <h1>Visual Craft Tree</h1>
                            <p>Explore production chains, ingredient trees and conveyor layouts.</p>
                        </div>
                    </div>

                    <div style="display: flex; gap: 6px; align-items: center; background: var(--bg-panel); padding: 3px; border-radius: 8px; border: 1px solid var(--border);">
                        <button class="btn format-landing-btn ${selectedFormat === 'classic' ? 'active' : ''}" data-format="classic">
                            <span>📋</span> Classic Tree
                        </button>
                        <button class="btn format-landing-btn ${selectedFormat === 'horizontal' ? 'active' : ''}" data-format="horizontal">
                            <span>🌿</span> Horizontal Graph
                        </button>
                        <button class="btn format-landing-btn ${selectedFormat === 'pipeline' ? 'active' : ''}" data-format="pipeline">
                            <span>⚙️</span> Conveyor
                        </button>
                    </div>
                </div>
                
                <div class="craft-search-container" style="max-width: 100%;">
                    <div class="landing-search-bar" id="search-input-wrapper">
                        <span style="color: var(--text-muted); font-size: 15px; margin-right: 10px; pointer-events: none;">🔍</span>
                        <input type="text" class="craft-search-input" placeholder="Search item or fluid name, ID" autocomplete="off" style="font-size: 13px; padding: 10px 0; width: 100%; color: var(--text);">
                        <button id="craft-search-clear" style="background: none; border: none; color: var(--text-muted); cursor: pointer; font-size: 13px; padding: 4px; display: none;" title="Clear">✕</button>
                    </div>
                    <div class="craft-search-results" id="craft-search-results" hidden style="max-height: 280px; width: 100%;"></div>
                </div>

                ${recentItems.length > 0 ? `
                <div style="width: 100%;">
                    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; font-size: 10px; font-weight: 600; text-transform: uppercase; letter-spacing: 1px; color: var(--text-muted);">
                        <span>🕒 Recently Viewed</span>
                        <button id="clear-recents-btn" class="clear-history-btn">Clear History</button>
                    </div>
                    <div style="display: flex; flex-wrap: wrap; gap: 6px;">
                        ${recentItems.map(item => `
                            <button class="craft-tree-suggestion-pill landing-recent-pill cat-${item.cat || 'Uncalculable'}" data-kind="${item.kind}" data-index="${item.index}">
                                <span>${item.kind === 'item' ? '📦' : '💧'}</span>
                                <span style="font-weight: 500; color: var(--text);">${escapeHtml(item.name)}</span>
                                ${item.complexity > 0 ? `<span class="cat-${item.cat || 'Uncalculable'}" style="font-size: 10px; font-weight: 700; font-family: var(--mono), monospace;">${formatComplexity(item.complexity)}</span>` : ''}
                            </button>
                        `).join('')}
                    </div>
                </div>
                ` : ''}

                ${topItems.length > 0 ? `
                <div style="width: 100%;">
                    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; font-size: 10px; font-weight: 600; text-transform: uppercase; letter-spacing: 1px; color: var(--text-muted);">
                        <span>👑 High Complexity Targets</span>
                        <button id="random-craft-btn" class="random-recipe-btn">
                            <span>🎲</span> Random Recipe
                        </button>
                    </div>
                    <div style="display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; width: 100%;">
                        ${topItems.map(it => `
                            <div class="craft-tree-suggestion-pill landing-top-card cat-${it.cat || 'Uncalculable'}" data-kind="${it.kind}" data-index="${it.index}">
                                <div style="display: flex; align-items: flex-start; gap: 8px;">
                                    <span style="font-size: 15px; margin-top: 1px;">📦</span>
                                    <div style="flex: 1; min-width: 0;">
                                        <div style="font-size: 12px; font-weight: 600; color: var(--text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis;" title="${escapeHtml(it.name)}">${escapeHtml(it.name)}</div>
                                        <div style="font-size: 10px; color: var(--text-muted); font-family: var(--mono), monospace; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">${escapeHtml(it.id)}</div>
                                    </div>
                                </div>
                                <div style="display: flex; justify-content: space-between; align-items: center; border-top: 1px solid rgba(255,255,255,0.04); padding-top: 4px;">
                                    <span class="category-pill cat-${it.cat || 'Uncalculable'}" style="font-size: 9px; padding: 1px 6px; margin: 0;">${it.cat}</span>
                                    <span class="cat-${it.cat || 'Uncalculable'}" style="font-size: 11px; font-weight: 700; font-family: var(--mono), monospace;">${formatComplexity(it.complexity)}</span>
                                </div>
                            </div>
                        `).join("")}
                    </div>
                </div>
                ` : ""}

            </div>
        </div>
    `;

    const searchInput = container.querySelector(".craft-search-input");
    const searchResults = container.querySelector("#craft-search-results");
    const clearBtn = container.querySelector("#craft-search-clear");

    if (searchInput && searchResults) {
        if (clearBtn) {
            clearBtn.addEventListener("click", () => {
                searchInput.value = "";
                searchResults.hidden = true;
                clearBtn.style.display = "none";
                searchInput.focus();
            });
        }

        searchInput.addEventListener("input", () => {
            const query = searchInput.value.trim().toLowerCase();
            if (clearBtn) clearBtn.style.display = query.length > 0 ? "block" : "none";

            if (query.length < 2) {
                searchResults.hidden = true;
                return;
            }

            const matched = [];
            for (let i = 0; i < db.items.count && matched.length < 50; i++) {
                const it = db.items.get(i);
                if (it && (it.name.toLowerCase().includes(query) || it.id.toLowerCase().includes(query))) {
                    matched.push({
                        kind: "item",
                        name: it.name,
                        id: it.id,
                        index: i,
                        complexity: it.complexity,
                        category: it.categoryName
                    });
                }
            }
            for (let i = 0; i < db.fluids.count && matched.length < 100; i++) {
                const fl = db.fluids.get(i);
                if (fl && (fl.name.toLowerCase().includes(query) || fl.id.toLowerCase().includes(query))) {
                    matched.push({
                        kind: "fluid",
                        name: fl.name,
                        id: fl.id,
                        index: i,
                        complexity: fl.complexity,
                        category: fl.categoryName
                    });
                }
            }

            if (matched.length > 0) {
                searchResults.innerHTML = matched.map(m => `
                    <div class="craft-search-item" data-kind="${m.kind}" data-index="${m.index}">
                        <div style="display: flex; flex-direction: column; gap: 2px; min-width: 0; flex: 1;">
                            <span class="name" style="font-weight: 600; font-size: 12px; color: var(--text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">${escapeHtml(m.name)}</span>
                            <span class="id" style="font-size: 10px; font-family: var(--mono), monospace; color: var(--text-dim);">${escapeHtml(m.id)}</span>
                        </div>
                        <div style="display: flex; align-items: center; gap: 8px; flex-shrink: 0;">
                            ${m.complexity !== undefined && m.complexity > 0 ? `
                                <span class="cat-${m.category || 'Uncalculable'}" style="font-size: 10px; font-weight: 600; font-family: var(--mono), monospace;">${formatComplexity(m.complexity)}</span>
                            ` : ""}
                            <span class="kind kind-${m.kind}">${m.kind}</span>
                        </div>
                    </div>
                `).join("");
                searchResults.hidden = false;
            } else {
                searchResults.innerHTML = `<div style="padding: 12px; color: var(--text-dim); text-align: center; font-size: 11px;">No matching items or fluids found</div>`;
                searchResults.hidden = false;
            }
        });

        searchInput.addEventListener("keydown", (e) => {
            if (e.key === "Enter") {
                const firstResult = searchResults.querySelector(".craft-search-item");
                if (firstResult && !searchResults.hidden) firstResult.click();
            }
        });

        searchResults.addEventListener("click", e => {
            const item = e.target.closest(".craft-search-item");
            if (!item) return;
            const kind = item.dataset.kind;
            const index = parseInt(item.dataset.index, 10);
            saveRecentItem(kind, index, db);
            onSelectRoot(kind, index);
        });
    }

    container.querySelectorAll(".craft-tree-suggestion-pill").forEach(btn => {
        btn.addEventListener("click", () => {
            const kind = btn.dataset.kind;
            const index = parseInt(btn.dataset.index, 10);
            saveRecentItem(kind, index, db);
            onSelectRoot(kind, index);
        });
    });

    container.querySelectorAll(".format-landing-btn").forEach(btn => {
        btn.addEventListener("click", () => {
            const format = btn.dataset.format;
            container.querySelectorAll(".format-landing-btn").forEach(b => {
                b.classList.toggle("active", b.dataset.format === format);
            });
            onSelectFormat(format);
        });
    });

    const clearRecentsBtn = container.querySelector("#clear-recents-btn");
    if (clearRecentsBtn) {
        clearRecentsBtn.addEventListener("click", () => {
            clearRecentItems();
            renderLandingHub(container, db, selectedFormat, onSelectRoot, onSelectFormat);
        });
    }

    const randomBtn = container.querySelector("#random-craft-btn");
    if (randomBtn) {
        randomBtn.addEventListener("click", () => {
            const total = db.items.count;
            if (total > 0) {
                const start = Math.floor(Math.random() * total);
                for (let i = 0; i < total; i++) {
                    const idx = (start + i) % total;
                    const it = db.items.get(idx);
                    if (it && it.complexity > 0 && !(it.flags & 0x10) && (it.flags & 0x01)) {
                        saveRecentItem("item", idx, db);
                        onSelectRoot("item", idx);
                        break;
                    }
                }
            }
        });
    }
}