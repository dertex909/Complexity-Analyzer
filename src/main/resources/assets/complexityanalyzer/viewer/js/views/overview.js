import { state } from "../core/state.js";

const fmtInt = new Intl.NumberFormat("en-US");

export function renderOverview(container) {
    const db = state.db;
    if (!db) return;
    const m = db.meta;

    const stats = [
        { label: "Items", value: m.itemCount },
        { label: "Fluids", value: m.fluidCount },
        { label: "Mobs", value: m.mobCount },
        { label: "Recipes", value: m.recipeCount },
        { label: "Valid items", value: m.validItems },
        { label: "Unobtainable", value: m.infiniteItems },
        { label: "Machines", value: m.machineCount },
        { label: "Mods", value: m.modCount },
    ];

    container.innerHTML = `
        <h2>Overview</h2>
        <div class="stat-grid">
            ${stats.map(s => `
                <div class="card">
                    <div class="value">${fmtInt.format(s.value)}</div>
                    <div class="label">${s.label}</div>
                </div>
            `).join("")}
        </div>
        <div class="card">
            <h4>File details</h4>
            <div class="detail-grid">
                <dt>Hash</dt><dd><code class="mono-code">0x${db.file.fileHash.toString(16)}</code></dd>
                <dt>Generated</dt><dd>${m.timestampMs ? new Date(m.timestampMs).toLocaleString() : m.timestampStr}</dd>
                <dt>Format version</dt><dd>0x${Number(db.file.version).toString(16)}</dd>
                <dt>Server</dt><dd>${m.serverName}</dd>
            </div>
        </div>
        <div class="card">
            <h4>Mod summary</h4>
            <div id="mod-summary-list"></div>
        </div>
    `;

    const modList = container.querySelector("#mod-summary-list");
    if (db.modSummary) {
        const sorted = [...db.modSummary].sort((a, b) => b.itemCount - a.itemCount);
        modList.innerHTML = sorted.slice(0, 20).map(mod => `
            <div class="stat-row">
                <span>${mod.modId}</span>
                <span>${fmtInt.format(mod.itemCount)} items · ${fmtInt.format(mod.recipeCount)} recipes</span>
            </div>
        `).join("") + (sorted.length > 20 ? `<div class="hint">…and ${sorted.length - 20} more</div>` : "");
    }
}
