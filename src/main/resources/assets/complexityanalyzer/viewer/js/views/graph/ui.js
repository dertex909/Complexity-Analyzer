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

export function updateLoaderProgress(container, progress) {
    const loaderText = container.querySelector("#graph-loader div[style*='font-weight: 500']");
    if (loaderText) {
        const percent = Math.round(progress * 100);
        loaderText.textContent = `Generating All-Items Graph… ${percent}%`;
    }
}

export function removeLoader(container) {
    const loader = container.querySelector("#graph-loader");
    if (loader) loader.remove();
}

export function createInfoPanel(overlay, nodeCount, edgeCount, onRecenter) {
    if (!overlay) return;

    overlay.innerHTML = `
        <div class="card" style="
            position: absolute;
            bottom: 16px;
            left: 16px;
            pointer-events: auto;
            padding: 14px;
            width: 260px;
            background: rgba(17, 20, 24, 0.95);
            border: 1px solid var(--border);
            box-shadow: var(--shadow-lg);
            font-family: var(--sans), sans-serif;
            display: flex;
            flex-direction: column;
            gap: 8px;
        ">
            <h4 style="margin: 0; color: var(--accent); font-size: 13px; font-weight: 600;">Recipe Network Graph</h4>
            <div style="font-size: 11px; display: flex; justify-content: space-between;">
                <span>Nodes (Connected items):</span>
                <strong style="color: var(--text);">${nodeCount}</strong>
            </div>
            <div style="font-size: 11px; display: flex; justify-content: space-between;; margin-bottom: 4px;">
                <span>Total Connections:</span>
                <strong style="color: var(--text);">${edgeCount}</strong>
            </div>
            <div style="
                font-size: 10px;
                line-height: 1.4;
                color: var(--text-dim);
                border-top: 1px solid var(--border);
                padding-top: 8px;
                margin-bottom: 4px;
            ">
                • <strong>Scroll wheel</strong> to zoom in & out<br>
                • <strong>Left-click & drag</strong> to pan<br>
                • <strong>Hover</strong> circles to see recipe flows<br>
                • <strong>Click a circle</strong> to open full details
            </div>
            <button id="recenter-graph-btn" style="
                background: var(--bg-hover);
                border: 1px solid var(--border);
                color: var(--text);
                padding: 6px;
                cursor: pointer;
                font-size: 11px;
                text-align: center;
                width: 100%;
                transition: background var(--transition-fast);
                font-weight: 500;
            ">Recenter Graph</button>
        </div>
    `;

    const btn = overlay.querySelector("#recenter-graph-btn");
    if (btn) {
        btn.addEventListener("click", onRecenter);
        btn.addEventListener("pointerdown", e => e.stopPropagation());
    }
}
