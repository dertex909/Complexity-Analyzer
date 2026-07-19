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
import {getCategoryColor, isNeighbor} from "./utils.js";

export class GraphRenderer {
    constructor(canvas) {
        this.canvas = canvas;
        this.ctx = canvas.getContext("2d");
    }

    render(nodes, edges, neighborMap, viewState) {
        const {zoom, pan, hoveredNode} = viewState;
        const w = this.canvas.clientWidth;
        const h = this.canvas.clientHeight;
        const dpr = window.devicePixelRatio || 1;

        if (this.canvas.width !== w * dpr || this.canvas.height !== h * dpr) {
            this.canvas.width = w * dpr;
            this.canvas.height = h * dpr;
        }

        this.ctx.save();
        this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
        this.ctx.scale(dpr, dpr);

        this.ctx.translate(pan.x, pan.y);
        this.ctx.scale(zoom, zoom);

        const bounds = this._calculateViewportBounds(w, h, pan, zoom);

        if (hoveredNode) this._drawHighlightedEdges(edges, hoveredNode, bounds, zoom);

        this._drawNodes(nodes, neighborMap, hoveredNode, bounds, zoom);
        this._drawLabels(nodes, neighborMap, hoveredNode, bounds, zoom);

        this.ctx.restore();
    }

    _calculateViewportBounds(w, h, pan, zoom) {
        const minGraphX = -pan.x / zoom;
        const maxGraphX = (w - pan.x) / zoom;
        const minGraphY = -pan.y / zoom;
        const maxGraphY = (h - pan.y) / zoom;

        const pad = GRAPH_CONFIG.EDGE_PAD;

        return {
            minX: minGraphX - pad,
            maxX: maxGraphX + pad,
            minY: minGraphY - pad,
            maxY: maxGraphY + pad
        };
    }

    _drawHighlightedEdges(edges, hoveredNode, bounds, zoom) {
        this.ctx.strokeStyle = GRAPH_CONFIG.HOVER_EDGE_COLOR;
        this.ctx.globalAlpha = GRAPH_CONFIG.HOVER_OPACITY_NEIGHBOR;
        this.ctx.lineWidth = GRAPH_CONFIG.HOVER_EDGE_WIDTH / zoom;
        this.ctx.beginPath();

        for (const edge of edges) {
            const hA = edge.source;
            const hB = edge.target;

            if (hA === hoveredNode || hB === hoveredNode) {
                const segMinX = Math.min(hA.x, hB.x);
                const segMaxX = Math.max(hA.x, hB.x);
                const segMinY = Math.min(hA.y, hB.y);
                const segMaxY = Math.max(hA.y, hB.y);

                if (segMaxX < bounds.minX || segMinX > bounds.maxX ||
                    segMaxY < bounds.minY || segMinY > bounds.maxY) {
                    continue;
                }

                this.ctx.moveTo(hA.x, hA.y);
                this.ctx.lineTo(hB.x, hB.y);
            }
        }

        this.ctx.stroke();
        this.ctx.globalAlpha = 1.0;
    }

    _drawNodes(nodes, neighborMap, hoveredNode, bounds, zoom) {
        const drawStroke = zoom >= GRAPH_CONFIG.STROKE_MIN_ZOOM;

        for (const node of nodes) {
            const rDraw = Math.max(node.radius, GRAPH_CONFIG.MIN_DRAW_RADIUS / zoom);
            const rLimit = rDraw + GRAPH_CONFIG.NODE_RENDER_BUFFER;

            if (node.x < bounds.minX - rLimit || node.x > bounds.maxX + rLimit ||
                node.y < bounds.minY - rLimit || node.y > bounds.maxY + rLimit) {
                continue;
            }

            const appearance = this._getNodeAppearance(node, hoveredNode, neighborMap, zoom);

            this.ctx.globalAlpha = appearance.opacity;
            this.ctx.fillStyle = getCategoryColor(node.category);
            this.ctx.strokeStyle = appearance.strokeColor;
            this.ctx.lineWidth = appearance.strokeWidth;

            this.ctx.beginPath();
            this.ctx.arc(node.x, node.y, rDraw, 0, 2 * Math.PI);
            this.ctx.fill();
            if (drawStroke) this.ctx.stroke();
        }

        this.ctx.globalAlpha = 1.0;
    }

