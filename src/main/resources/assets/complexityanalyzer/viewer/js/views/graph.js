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

import {selectItem, state} from "../core/state.js";
import {LAYOUT_VERSION} from "./graph/constants.js";
import {GraphCache} from "./graph/cache.js";
import {applyInitialLayoutAsync, buildGraphData} from "./graph/data-builder.js";
import {GraphRenderer} from "./graph/renderer.js";
import {InteractionHandler} from "./graph/interaction.js";
import {calculateFitView} from "./graph/utils.js";
import {removeLoader, showLoader, updateSidePanel} from "./graph/ui.js";

let activeRenderer = null;
let activeResizeListener = null;
const graphCache = new GraphCache();

export async function renderGraph(container) {
    const db = state.db;
    if (!db) return;

    showLoader(container);

    const canvas = container.querySelector("#graph-canvas");
    if (!canvas) return;

    cleanup();

    const newCanvas = canvas.cloneNode(true);
    canvas.parentNode.replaceChild(newCanvas, canvas);
    const mCanvas = newCanvas;

    const currentHash = db.file.fileHash.toString();
    const cacheKey = `${currentHash}_${LAYOUT_VERSION}`;

    let activeNodes, resolvedEdges, neighborMap;

    const cached = graphCache.get(cacheKey);
    if (cached) {
        activeNodes = cached.nodes;
        resolvedEdges = cached.edges;
        neighborMap = cached.neighborMap;
    } else {
        const graphData = buildGraphData(db);
        activeNodes = graphData.nodes;
        resolvedEdges = graphData.edges;
        neighborMap = graphData.neighborMap;

        await applyInitialLayoutAsync(activeNodes, resolvedEdges, (percent) => {
            const subtitle = container.querySelector("#graph-loader div:last-child");
            if (subtitle) subtitle.textContent = `Calculating layout: ${percent}%`;
        });

        graphCache.set(cacheKey, activeNodes, resolvedEdges, neighborMap);
    }

    const viewState = {zoom: 1.0, pan: {x: 0, y: 0}, hoveredNode: null, selectedNode: null};
    const overlay = container.querySelector("#graph-overlay");

    const renderer = new GraphRenderer(mCanvas);

    let currentSelectedNode = null;
    const updatePanelIfChanged = () => {
        if (currentSelectedNode !== viewState.selectedNode) {
            currentSelectedNode = viewState.selectedNode;
            const onOpenDetails = currentSelectedNode ? () => selectItem(currentSelectedNode.id) : null;
            updateSidePanel(overlay, currentSelectedNode, () => {
                viewState.selectedNode = null;
                viewState.hoveredNode = null;
                updatePanelIfChanged();
                draw();
            }, onOpenDetails);
        }
    };

    let renderPending = false;
    const draw = () => {
        if (renderPending) return;
        renderPending = true;
        requestAnimationFrame(() => {
            renderPending = false;
            updatePanelIfChanged();
            renderer.render(activeNodes, resolvedEdges, neighborMap, viewState);
        });
    };

    const recenterGraph = () => {
        if (activeNodes.length === 0) return;
        const fitView = calculateFitView(activeNodes, mCanvas.clientWidth, mCanvas.clientHeight);
        viewState.zoom = fitView.zoom;
        viewState.pan = fitView.pan;
        draw();
    };

    new InteractionHandler(mCanvas, activeNodes, viewState, draw);

    activeRenderer = renderer;

    removeLoader(container);
    recenterGraph();

    const onResize = () => draw();
    window.addEventListener("resize", onResize);
    activeResizeListener = onResize;
}

function cleanup() {
    if (activeRenderer) {
        if (typeof activeRenderer.destroy === 'function') activeRenderer.destroy();
        activeRenderer = null;
    }
    if (activeResizeListener) {
        window.removeEventListener("resize", activeResizeListener);
        activeResizeListener = null;
    }
}