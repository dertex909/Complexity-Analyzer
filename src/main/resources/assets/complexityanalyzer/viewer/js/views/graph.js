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

import {selectItem, state, store} from "../core/state.js";
import {GraphController} from "../graph/GraphController.js";
import {InteractionHandler} from "../graph/InteractionHandler.js";
import {UIManager} from "../graph/UIManager.js";

let activeAnimationId = null;

export async function renderGraph(container) {
    const db = state.db;
    if (!db) return;

    if (activeAnimationId) {
        cancelAnimationFrame(activeAnimationId);
        activeAnimationId = null;
    }
    if (window.caGraphCleanup) window.caGraphCleanup();
    const originalCanvas = container.querySelector("#graph-canvas");
    if (!originalCanvas) return;

    const canvas = originalCanvas.cloneNode(true);
    originalCanvas.parentNode.replaceChild(canvas, originalCanvas);

    let textCanvas = container.querySelector("#graph-text-canvas");
    if (!textCanvas) {
        textCanvas = document.createElement("canvas");
        textCanvas.id = "graph-text-canvas";
        textCanvas.style.position = "absolute";
        textCanvas.style.inset = "0";
        textCanvas.style.pointerEvents = "none";
        textCanvas.style.zIndex = "2";
        canvas.parentNode.insertBefore(textCanvas, canvas.nextSibling);
    }

    try {
        const controller = new GraphController(canvas, textCanvas, db);
        controller.initialize();
        if (state.selectedItem >= 0) {
            controller.selectedNodeId = state.selectedItem;
            controller.focusOnNode(state.selectedItem);
        }

        const uiManager = new UIManager(container, controller);
        uiManager.initialize();

        const interactionHandler = new InteractionHandler(canvas, controller, (nodeId) => {
            selectItem(nodeId);
        }, (node) => {
            uiManager.updateHoverDetails(node);
        });

        const originalUpdate = controller.update.bind(controller);

        controller.update = function () {
            controller.isPanning = interactionHandler.isPanningActive();
            return originalUpdate();
        };

        controller.startAnimation();
        activeAnimationId = controller.animationId;

        const onSelectChange = () => {
            if (state.selectedItem >= 0 && state.selectedItem !== controller.selectedNodeId) {
                controller.focusOnNode(state.selectedItem);
                uiManager.updateStats();
            }
        };

        store.addEventListener("selectItem", onSelectChange);

        window.caGraphCleanup = () => {
            store.removeEventListener("selectItem", onSelectChange);
            controller.dispose();
        };
    } catch (error) {
        console.error("Error initializing graph:", error);
    }
}
