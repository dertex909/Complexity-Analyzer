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

import {selectItem} from "../../core/state.js";
import {GRAPH_CONFIG} from "./constants.js";
import {findNodeAtPosition, transformCoords} from "./utils.js";

export class InteractionHandler {
    constructor(canvas, nodes, viewState, onViewUpdate) {
        this.canvas = canvas;
        this.nodes = nodes;
        this.viewState = viewState;
        this.onViewUpdate = onViewUpdate;

        this.isPanning = false;
        this.lastMouse = {x: 0, y: 0};
        this.startMouse = {x: 0, y: 0};
        this.lastHoverCheck = 0;

        this._setupEventListeners();
    }

    _setupEventListeners() {
        this.canvas.addEventListener("pointerdown", this._onPointerDown.bind(this));
        this.canvas.addEventListener("pointermove", this._onPointerMove.bind(this));
        this.canvas.addEventListener("pointerup", this._onPointerUp.bind(this));
        this.canvas.addEventListener("wheel", this._onWheel.bind(this), {passive: false});
    }

    _onPointerDown(e) {
        this.canvas.setPointerCapture(e.pointerId);
        this.lastMouse = {x: e.clientX, y: e.clientY};
        this.startMouse = {x: e.clientX, y: e.clientY};
        this.isPanning = true;
    }

    _onPointerMove(e) {
        const graphCoords = transformCoords(
            e.clientX,
            e.clientY,
            this.canvas,
            this.viewState.pan,
            this.viewState.zoom
        );

        const now = Date.now();
        if (!this.isPanning && (now - this.lastHoverCheck > GRAPH_CONFIG.HOVER_CHECK_INTERVAL)) {
            this.lastHoverCheck = now;

            const found = findNodeAtPosition(
                this.nodes,
                graphCoords.x,
                graphCoords.y,
                this.viewState.zoom
            );

            if (this.viewState.hoveredNode !== found) {
                this.viewState.hoveredNode = found;
                this.onViewUpdate();
            }
        }

        if (this.isPanning) {
            this.viewState.pan.x += e.clientX - this.lastMouse.x;
            this.viewState.pan.y += e.clientY - this.lastMouse.y;
            this.onViewUpdate();
        }

        this.lastMouse = {x: e.clientX, y: e.clientY};
    }

    _onPointerUp(e) {
        try {
            this.canvas.releasePointerCapture(e.pointerId);
        } catch (_) {
        }

        this.isPanning = false;
        this.onViewUpdate();

        const moveDist = Math.hypot(e.clientX - this.startMouse.x, e.clientY - this.startMouse.y);
        if (moveDist < GRAPH_CONFIG.CLICK_THRESHOLD && this.viewState.hoveredNode) selectItem(this.viewState.hoveredNode.id);
    }

    _onWheel(e) {
        e.preventDefault();

        const pivot = transformCoords(
            e.clientX,
            e.clientY,
            this.canvas,
            this.viewState.pan,
            this.viewState.zoom
        );

        const zoomFactor = e.deltaY > 0
            ? GRAPH_CONFIG.ZOOM_FACTOR_OUT
            : GRAPH_CONFIG.ZOOM_FACTOR;

        const newZoom = Math.max(
            GRAPH_CONFIG.MIN_ZOOM,
            Math.min(GRAPH_CONFIG.MAX_ZOOM, this.viewState.zoom * zoomFactor)
        );

        const bounds = this.canvas.getBoundingClientRect();
        this.viewState.pan.x = e.clientX - bounds.left - pivot.x * newZoom;
        this.viewState.pan.y = e.clientY - bounds.top - pivot.y * newZoom;
        this.viewState.zoom = newZoom;

        this.onViewUpdate();
    }
}
