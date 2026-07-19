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

/**
 * Show loading overlay
 */
export function showLoader(container) {
    const overlay = container.querySelector("#graph-overlay");
    if (!overlay) return null;

    overlay.innerHTML = `
        <div id="graph-loader" style="
            position: absolute;
            inset: 0;
            display: flex;
            flex-direction: column;
            justify-content: center;
            align-items: center;
            background: rgba(10, 12, 16, 0.95);
            z-index: 100;
            color: var(--text-dim);
            gap: 12px;
            pointer-events: auto;
        ">
            <div style="
                width: 32px;
                height: 32px;
                border: 3px solid var(--border-light);
                border-top-color: var(--accent);
                border-radius: 50%;
                animation: spin 0.8s linear infinite;
            "></div>
            <div style="font-weight: 500; color: var(--text);">Generating All-Items Graph…</div>
            <div style="font-size: 11px;">Scanning recipe connections...</div>
        </div>
        <style>
            @keyframes spin {
                to { transform: rotate(360deg); }
            }
        </style>
    `;

    return overlay.querySelector("#graph-loader");
}

export function removeLoader(container) {
    const loader = container.querySelector("#graph-loader");
    if (loader) loader.remove();
}

export function updateSidePanel(overlay, node, onClose, onOpenDetails) {
    if (!overlay) return;

    let panel = overlay.querySelector("#graph-side-panel");
    if (!panel) {
        panel = document.createElement('div');
        panel.id = "graph-side-panel";
        panel.style.cssText = `
            position: absolute;
            top: 12px;
            right: 12px;
            bottom: 12px;
            width: 260px;
            background: rgba(11, 13, 16, 0.92);
            border: 1px solid rgba(255,255,255,0.08);
            border-radius: 14px;
            box-shadow: 0 8px 40px rgba(0,0,0,0.6), inset 0 1px 0 rgba(255,255,255,0.06);
            backdrop-filter: blur(24px);
            transform: translateX(16px);
            opacity: 0;
            transition: opacity 0.22s ease, transform 0.22s cubic-bezier(0.2,0.9,0.3,1);
            pointer-events: none;
            display: flex;
            flex-direction: column;
            overflow: hidden;
            z-index: 200;
            font-family: var(--sans), sans-serif;
        `;
        overlay.appendChild(panel);
    }

    if (node) {
        const fmt = new Intl.NumberFormat("en-US", {maximumFractionDigits: 2});
        const fmtI = new Intl.NumberFormat("en-US");

        const fc = (c) => {
            if (c === undefined || c < 0 || !isFinite(c)) return "—";
            if (c === 0) return "0";
            if (c >= 1e6) return c.toExponential(2);
            return fmt.format(c);
        };

        panel.innerHTML = `
            <style>
                #graph-side-panel .gsp-row {
                    display: flex;
                    flex-direction: column;
                    gap: 2px;
                    padding: 11px 16px;
                    border-bottom: 1px solid rgba(255,255,255,0.04);
                    transition: background 0.15s;
                }
                #graph-side-panel .gsp-row:last-child { border-bottom: none; }
                #graph-side-panel .gsp-row:hover { background: rgba(255,255,255,0.03); }
                #graph-side-panel .gsp-label {
                    font-size: 10px;
                    font-weight: 600;
                    text-transform: uppercase;
                    letter-spacing: 1px;
                    color: rgba(255,255,255,0.3);
                }
                #graph-side-panel .gsp-val {
                    font-size: 14px;
                    font-weight: 500;
                    color: var(--text);
                    line-height: 1.3;
                }
            </style>

            <div style="padding: 14px 16px 12px; border-bottom: 1px solid rgba(255,255,255,0.07);">
                <div style="display:flex; justify-content:space-between; align-items:flex-start; gap:8px; margin-bottom: 6px;">
                    <div style="font-size:13px; font-weight:700; color:var(--text); line-height:1.3; word-break:break-word;">${node.name}</div>
                    <button id="gsp-close" style="
                        flex-shrink:0; background:rgba(255,255,255,0.05);
                        border:1px solid rgba(255,255,255,0.1); color:rgba(255,255,255,0.4);
                        cursor:pointer; width:24px; height:24px; border-radius:6px;
                        font-size:12px; display:flex; align-items:center; justify-content:center;
                        transition: all 0.15s;
                    " onmouseover="this.style.background='rgba(255,255,255,0.12)';this.style.color='#fff'"
                       onmouseout="this.style.background='rgba(255,255,255,0.05)';this.style.color='rgba(255,255,255,0.4)'">✕</button>
                </div>
                ${node.itemId ? `<div style="font-size:10px; color:var(--accent); opacity:0.6; font-family:monospace; word-break:break-all;">${node.itemId}</div>` : ''}
            </div>

            <div style="flex:1; overflow-y:auto; padding: 6px 0;">
                <div class="gsp-row">
                    <div class="gsp-label">Complexity</div>
                    <div class="gsp-val cat-${node.category}" style="font-size:18px; font-weight:700;">${fc(node.complexity)}</div>
                </div>
                <div class="gsp-row">
                    <div class="gsp-label">Category</div>
                    <div class="gsp-val"><span class="category-pill cat-${node.category}" style="font-size:10px;padding:2px 7px;margin:0;">${node.category}</span></div>
                </div>
                <div class="gsp-row">
                    <div class="gsp-label">Craft Depth</div>
                    <div class="gsp-val">${node.depth !== undefined ? fmtI.format(node.depth) : '—'}</div>
                </div>
                <div class="gsp-row">
                    <div class="gsp-label">Total Ingredients</div>
                    <div class="gsp-val">${node.totalIngredients !== undefined ? fmtI.format(node.totalIngredients) : '—'}</div>
                </div>
                <div class="gsp-row">
                    <div class="gsp-label">Recipe Usages</div>
                    <div class="gsp-val">Used in <span style="color:var(--accent);font-weight:700;">${node.usageCount !== undefined ? fmtI.format(node.usageCount) : '?'}</span> recipe(s)</div>
                </div>
                <div class="gsp-row">
                    <div class="gsp-label">Graph Connections</div>
                    <div class="gsp-val">${fmtI.format(node.degree)}</div>
                </div>
            </div>

            <div style="padding: 12px 16px; border-top: 1px solid rgba(255,255,255,0.06);">
                <button id="gsp-open-details" style="
                    width:100%;
                    background: linear-gradient(135deg, rgba(94,199,255,0.12), rgba(94,199,255,0.06));
                    border: 1px solid rgba(94,199,255,0.25);
                    color: var(--accent);
                    cursor: pointer;
                    padding: 8px 12px;
                    border-radius: 8px;
                    font-size: 12px;
                    font-weight: 600;
                    letter-spacing: 0;
                    transition: all 0.15s;
                    text-align: center;
                " onmouseover="this.style.background='linear-gradient(135deg,rgba(94,199,255,0.22),rgba(94,199,255,0.12))'"
                   onmouseout="this.style.background='linear-gradient(135deg,rgba(94,199,255,0.12),rgba(94,199,255,0.06))'">
                    ⤢ Open Full Details
                </button>
            </div>
        `;

        const closeBtn = panel.querySelector("#gsp-close");
        if (closeBtn) closeBtn.addEventListener("click", onClose);

        const detailsBtn = panel.querySelector("#gsp-open-details");
        if (detailsBtn && onOpenDetails) detailsBtn.addEventListener("click", onOpenDetails);

        void panel.offsetWidth;
        panel.style.opacity = '1';
        panel.style.transform = 'translateX(0)';
        panel.style.pointerEvents = 'auto';
    } else {
        panel.style.opacity = '0';
        panel.style.transform = 'translateX(16px)';
        panel.style.pointerEvents = 'none';
    }
}