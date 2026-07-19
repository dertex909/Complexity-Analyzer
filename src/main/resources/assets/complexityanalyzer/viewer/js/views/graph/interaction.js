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

        this._touches = {};
        this._lastPinchDist = null;

        this._setupEventListeners();
    }

    _setupEventListeners() {
        this.canvas.addEventListener("pointerdown", this._onPointerDown.bind(this));
        this.canvas.addEventListener("pointermove", this._onPointerMove.bind(this));
        this.canvas.addEventListener("pointerup", this._onPointerUp.bind(this));
        this.canvas.addEventListener("wheel", this._onWheel.bind(this), {passive: false});

        this.canvas.addEventListener("touchstart", this._onTouchStart.bind(this), {passive: false});
        this.canvas.addEventListener("touchmove", this._onTouchMove.bind(this), {passive: false});
        this.canvas.addEventListener("touchend", this._onTouchEnd.bind(this), {passive: false});
        this.canvas.addEventListener("touchcancel", this._onTouchEnd.bind(this), {passive: false});
    }

    _onPointerDown(e) {
        if (e.pointerType === "touch") return;
        this.canvas.setPointerCapture(e.pointerId);
        this.lastMouse = {x: e.clientX, y: e.clientY};
        this.startMouse = {x: e.clientX, y: e.clientY};
        this.isPanning = true;
    }

    _onPointerMove(e) {
        if (e.pointerType === "touch") return;

        const graphCoords = transformCoords(
            e.clientX, e.clientY,
            this.canvas, this.viewState.pan, this.viewState.zoom
        );

        const now = Date.now();
        if (!this.isPanning && (now - this.lastHoverCheck > GRAPH_CONFIG.HOVER_CHECK_INTERVAL)) {
            this.lastHoverCheck = now;
            const found = findNodeAtPosition(this.nodes, graphCoords.x, graphCoords.y, this.viewState.zoom);
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
        if (e.pointerType === "touch") return;
        try {
            this.canvas.releasePointerCapture(e.pointerId);
        } catch (_) {
        }

        this.isPanning = false;
        this.onViewUpdate();

        const moveDist = Math.hypot(e.clientX - this.startMouse.x, e.clientY - this.startMouse.y);
        if (moveDist < GRAPH_CONFIG.CLICK_THRESHOLD) {
            this._selectAtScreenPoint(e.clientX, e.clientY);
        }
    }

    _onTouchStart(e) {
        e.preventDefault();
        for (const t of e.changedTouches) this._touches[t.identifier] = {x: t.clientX, y: t.clientY};
        const ids = Object.keys(this._touches);

        if (ids.length === 1) {
            this._touchStartX = e.changedTouches[0].clientX;
            this._touchStartY = e.changedTouches[0].clientY;
            this._lastPinchDist = null;
        } else if (ids.length === 2) {
            this._lastPinchDist = this._pinchDist();
        }
    }

    _onTouchMove(e) {
        e.preventDefault();
        for (const t of e.changedTouches) this._touches[t.identifier] = {x: t.clientX, y: t.clientY};
        const ids = Object.keys(this._touches);

        if (ids.length === 1) {
            const id = ids[0];
            const prev = this._lastSingleTouch || this._touches[id];
            const cur = this._touches[id];
            this.viewState.pan.x += cur.x - prev.x;
            this.viewState.pan.y += cur.y - prev.y;
            this._lastSingleTouch = {x: cur.x, y: cur.y};
            this.onViewUpdate();
        } else if (ids.length === 2) {
            const newDist = this._pinchDist();
            if (this._lastPinchDist && newDist > 0) {
                const factor = newDist / this._lastPinchDist;
                const mid = this._pinchMid();
                const pivot = transformCoords(mid.x, mid.y, this.canvas, this.viewState.pan, this.viewState.zoom);
                const newZoom = Math.max(GRAPH_CONFIG.MIN_ZOOM, Math.min(GRAPH_CONFIG.MAX_ZOOM, this.viewState.zoom * factor));
                const bounds = this.canvas.getBoundingClientRect();
                this.viewState.pan.x = mid.x - bounds.left - pivot.x * newZoom;
                this.viewState.pan.y = mid.y - bounds.top - pivot.y * newZoom;
                this.viewState.zoom = newZoom;
                this.onViewUpdate();
            }
            this._lastPinchDist = newDist;
        }
    }

    _onTouchEnd(e) {
        e.preventDefault();
        const wasSingleFinger = Object.keys(this._touches).length === 1;
        const endTouch = e.changedTouches[0];

        for (const t of e.changedTouches) {
            delete this._touches[t.identifier];
        }
        this._lastSingleTouch = null;
        this._lastPinchDist = null;

        if (wasSingleFinger && endTouch) {
            const dx = endTouch.clientX - (this._touchStartX || 0);
            const dy = endTouch.clientY - (this._touchStartY || 0);
            if (Math.hypot(dx, dy) < GRAPH_CONFIG.CLICK_THRESHOLD) this._selectAtScreenPoint(endTouch.clientX, endTouch.clientY);
        }
    }

    _selectAtScreenPoint(clientX, clientY) {
        const gc = transformCoords(clientX, clientY, this.canvas, this.viewState.pan, this.viewState.zoom);
        const hit = findNodeAtPosition(this.nodes, gc.x, gc.y, this.viewState.zoom);

        if (hit) {
            this.viewState.hoveredNode = hit;
            this.viewState.selectedNode = (this.viewState.selectedNode === hit) ? null : hit;
        } else {
            this.viewState.selectedNode = null;
            this.viewState.hoveredNode = null;
        }
        this.onViewUpdate();
    }

    _pinchDist() {
        const ids = Object.keys(this._touches);
        if (ids.length < 2) return 0;
        const a = this._touches[ids[0]];
        const b = this._touches[ids[1]];
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    _pinchMid() {
        const ids = Object.keys(this._touches);
        const a = this._touches[ids[0]];
        const b = this._touches[ids[1]];
        return {x: (a.x + b.x) / 2, y: (a.y + b.y) / 2};
    }

    _onWheel(e) {
        e.preventDefault();
        const pivot = transformCoords(e.clientX, e.clientY, this.canvas, this.viewState.pan, this.viewState.zoom);
        const zoomFactor = e.deltaY > 0 ? GRAPH_CONFIG.ZOOM_FACTOR_OUT : GRAPH_CONFIG.ZOOM_FACTOR;
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