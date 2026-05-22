import {
    escapeHtml,
    getMobFlags,
    fmt
} from "../../core/utils.js";
import {state, setState} from "../../core/state.js";
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
                if (action === "mob-drops") renderDropsSection(bodyEl, mobIndex);
            });
        });
    }

    setupModalClose(overlay, closeEl, () => {
        setState({selectedMob: -1});
    });
}

async function renderDropsSection(bodyEl, mobIndex) {
    const db = state.db;
    if (!db) return;

    try {
        const drops = await db.getMobDrops(mobIndex);
        bodyEl.innerHTML = `
            <button class="btn" id="mob-back-btn" style="margin-bottom:12px;">← Back to Mob Info</button>
            <h3>Drops (${drops.length})</h3>
            ${drops.length === 0 ? `<div class="hint">No drops recorded.</div>` : drops.map(d => `
                <div class="drop-row" style="padding:8px; border-bottom:1px solid var(--border);">
                    <strong>${escapeHtml(d.itemName || "?")}</strong>
                    <span class="hint"> — ${fmt.format(d.yieldPerKill)}/kill${d.killMethod ? ` · ${escapeHtml(d.killMethod)}` : ""}</span>
                    ${d.itemId ? `<div class="mono-code" style="font-size:11px">${escapeHtml(d.itemId)}</div>` : ""}
                </div>
            `).join("")}
        `;

        bodyEl.querySelector("#mob-back-btn").addEventListener("click", () => {
            renderMobDetail(null, mobIndex);
        });
    } catch (e) {
        bodyEl.innerHTML = `<div style="color:var(--err)">Error: ${escapeHtml(String(e))}</div>`;
    }
}