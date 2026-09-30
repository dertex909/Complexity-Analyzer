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

export function renderErrorOverlay(shadow, tabId, scriptUrl, err) {
    const rawFileName = scriptUrl ? scriptUrl.split("/").pop() : tabId;
    const fileName = escapeHtml(rawFileName);
    const errorType = escapeHtml(err?.name || "Plugin Error");
    const errorMsg = escapeHtml(err?.message || String(err));
    const stackTrace = escapeHtml(err?.stack || errorMsg);

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
                border: 1px solid rgba(239, 68, 68, 0.35);
                border-radius: 14px;
                box-shadow: 0 20px 60px rgba(0, 0, 0, 0.65), 0 0 35px rgba(239, 68, 68, 0.12);
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
                border-radius: 20px;
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
                border-radius: 6px;
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
            .err-trace {
                margin: 0;
                padding: 16px;
                background: #0c0d12;
                border: 1px solid #222530;
                border-radius: 8px;
                font-family: "JetBrains Mono", monospace;
                font-size: 12px;
                line-height: 1.6;
                color: #d1d5db;
                white-space: pre-wrap;
                word-break: break-word;
                max-height: 260px;
                overflow-y: auto;
            }
            .err-trace::-webkit-scrollbar {
                width: 8px;
                height: 8px;
            }
            .err-trace::-webkit-scrollbar-thumb {
                background: #2a2e3d;
                border-radius: 4px;
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
                <pre class="err-trace">${stackTrace}</pre>
            </div>
            <div class="err-footer">
                <span>⚡ <b>Hot Reload Active:</b> Resolve the issue in your source file and save changes. The dashboard will automatically recompile and update.</span>
            </div>
        </div>
    `;
}