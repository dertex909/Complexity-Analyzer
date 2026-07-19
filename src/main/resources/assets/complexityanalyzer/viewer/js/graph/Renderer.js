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

import {createProgram, EDGE_FS, EDGE_VS, NODE_FS, NODE_VS} from './shaders.js';
import {getCatColor, hexToRgb, rectsOverlap} from './utils.js';
import {EDGE_CONSTANTS, LABEL_CONSTANTS} from './constants.js';
import {EdgeRouter} from './EdgeRouter.js';

export class Renderer {
    constructor(gl, textCanvas) {
        this.gl = gl;
        this.textCanvas = textCanvas;
        this.tCtx = textCanvas.getContext("2d");

        gl.enable(gl.BLEND);
        gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);

        this.nodeProgram = createProgram(gl, NODE_VS, NODE_FS);
        this.edgeProgram = createProgram(gl, EDGE_VS, EDGE_FS);

        this.nodeVao = gl.createVertexArray();
        this.nodeVbo = gl.createBuffer();
        this.nodeVerticesCount = 0;

        this.edgeVao = gl.createVertexArray();
        this.edgeVbo = gl.createBuffer();
        this.edgeVerticesCount = 0;

        this.edgeRouter = null;
        this.currentNodes = [];

        this.setupNodeVao();
        this.setupEdgeVao();
    }

    setupNodeVao() {
        const gl = this.gl;
        gl.bindVertexArray(this.nodeVao);
        gl.bindBuffer(gl.ARRAY_BUFFER, this.nodeVbo);

        const stride = 6 * 4;
        gl.enableVertexAttribArray(0);
        gl.vertexAttribPointer(0, 2, gl.FLOAT, false, stride, 0);

        gl.enableVertexAttribArray(1);
        gl.vertexAttribPointer(1, 3, gl.FLOAT, false, stride, 2 * 4);

        gl.enableVertexAttribArray(2);
        gl.vertexAttribPointer(2, 1, gl.FLOAT, false, stride, 5 * 4);
    }

    setupEdgeVao() {
        const gl = this.gl;
        gl.bindVertexArray(this.edgeVao);
        gl.bindBuffer(gl.ARRAY_BUFFER, this.edgeVbo);

        gl.enableVertexAttribArray(0);
        gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 5 * 4, 0);

        gl.enableVertexAttribArray(1);
        gl.vertexAttribPointer(1, 3, gl.FLOAT, false, 5 * 4, 2 * 4);
    }

    uploadNodes(nodes) {
        const gl = this.gl;
        const nodeData = [];

        for (const n of nodes) {
            const rgb = hexToRgb(getCatColor(n.category));
            nodeData.push(n.x, n.y, rgb[0], rgb[1], rgb[2], n.radius * 2);
        }

        gl.bindVertexArray(this.nodeVao);
        gl.bindBuffer(gl.ARRAY_BUFFER, this.nodeVbo);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(nodeData), gl.STATIC_DRAW);

        this.nodeVerticesCount = nodes.length;
        
        // Обновляем роутер при изменении узлов
        this.currentNodes = nodes;
        this.edgeRouter = new EdgeRouter(nodes);
    }

    uploadEdges(edges, edgeStyle) {
        const gl = this.gl;
        const edgeData = [];
        const baseRgb = EDGE_CONSTANTS.BASE_COLOR;

        const pushSegment = (x1, y1, x2, y2) => {
            edgeData.push(x1, y1, baseRgb[0], baseRgb[1], baseRgb[2]);
            edgeData.push(x2, y2, baseRgb[0], baseRgb[1], baseRgb[2]);
        };

        // Используем новый роутер для умной маршрутизации
        if (edgeStyle === "smart" && this.edgeRouter) {
            const routedEdges = this.edgeRouter.routeAllEdges(edges, true, 12);
            
            for (const {edge, path} of routedEdges) {
                for (let i = 0; i < path.length - 1; i++) {
                    pushSegment(path[i].x, path[i].y, path[i + 1].x, path[i + 1].y);
                }
            }
        } else {
            // Старая логика для других стилей
            for (const edge of edges) {
                const s = edge.source;
                const t = edge.target;

                if (edgeStyle === "orthogonal") {
                    const midX = s.x + (t.x - s.x) * 0.5;
                    pushSegment(s.x, s.y, midX, s.y);
                    pushSegment(midX, s.y, midX, t.y);
                    pushSegment(midX, t.y, t.x, t.y);
                } else if (edgeStyle === "curved") {
                    const cp = Math.abs(t.x - s.x) * EDGE_CONSTANTS.CURVE_CONTROL_FACTOR;
                    let prevX = s.x, prevY = s.y;

                    for (let k = 1; k <= EDGE_CONSTANTS.CURVE_STEPS; k++) {
                        const u = k / EDGE_CONSTANTS.CURVE_STEPS;
                        const omt = 1 - u;
                        const px = omt * omt * omt * s.x + 3 * omt * omt * u * (s.x + cp) +
                            3 * omt * u * u * (t.x - cp) + u * u * u * t.x;
                        const py = omt * omt * omt * s.y + 3 * omt * omt * u * s.y +
                            3 * omt * u * u * t.y + u * u * u * t.y;
                        pushSegment(prevX, prevY, px, py);
                        prevX = px;
                        prevY = py;
                    }
                } else {
                    pushSegment(s.x, s.y, t.x, t.y);
                }
            }
        }

        gl.bindVertexArray(this.edgeVao);
        gl.bindBuffer(gl.ARRAY_BUFFER, this.edgeVbo);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(edgeData), gl.STATIC_DRAW);

        this.edgeVerticesCount = edgeData.length / 5;
    }

    render(camera, canvasWidth, canvasHeight, renderState) {
        const gl = this.gl;
        const dpr = window.devicePixelRatio || 1;

        if (gl.canvas.width !== canvasWidth * dpr || gl.canvas.height !== canvasHeight * dpr) {
            gl.canvas.width = canvasWidth * dpr;
            gl.canvas.height = canvasHeight * dpr;
            this.textCanvas.width = canvasWidth * dpr;
            this.textCanvas.height = canvasHeight * dpr;
        }

        gl.viewport(0, 0, gl.canvas.width, gl.canvas.height);
        gl.clearColor(0.047, 0.054, 0.07, 1.0);
        gl.clear(gl.COLOR_BUFFER_BIT);

        const res = [canvasWidth, canvasHeight];

        if (camera.zoom >= EDGE_CONSTANTS.MIN_ZOOM_RENDER) {
            gl.useProgram(this.edgeProgram);
            gl.uniform2f(gl.getUniformLocation(this.edgeProgram, "u_resolution"), res[0], res[1]);
            gl.uniform2f(gl.getUniformLocation(this.edgeProgram, "u_pan"), camera.pan.x, camera.pan.y);
            gl.uniform1f(gl.getUniformLocation(this.edgeProgram, "u_zoom"), camera.zoom);

            gl.bindVertexArray(this.edgeVao);
            gl.drawArrays(gl.LINES, 0, this.edgeVerticesCount);
        }

        gl.useProgram(this.nodeProgram);
        gl.uniform2f(gl.getUniformLocation(this.nodeProgram, "u_resolution"), res[0], res[1]);
        gl.uniform2f(gl.getUniformLocation(this.nodeProgram, "u_pan"), camera.pan.x, camera.pan.y);
        gl.uniform1f(gl.getUniformLocation(this.nodeProgram, "u_zoom"), camera.zoom);

        gl.bindVertexArray(this.nodeVao);
        gl.drawArrays(gl.POINTS, 0, this.nodeVerticesCount);

        this.renderLabels(camera, canvasWidth, canvasHeight, renderState);
    }

    renderLabels(camera, viewW, viewH, renderState) {
        const {visibleNodes, hoveredNode, highlightChain, selectedCategoryFilter} = renderState;

        this.tCtx.clearRect(0, 0, this.textCanvas.width, this.textCanvas.height);
        this.tCtx.save();
        this.tCtx.scale(window.devicePixelRatio || 1, window.devicePixelRatio || 1);

        const labelBoxes = [];

        const sortedNodes = [...visibleNodes].sort((a, b) => {
            const scoreA = (hoveredNode && (a.id === hoveredNode.id ||
                highlightChain.upstream.has(a.id) ||
                highlightChain.downstream.has(a.id))) ? 1000 : a.degree;
            const scoreB = (hoveredNode && (b.id === hoveredNode.id ||
                highlightChain.upstream.has(b.id) ||
                highlightChain.downstream.has(b.id))) ? 1000 : b.degree;
            return scoreB - scoreA;
        });

        for (const node of sortedNodes) {
            const labelInfo = this.shouldShowLabel(node, camera.zoom, hoveredNode,
                highlightChain, selectedCategoryFilter);
            if (!labelInfo.show) continue;

            const screenX = node.x * camera.zoom + camera.pan.x;
            const screenY = node.y * camera.zoom + camera.pan.y;

            this.tCtx.font = "bold 10px var(--sans), system-ui, sans-serif";
            const textWidth = this.tCtx.measureText(node.name).width;

            const labelW = textWidth + LABEL_CONSTANTS.LABEL_PADDING;
            const labelH = LABEL_CONSTANTS.LABEL_HEIGHT;
            const labelX = screenX - labelW / 2;
            const labelY = screenY + (node.radius * camera.zoom) + LABEL_CONSTANTS.LABEL_OFFSET;

            const labelBox = {
                minX: labelX, maxX: labelX + labelW,
                minY: labelY, maxY: labelY + labelH
            };

            const forceDisplay = labelInfo.priority;
            if (forceDisplay || !this.hasCollision(labelBox, labelBoxes)) {
                labelBoxes.push(labelBox);
                this.drawLabel(node.name, screenX, labelY, labelInfo.opacity);
            }
        }

        this.tCtx.restore();
    }

    shouldShowLabel(node, zoom, hoveredNode, highlightChain, selectedCategoryFilter) {
        let show = false;
        let opacity = 1.0;
        let priority = false;

        if (hoveredNode) {
            if (node.id === hoveredNode.id) {
                show = true;
                priority = true;
            } else if (highlightChain.upstream.has(node.id) || highlightChain.downstream.has(node.id)) {
                show = true;
                priority = true;
                opacity = 0.85;
            }
        } else if (selectedCategoryFilter !== "All") {
            if (node.category === selectedCategoryFilter) show = true;
        } else {
            if (zoom >= LABEL_CONSTANTS.ZOOM_FULL_LABELS) {
                show = true;
                opacity = Math.min(1.0, (zoom - 0.6) * 5.0);
            } else if (zoom >= LABEL_CONSTANTS.ZOOM_HIGH_DEGREE &&
                node.degree >= LABEL_CONSTANTS.HIGH_DEGREE_THRESHOLD) {
                show = true;
                opacity = Math.min(0.85, (zoom - 0.38) * 3.5);
            } else if (zoom >= LABEL_CONSTANTS.ZOOM_ULTRA_HIGH_DEGREE &&
                node.degree >= LABEL_CONSTANTS.ULTRA_HIGH_DEGREE_THRESHOLD) {
                show = true;
                opacity = 0.55;
            }
        }

        return {show, opacity, priority};
    }

    hasCollision(box, boxes) {
        for (const existingBox of boxes) if (rectsOverlap(box, existingBox)) return true;
        return false;
    }

    drawLabel(text, x, y, opacity) {
        const ctx = this.tCtx;

        ctx.font = "bold 10px var(--sans), system-ui, sans-serif";
        const textWidth = ctx.measureText(text).width;
        const labelW = textWidth + LABEL_CONSTANTS.LABEL_PADDING;
        const labelH = LABEL_CONSTANTS.LABEL_HEIGHT;
        const labelX = x - labelW / 2;

        ctx.fillStyle = `rgba(13, 17, 23, ${opacity * 0.82})`;
        ctx.fillRect(labelX, y - 2, labelW, labelH);

        ctx.fillStyle = `rgba(235, 241, 248, ${opacity})`;
        ctx.textAlign = "center";
        ctx.textBaseline = "top";
        ctx.strokeStyle = `rgba(10, 12, 16, ${opacity * 0.95})`;
        ctx.lineWidth = 2.5;
        ctx.lineJoin = "round";
        ctx.strokeText(text, x, y);
        ctx.fillText(text, x, y);
    }

    dispose() {
        const gl = this.gl;
        gl.deleteVertexArray(this.nodeVao);
        gl.deleteBuffer(this.nodeVbo);
        gl.deleteVertexArray(this.edgeVao);
        gl.deleteBuffer(this.edgeVbo);
        gl.deleteProgram(this.nodeProgram);
        gl.deleteProgram(this.edgeProgram);
    }
}
