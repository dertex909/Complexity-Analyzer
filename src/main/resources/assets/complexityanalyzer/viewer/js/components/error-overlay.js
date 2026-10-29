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

function escapeHtml(str) {
    return String(str ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#039;");
}

function copyToClipboard(text) {
    if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(text);
    const ta = document.createElement("textarea");
    ta.value = text;
    ta.style.position = "fixed";
    ta.style.left = "-9999px";
    ta.style.top = "-9999px";
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    try {
        // noinspection JSDeprecatedSymbols
        document.execCommand("copy");
    } finally {
        ta.remove();
    }
    return Promise.resolve();
}

export function renderErrorOverlay(shadow, tabId, scriptUrl, err) {
    const rawFileName = scriptUrl ? scriptUrl.split("/").pop() : tabId;
    const fileName = escapeHtml(rawFileName);
    const errorType = escapeHtml(err?.name || "Plugin Error");
    const errorMsg = escapeHtml(err?.message || String(err));
    const rawStackTrace = err?.stack || errorMsg;
    const stackTrace = escapeHtml(rawStackTrace);

    shadow.innerHTML = `
        <style>
            :host {
                display: flex;
                align-items: center;
                justify-content: center;
                width: 100%;
                height: 100%;
                min-height: 480px;
                padding: 24px;
                box-sizing: border-box;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            }
            .err-card {
                position: relative;
                width: 100%;
                max-width: 780px;
                background: #14161d;
                border: 1px solid rgba(239, 68, 68, 0.45);
                border-radius: 0;
                box-shadow: 0 20px 60px rgba(0, 0, 0, 0.75), 0 0 35px rgba(239, 68, 68, 0.15);
                overflow: hidden;
                animation: ca-pop .18s cubic-bezier(0.16, 1, 0.3, 1);
            }
            .err-header {
                display: flex;
                align-items: center;
                justify-content: space-between;
                padding: 14px 20px;
                background: #1c1f28;
                border-bottom: 1px solid #282c37;
            }
            .err-badge {
                display: inline-flex;
                align-items: center;
                gap: 6px;
                padding: 4px 10px;
                font-size: 11px;
                font-weight: 700;
                text-transform: uppercase;
                letter-spacing: 1px;
                border-radius: 0;
                background: rgba(239, 68, 68, 0.15);
                border: 1px solid rgba(239, 68, 68, 0.35);
                color: #ef4444;
            }
            .err-file {
                font-size: 12px;
                color: #9aa0ac;
                font-family: "JetBrains Mono", monospace;
                background: rgba(255, 255, 255, 0.04);
                padding: 3px 8px;
                border-radius: 0;
                border: 1px solid rgba(255, 255, 255, 0.06);
            }
            .err-body {
                padding: 20px 24px;
            }
            .err-msg {
                margin: 0 0 16px;
                font-size: 15px;
                font-weight: 600;
                color: #f87171;
                line-height: 1.5;
                font-family: "JetBrains Mono", monospace;
            }
            .err-trace-container {
                position: relative;
            }
            .err-trace {
                margin: 0;
                padding: 16px 70px 16px 16px;
                background: #0c0d12;
                border: 1px solid #222530;
                border-radius: 0;
                font-family: "JetBrains Mono", monospace;
                font-size: 12px;
                line-height: 1.6;
                color: #d1d5db;
                white-space: pre-wrap;
                word-break: break-word;
                max-height: 260px;
                overflow-y: auto;
                cursor: pointer;
                transition: border-color 0.15s, background-color 0.15s;
            }
            .err-trace:hover {
                border-color: #3b4256;
                background: #0f1117;
            }
            .err-copy-hint {
                position: absolute;
                top: 8px;
                right: 8px;
                font-size: 10px;
                font-weight: 700;
                text-transform: uppercase;
                letter-spacing: 1px;
                padding: 3px 6px;
                background: #1c1f28;
                border: 1px solid #282c37;
                border-radius: 0;
                color: #8b92a4;
                pointer-events: none;
                transition: all 0.15s;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            }
            .err-trace:hover + .err-copy-hint,
            .err-copy-hint:hover {
                color: #e2e8f0;
                border-color: #4b5563;
            }
            .err-copy-hint.copied {
                color: #4ade80 !important;
                border-color: rgba(74, 222, 128, 0.4) !important;
                background: rgba(74, 222, 128, 0.12) !important;
            }
            .err-trace::-webkit-scrollbar {
                width: 8px;
                height: 8px;
            }
            .err-trace::-webkit-scrollbar-thumb {
                background: #2a2e3d;
                border-radius: 0;
            }
            .err-trace::-webkit-scrollbar-thumb:hover {
                background: #3b4256;
            }
            .err-footer {
                display: flex;
                align-items: center;
                gap: 8px;
                padding: 12px 24px;
                background: #101217;
                border-top: 1px solid #1e212b;
                font-size: 12px;
                color: #8b92a4;
                line-height: 1.4;
            }
            .err-footer b {
                color: #e2e8f0;
            }
            @keyframes ca-pop {
                from { opacity: 0; transform: scale(0.97) translateY(8px); }
                to { opacity: 1; transform: scale(1) translateY(0); }
            }
        </style>
        <div class="err-card">
            <div class="err-header">
                <span class="err-badge">⚠️ Build Failed</span>
                <span class="err-file">${fileName}</span>
            </div>
            <div class="err-body">
                <div class="err-msg">${errorType}: ${errorMsg}</div>
                <div class="err-trace-container">
                    <pre class="err-trace" title="Click to copy stack trace">${stackTrace}</pre>
                    <span class="err-copy-hint">Copy</span>
                </div>
            </div>
            <div class="err-footer">
                <span>⚡ <b>Hot Reload Active:</b> Resolve the issue in your source file and save changes. The dashboard will automatically recompile and update.</span>
            </div>
        </div>
    `;

    const traceEl = shadow.querySelector(".err-trace");
    const hintEl = shadow.querySelector(".err-copy-hint");

    if (traceEl) traceEl.addEventListener("click", () => {
        copyToClipboard(rawStackTrace).then(() => {
            if (hintEl) {
                hintEl.textContent = "Copied!";
                hintEl.classList.add("copied");
                setTimeout(() => {
                    hintEl.textContent = "Copy";
                    hintEl.classList.remove("copied");
                }, 1400);
            }
        });
    });

}