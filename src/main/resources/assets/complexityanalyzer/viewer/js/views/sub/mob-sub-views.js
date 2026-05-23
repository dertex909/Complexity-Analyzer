import {
    escapeHtml,
    fmt,
    renderSubTabHeader
} from "../../core/utils.js";
import {state, setState} from "../../core/state.js";

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
                    <div class="card drop-row ingredient" data-index="${d.itemIndex}" style="display: flex; flex-direction: column; justify-content: space-between; gap: 4px; padding: 10px 14px; cursor: pointer; border-radius: 6px; border: 1px solid var(--border); transition: border-color 0.15s ease;">
                        <div style="display: flex; justify-content: space-between; align-items: center;">
                            <strong style="font-size: 14px; color: var(--text);">${escapeHtml(d.itemName || "?")}</strong>
                            <span class="hint" style="font-size: 13px;">— ${fmt.format(d.yieldPerKill)}/kill${d.killMethod ? ` · ${escapeHtml(d.killMethod)}` : ""}</span>
                        </div>
                        ${d.itemId ? `<div class="mono-code" style="font-size: 11px; color: var(--accent);">${escapeHtml(d.itemId)}</div>` : ""}
                    </div>
                `).join("")}
            </div>
        `;

        body.querySelectorAll(".ingredient").forEach(el => {
            el.addEventListener("click", () => {
                const idx = parseInt(el.dataset.index, 10);
                if (idx >= 0) setState({tab: "item-recipes", selectedItem: idx});
            });
        });
    } catch (e) {
        body.innerHTML = `<div style="color:var(--err)">Error loading drops: ${escapeHtml(String(e))}</div>`;
    }
}