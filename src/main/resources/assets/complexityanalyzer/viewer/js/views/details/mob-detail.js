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

import {escapeHtml, fmt, getMobFlags} from "../../core/utils.js";
import {setState, state} from "../../core/state.js";
import {setupModalClose} from "../../components/modal-utils.js";

export async function renderMobDetail(unusedContainer, mobIndex) {
    const db = state.db;
    if (!db) return;
    const mob = db.mobs.get(mobIndex);
    if (!mob) return;

    const overlay = document.getElementById("mob-modal");
    if (!overlay) return;

    const titleEl = document.getElementById("mob-modal-title");
    const bodyEl = document.getElementById("mob-modal-body");
    const closeEl = document.getElementById("mob-modal-close");

    if (titleEl) {
        titleEl.innerHTML = `${escapeHtml(mob.name)} <code class="mono-code" style="font-size:11px;">${escapeHtml(mob.id)}</code>`;
    }

    const hasDrops = mob.dropCount > 0;

    if (bodyEl) {
        bodyEl.innerHTML = `
            <dl class="detail-grid">
                <dt>Category</dt><dd>${escapeHtml(mob.categoryName)}</dd>
                <dt>Health</dt><dd>${fmt.format(mob.health)}</dd>
                <dt>Damage</dt><dd>${fmt.format(mob.damage)}</dd>
                <dt>Armor</dt><dd>${fmt.format(mob.armor)}</dd>
                <dt>Survivability</dt><dd>${fmt.format(mob.survivability)}</dd>
                <dt>Threat</dt><dd>${fmt.format(mob.threat)}</dd>
                <dt>Combat power</dt><dd>${fmt.format(mob.combatPower)}</dd>
                <dt>Rarity</dt><dd>${fmt.format(mob.rarity)}</dd>
                <dt>Flags</dt><dd class="flags">${getMobFlags(mob, false)}</dd>
            </dl>

            <div class="modal-actions-grid">
                <button class="modal-action-card" data-action="mob-drops" ${!hasDrops ? "disabled" : ""}>
                    <span class="action-title">Drops</span>
                    <span class="action-desc">${hasDrops ? "View items dropped by this mob" : "This mob has no recorded drops"}</span>
                </button>
            </div>
        `;

        bodyEl.querySelectorAll(".modal-action-card").forEach(card => {
            if (card.hasAttribute("disabled")) return;
            card.addEventListener("click", () => {
                const action = card.dataset.action;
                overlay.hidden = true;
                setState({tab: action});
            });
        });
    }

    setupModalClose(overlay, closeEl, () => {
        setState({selectedMob: -1});
    });
}