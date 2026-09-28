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

export function initSidebar() {
    initSidebarResizer();
    initSidebarToggle();
}

function initSidebarResizer() {
    const resizer = document.getElementById("sidebar-resizer");
    const sidebar = document.querySelector(".sidebar-left");
    if (!resizer || !sidebar) return;

    const savedWidth = localStorage.getItem("sidebarWidth");
    if (savedWidth) document.documentElement.style.setProperty("--sidebar-width", savedWidth + "px");

    resizer.addEventListener("pointerdown", (e) => {
        e.preventDefault();
        resizer.classList.add("dragging");
        resizer.setPointerCapture(e.pointerId);
        document.body.style.cursor = "col-resize";
        document.body.style.userSelect = "none";

        const onPointerMove = (moveEvent) => {
            let newWidth = moveEvent.clientX;
            if (newWidth < 185) newWidth = 185;
            if (newWidth > 500) newWidth = 500;
            document.documentElement.style.setProperty("--sidebar-width", newWidth + "px");
            localStorage.setItem("sidebarWidth", newWidth);
            window.dispatchEvent(new Event("resize"));
        };

        const onPointerUp = (upEvent) => {
            resizer.classList.remove("dragging");
            try {
                resizer.releasePointerCapture(upEvent.pointerId);
            } catch (ignored) {
            }
            document.body.style.cursor = "";
            document.body.style.userSelect = "";
            resizer.removeEventListener("pointermove", onPointerMove);
            resizer.removeEventListener("pointerup", onPointerUp);
        };

        resizer.addEventListener("pointermove", onPointerMove);
        resizer.addEventListener("pointerup", onPointerUp);
    });
}

function initSidebarToggle() {
    const hideBtn = document.getElementById("sidebar-hide-btn");
    const showBtn = document.getElementById("sidebar-show-btn");
    if (!hideBtn || !showBtn) return;

    const isCollapsed = localStorage.getItem("sidebarCollapsed") === "true";
    if (isCollapsed) document.body.classList.add("collapsed");

    const setCollapsed = (collapsed) => {
        if (collapsed) {
            document.body.classList.add("collapsed");
            localStorage.setItem("sidebarCollapsed", "true");
        } else {
            document.body.classList.remove("collapsed");
            localStorage.setItem("sidebarCollapsed", "false");
        }

        window.dispatchEvent(new Event("resize"));
        setTimeout(() => window.dispatchEvent(new Event("resize")), 150);
        setTimeout(() => window.dispatchEvent(new Event("resize")), 360);
    };

    hideBtn.addEventListener("click", () => setCollapsed(true));
    showBtn.addEventListener("click", () => setCollapsed(false));
}