    _getNodeAppearance(node, hoveredNode, neighborMap, zoom) {
        let opacity = GRAPH_CONFIG.HOVER_OPACITY_FULL;
        let strokeColor = GRAPH_CONFIG.STROKE_COLOR_NORMAL;
        let strokeWidth = GRAPH_CONFIG.STROKE_WIDTH_NORMAL / zoom;

        if (hoveredNode) if (node === hoveredNode) {
            opacity = GRAPH_CONFIG.HOVER_OPACITY_FULL;
            strokeColor = GRAPH_CONFIG.STROKE_COLOR_HOVER;
            strokeWidth = GRAPH_CONFIG.STROKE_WIDTH_HOVER / zoom;
        } else if (isNeighbor(node, hoveredNode, neighborMap)) {
            opacity = GRAPH_CONFIG.HOVER_OPACITY_NEIGHBOR;
            strokeColor = GRAPH_CONFIG.STROKE_COLOR_NEIGHBOR;
            strokeWidth = GRAPH_CONFIG.STROKE_WIDTH_NEIGHBOR / zoom;
        } else {
            opacity = GRAPH_CONFIG.HOVER_OPACITY_OTHER;
        }

        return {opacity, strokeColor, strokeWidth};
    }

    _drawLabels(nodes, neighborMap, hoveredNode, bounds, zoom) {
        for (const node of nodes) {
            const rLimit = node.radius + GRAPH_CONFIG.LABEL_RENDER_BUFFER;

            if (node.x < bounds.minX - rLimit || node.x > bounds.maxX + rLimit ||
                node.y < bounds.minY - rLimit || node.y > bounds.maxY + rLimit) {
                continue;
            }

            const labelState = this._shouldDrawLabel(node, hoveredNode, neighborMap, zoom);

            if (labelState.shouldDraw) this._drawNodeLabel(node, labelState.opacity, zoom);
        }
    }

    _shouldDrawLabel(node, hoveredNode, neighborMap, zoom) {
        let shouldDraw = false;
        let opacity = 1.0;

        if (hoveredNode) {
            if (node === hoveredNode) {
                shouldDraw = true;
                opacity = GRAPH_CONFIG.HOVER_OPACITY_FULL;
            } else if (isNeighbor(node, hoveredNode, neighborMap)) {
                shouldDraw = true;
                opacity = 0.85;
            }
        } else {
            if (zoom >= GRAPH_CONFIG.LABEL_FULL_ZOOM) {
                shouldDraw = true;
                opacity = Math.min(1.0, (zoom - GRAPH_CONFIG.LABEL_FADE_START) * GRAPH_CONFIG.LABEL_FADE_FACTOR);
            }
            else if (zoom >= GRAPH_CONFIG.LABEL_HUB_ZOOM && node.degree >= GRAPH_CONFIG.LABEL_HUB_MIN_DEGREE) {
                shouldDraw = true;
                opacity = Math.min(
                    GRAPH_CONFIG.LABEL_MAX_OPACITY,
                    (zoom - GRAPH_CONFIG.LABEL_HUB_FADE_START) * GRAPH_CONFIG.LABEL_HUB_FADE_FACTOR
                );
            }
        }

        return {shouldDraw, opacity};
    }

    _drawNodeLabel(node, opacity, zoom) {
        this.ctx.fillStyle = `rgba(${GRAPH_CONFIG.LABEL_COLOR}, ${opacity})`;
        this.ctx.font = "bold 9px system-ui, -apple-system, sans-serif";
        this.ctx.textAlign = "center";
        this.ctx.textBaseline = "top";

        this.ctx.strokeStyle = `rgba(${GRAPH_CONFIG.LABEL_STROKE_COLOR}, ${opacity * 0.95})`;
        this.ctx.lineWidth = GRAPH_CONFIG.LABEL_STROKE_WIDTH / zoom;
        this.ctx.lineJoin = "round";

        const labelY = node.y + node.radius + GRAPH_CONFIG.LABEL_OFFSET;

        this.ctx.strokeText(node.name, node.x, labelY);
        this.ctx.fillText(node.name, node.x, labelY);
    }
}
