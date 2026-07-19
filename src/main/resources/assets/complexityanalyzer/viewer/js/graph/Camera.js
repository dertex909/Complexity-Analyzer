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

import {CAMERA_CONSTANTS} from './constants.js';

export class Camera {
    constructor() {
        this.zoom = 1.0;
        this.pan = {x: 0, y: 0};
        this.targetZoom = 1.0;
        this.targetPan = {x: 0, y: 0};
    }

    update(isPanning) {
        if (isPanning) return false;

        const panDx = this.targetPan.x - this.pan.x;
        const panDy = this.targetPan.y - this.pan.y;
        const zoomD = this.targetZoom - this.zoom;

        if (Math.abs(panDx) > 0.05 || Math.abs(panDy) > 0.05 || Math.abs(zoomD) > 0.001) {
            this.pan.x += panDx * CAMERA_CONSTANTS.PAN_LERP_SPEED;
            this.pan.y += panDy * CAMERA_CONSTANTS.PAN_LERP_SPEED;
            this.zoom += zoomD * CAMERA_CONSTANTS.PAN_LERP_SPEED;
            return true;
        } else {
            this.pan.x = this.targetPan.x;
            this.pan.y = this.targetPan.y;
            this.zoom = this.targetZoom;
            return false;
        }
    }

    zoomAt(zoomFactor, pivotGraph, pivotScreen) {
        this.targetZoom = Math.max(CAMERA_CONSTANTS.MIN_ZOOM, Math.min(CAMERA_CONSTANTS.MAX_ZOOM, this.targetZoom * zoomFactor));
        this.targetPan.x = pivotScreen.x - pivotGraph.x * this.targetZoom;
        this.targetPan.y = pivotScreen.y - pivotGraph.y * this.targetZoom;
    }

    panBy(dx, dy) {
        this.pan.x += dx;
        this.pan.y += dy;
        this.targetPan.x = this.pan.x;
        this.targetPan.y = this.pan.y;
    }

    fitToBounds(bounds, canvasWidth, canvasHeight, smooth = true) {
        const pad = CAMERA_CONSTANTS.VIEWPORT_PADDING;
        const targetW = canvasWidth - pad * 2;
        const targetH = canvasHeight - pad * 2;

        const z = Math.max(
            CAMERA_CONSTANTS.FIT_PADDING_MIN,
            Math.min(CAMERA_CONSTANTS.FIT_PADDING_MAX, Math.min(targetW / bounds.width, targetH / bounds.height))
        );

        const px = canvasWidth / 2 - (bounds.minX + bounds.width / 2) * z;
        const py = canvasHeight / 2 - (bounds.minY + bounds.height / 2) * z;

        if (smooth) {
            this.targetZoom = z;
            this.targetPan.x = px;
            this.targetPan.y = py;
        } else {
            this.zoom = this.targetZoom = z;
            this.pan.x = this.targetPan.x = px;
            this.pan.y = this.targetPan.y = py;
        }
    }

    focusOn(x, y, canvasWidth, canvasHeight, zoom = null) {
        if (zoom !== null) this.targetZoom = Math.max(
            CAMERA_CONSTANTS.MIN_ZOOM,
            Math.min(CAMERA_CONSTANTS.MAX_ZOOM, zoom)
        );
        this.targetPan.x = canvasWidth / 2 - x * this.targetZoom;
        this.targetPan.y = canvasHeight / 2 - y * this.targetZoom;
    }

    getState() {
        return {zoom: this.zoom, pan: this.pan};
    }
}
