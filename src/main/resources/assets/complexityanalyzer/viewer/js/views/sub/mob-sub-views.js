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

import {escapeHtml, fmt, renderSubTabHeader} from "../../core/utils.js";
import {state} from "../../core/state.js";
import {wireRecipeLinks} from "./recipe-shared.js";

export async function renderMobDropsView(container) {
    const db = state.db;
    if (!db) return;
    const mobIndex = state.selectedMob;
    if (mobIndex < 0) {
        container.innerHTML = `<div class="empty-state"><div class="icon">🔍</div><div class="message">No mob selected. Go to Mobs tab and choose a mob.</div></div>`;
        return;
    }
    const mob = db.mobs.get(mobIndex);
    if (!mob) return;

    renderSubTabHeader(container, mob, "Drops of Mob", "Back to Mobs", "mobs");
    const body = container.querySelector(".sub-tab-content-body");

    body.innerHTML = `<div class="hint">Loading mob drops…</div>`;
    try {
        const drops = await db.getMobDrops(mobIndex);
        if (drops.length === 0) {
            body.innerHTML = `<div class="empty-state"><div class="icon">∅</div><div class="message">No drops recorded for this mob.</div></div>`;
            return;
        }

        body.innerHTML = `
            <div style="padding: 12px; display: flex; flex-direction: column; gap: 8px;">
                ${drops.map(d => `
                    <div class="card drop-row ingredient ingredient-link" data-index="${d.itemIndex}" style="display: flex; flex-direction: column; justify-content: space-between; gap: 4px; padding: 10px 14px; cursor: pointer; border-radius: 6px; border: 1px solid var(--border); transition: border-color 0.15s ease;">
                        <div style="display: flex; justify-content: space-between; align-items: center;">
                            <strong style="font-size: 14px; color: var(--text);">${escapeHtml(d.itemName || "?")}</strong>
                            <span class="hint" style="font-size: 13px;">— ${fmt.format(d.yieldPerKill)}/kill${d.killMethod ? ` · ${escapeHtml(d.killMethod)}` : ""}</span>
                        </div>
                        ${d.itemId ? `<div class="mono-code" style="font-size: 11px; color: var(--accent);">${escapeHtml(d.itemId)}</div>` : ""}
                    </div>
                `).join("")}
            </div>
        `;

        wireRecipeLinks(body);
    } catch (e) {
        body.innerHTML = `<div style="color:var(--err)">Error loading drops: ${escapeHtml(String(e))}</div>`;
    }
}