import { state } from "../../core/state.js";
import { MOB_FLAG } from "../../core/cabin.js";

const fmt = new Intl.NumberFormat("en-US", { maximumFractionDigits: 2 });
const fmtInt = new Intl.NumberFormat("en-US");

export async function renderMobDetail(container, mobIndex) {
    const db = state.db;
    const mob = db.mobs.get(mobIndex);
    if (!mob) return;

    const panel = container.querySelector("#mob-detail-panel") || document.createElement("div");
    panel.id = "mob-detail-panel";
    panel.className = "card";
    panel.style.marginTop = "12px";
    panel.innerHTML = `
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
            <h3 style="margin:0">${escapeHtml(mob.name)} <code class="mono-code" style="font-size:11px">${escapeHtml(mob.id)}</code></h3>
            <button class="btn" id="mob-detail-close" style="font-size:11px;padding:4px 8px">Close</button>
        </div>
        <div id="mob-detail-body">Loading…</div>
    `;
    if (!container.contains(panel)) container.appendChild(panel);

    panel.querySelector("#mob-detail-close").addEventListener("click", () => {
        panel.remove();
        state.selectedMob = -1;
    });

    const body = panel.querySelector("#mob-detail-body");
    try {
        const drops = await db.getMobDrops(mobIndex);
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
                <dt>Flags</dt><dd class="flags">${mobFlags(mob)}</dd>
            </dl>
            <div class="section-sub">
                <h3>Drops (${drops.length})</h3>
                ${drops.length === 0 ? `<div class="hint">No drops recorded.</div>` : drops.map(d => `
                    <div class="drop-row">
                        <strong>${escapeHtml(d.itemName || "?")}</strong>
                        <span class="hint"> — ${fmt.format(d.yieldPerKill)}/kill${d.killMethod ? ` · ${escapeHtml(d.killMethod)}` : ""}</span>
                        ${d.itemId ? `<div class="mono-code" style="font-size:11px">${escapeHtml(d.itemId)}</div>` : ""}
                    </div>
                `).join("")}
            </div>
        `;
    } catch (e) {
        body.innerHTML = `<div style="color:var(--err)">Error: ${escapeHtml(String(e))}</div>`;
    }
}

function mobFlags(m) {
    const out = [];
    if (m.flags & MOB_FLAG.BOSS) out.push(`<span class="flag boss">B</span>`);
    if (m.flags & MOB_FLAG.MINIBOSS) out.push(`<span class="flag miniboss">m</span>`);
    return out.join("") || "<span class=\"hint\">—</span>";
}

function escapeHtml(s) { return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]); }
