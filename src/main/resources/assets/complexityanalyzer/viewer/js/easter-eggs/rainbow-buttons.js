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

const INTERACTIVE_SELECTOR = "button, input, select, textarea, summary, a[href], .btn, .clickable-header, .tab, .sub-tab, .sidebar-title, [role='button'], [role='tab'], [tabindex]:not([tabindex='-1'])";
let interactiveObserver = null;

export function initRainbowButtonEasterEgg() {
    const categoriesLabel = document.querySelector(".sidebar-title");
    if (!categoriesLabel) return;

    let clickCount = 0;
    categoriesLabel.addEventListener("click", () => {
        clickCount++;
        if (clickCount === 10) activateRainbowButtons();
    });
}

function activateRainbowButtons() {
    document.documentElement.classList.add("rainbow-easter-egg");
    const appRoots = [
        document.querySelector(".workspace"),
        document.querySelector(".footer"),
        document.querySelector("#sidebar-show-btn")
    ].filter(Boolean);

    const randomizeTiming = (target) => {
        const flowDuration = 18 + Math.random() * 10;
        const breatheDuration = 6 + Math.random() * 3.5;
        const flowDelay = -Math.random() * flowDuration;
        const breatheDelay = -Math.random() * breatheDuration;
        target.classList.add("rainbow-easter-target");
        target.style.animationDuration = `${flowDuration.toFixed(2)}s, ${breatheDuration.toFixed(2)}s`;
        target.style.animationDelay = `${flowDelay.toFixed(2)}s, ${breatheDelay.toFixed(2)}s`;
    };

    const isInteractive = (element) => {
        if (element.matches(INTERACTIVE_SELECTOR)) return true;
        if (getComputedStyle(element).cursor !== "pointer") return false;
        return !element.parentElement || getComputedStyle(element.parentElement).cursor !== "pointer";
    };

    const randomizeTree = (node) => {
        if (!(node instanceof Element)) return;
        if (isInteractive(node)) randomizeTiming(node);
        node.querySelectorAll("*").forEach(element => {
            if (isInteractive(element)) randomizeTiming(element);
        });
    };

    appRoots.forEach(randomizeTree);
    interactiveObserver = new MutationObserver(mutations => {
        mutations.forEach(mutation => mutation.addedNodes.forEach(randomizeTree));
    });
    appRoots.forEach(root => interactiveObserver.observe(root, {childList: true, subtree: true}));
}
