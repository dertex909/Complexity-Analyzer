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

import {screenToGraph} from './utils.js';
import {CAMERA_CONSTANTS} from './constants.js';

export class InteractionHandler {
    constructor(canvas, controller, onNodeClick, onHoverChange) {
        this.canvas = canvas;
        this.controller = controller;
        this.onNodeClick = onNodeClick;
        this.onHoverChange = onHoverChange;

        this.isPanning = false;
        this.isDraggingMinimap = false;
        this.lastMouse = {x: 0, y: 0};
        this.startMouse = {x: 0, y: 0};

        this.attachListeners();
    }

    attachListeners() {
        this.canvas.addEventListener("pointerdown", e => this.handlePointerDown(e));
        this.canvas.addEventListener("pointermove", e => this.handlePointerMove(e));
        this.canvas.addEventListener("pointerup", e => this.handlePointerUp(e));
        this.canvas.addEventListener("wheel", e => this.handleWheel(e), {passive: false});
    }

    handlePointerDown(e) {
        const bounds = this.canvas.getBoundingClientRect();
        const mouseX = e.clientX - bounds.left;
        const mouseY = e.clientY - bounds.top;

        const minW = 200;
        const minH = 135;
        const minX = this.canvas.clientWidth - minW - 16;
        const minY = this.canvas.clientHeight - minH - 16;

        if (mouseX >= minX && mouseX <= minX + minW &&
            mouseY >= minY && mouseY <= minY + minH) {
            this.isDraggingMinimap = true;
            this.canvas.setPointerCapture(e.pointerId);
            this.handleMinimapDrag(mouseX - minX, mouseY - minY);
            return;
        }

        this.canvas.setPointerCapture(e.pointerId);
        this.lastMouse = {x: e.clientX, y: e.clientY};
        this.startMouse = {x: e.clientX, y: e.clientY};
        this.isPanning = true;
    }

    handlePointerMove(e) {
        if (this.isDraggingMinimap) {
            const bounds = this.canvas.getBoundingClientRect();
            const minW = 200;
            const minH = 135;
            const mx = this.canvas.clientWidth - minW - 16;
            const my = this.canvas.clientHeight - minH - 16;
            this.handleMinimapDrag(e.clientX - bounds.left - mx, e.clientY - bounds.top - my);
            return;
        }

        if (!this.isPanning) {
            const node = this.controller.findNodeAt(e.clientX, e.clientY);

            if (this.controller.hoveredNode !== node) {
                this.controller.hoveredNode = node;
                this.controller.updateHighlights();
                if (this.onHoverChange) this.onHoverChange(node);
            }
        }

        if (this.isPanning) {
            const dx = e.clientX - this.lastMouse.x;
            const dy = e.clientY - this.lastMouse.y;
            this.controller.camera.panBy(dx, dy);
            this.controller.needsRedraw = true;
        }

        this.lastMouse = {x: e.clientX, y: e.clientY};
    }

    handlePointerUp(e) {
        try {
            this.canvas.releasePointerCapture(e.pointerId);
        } catch (_) {
        }

        const wasPanning = this.isPanning;
        this.isPanning = false;
        this.isDraggingMinimap = false;

        const moveDist = Math.hypot(e.clientX - this.startMouse.x, e.clientY - this.startMouse.y);
        if (moveDist < 5 && !wasPanning) {
            const node = this.controller.findNodeAt(e.clientX, e.clientY);
            if (node && this.onNodeClick) {
                this.onNodeClick(node.id);
            } else if (!node) {
                this.controller.selectedNodeId = -1;
                this.controller.needsRedraw = true;
            }
        }
    }

    handleWheel(e) {
        e.preventDefault();

        const pivot = screenToGraph(e.clientX, e.clientY, this.canvas, this.controller.camera.getState());
        const zoomFactor = e.deltaY > 0 ?
            CAMERA_CONSTANTS.ZOOM_FACTOR_OUT :
            CAMERA_CONSTANTS.ZOOM_FACTOR_IN;

        const bounds = this.canvas.getBoundingClientRect();
        const pivotScreen = {
            x: e.clientX - bounds.left,
            y: e.clientY - bounds.top
        };

        this.controller.camera.zoomAt(zoomFactor, pivot, pivotScreen);
        this.controller.needsRedraw = true;
    }

    handleMinimapDrag(mx, my) {
        const minW = 200;
        const minH = 135;
        const bounds = this.controller.getBounds();
        const mapScale = Math.min((minW - 16) / bounds.width, (minH - 16) / bounds.height);
        const mapOffsetX = 8 + (minW - 16 - bounds.width * mapScale) / 2;
        const mapOffsetY = 8 + (minH - 16 - bounds.height * mapScale) / 2;

        const graphX = bounds.minX + (mx - mapOffsetX) / mapScale;
        const graphY = bounds.minY + (my - mapOffsetY) / mapScale;

        const camera = this.controller.camera;
        camera.targetPan.x = camera.pan.x = this.canvas.clientWidth / 2 - graphX * camera.zoom;
        camera.targetPan.y = camera.pan.y = this.canvas.clientHeight / 2 - graphY * camera.zoom;
        this.controller.needsRedraw = true;
    }

    isPanningActive() {
        return this.isPanning || this.isDraggingMinimap;
    }
}
