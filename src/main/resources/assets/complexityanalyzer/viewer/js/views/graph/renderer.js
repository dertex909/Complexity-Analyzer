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

function hexToRgb(hex) {
    if (!hex) return [1, 1, 1];
    let r = 0, g = 0, b = 0;
    if (hex.length === 7) {
        r = parseInt(hex.slice(1, 3), 16) / 255;
        g = parseInt(hex.slice(3, 5), 16) / 255;
        b = parseInt(hex.slice(5, 7), 16) / 255;
    } else if (hex.startsWith('rgba')) {
        const parts = hex.substring(hex.indexOf('(') + 1, hex.lastIndexOf(')')).split(',');
        r = parseInt(parts[0]) / 255;
        g = parseInt(parts[1]) / 255;
        b = parseInt(parts[2]) / 255;
    }
    return [r, g, b];
}

const VS_NODE = `
attribute vec2 a_position;
attribute float a_radius;
attribute vec3 a_color;
attribute float a_opacity;

uniform vec2 u_resolution;
uniform vec2 u_pan;
uniform float u_zoom;
uniform float u_pixelRatio;

varying vec3 v_color;
varying float v_opacity;
varying float v_radius;

void main() {
    vec2 pos = (a_position * u_zoom) + u_pan;
    vec2 clipSpace = (pos / u_resolution) * 2.0 - 1.0;
    gl_Position = vec4(clipSpace * vec2(1, -1), 0, 1);
    
    float r = max(a_radius * u_zoom, 2.0);
    gl_PointSize = r * 2.0 * u_pixelRatio;
    v_color = a_color;
    v_opacity = a_opacity;
    v_radius = r * u_pixelRatio;
}
`;

const FS_NODE = `
precision mediump float;
varying vec3 v_color;
varying float v_opacity;
varying float v_radius;

void main() {
    vec2 coord = gl_PointCoord - vec2(0.5);
    float dist = length(coord) * 2.0;
    if (dist > 1.0) discard;
    
    float delta = 1.0 / max(v_radius, 1.0);
    float alpha = 1.0 - smoothstep(1.0 - delta, 1.0, dist);
    
    gl_FragColor = vec4(v_color, v_opacity * alpha);
}
`;

const VS_EDGE = `
attribute vec2 a_position;
attribute vec3 a_color;
attribute float a_opacity;
attribute float a_t;
attribute float a_isAnimated;
attribute float a_sourceId;
attribute float a_targetId;

uniform vec2 u_resolution;
uniform vec2 u_pan;
uniform float u_zoom;
uniform float u_time;
uniform float u_activeNodeId;

varying vec3 v_color;
varying float v_opacity;
varying float v_t;
varying float v_isAnimated;

void main() {
    vec2 pos = (a_position * u_zoom) + u_pan;
    vec2 clipSpace = (pos / u_resolution) * 2.0 - 1.0;
    gl_Position = vec4(clipSpace * vec2(1, -1), 0, 1);
    
    v_t = a_t;
    
    if (u_activeNodeId >= 0.0) {
        if (abs(a_sourceId - u_activeNodeId) < 0.1 || abs(a_targetId - u_activeNodeId) < 0.1) {
            v_color = vec3(0.35, 0.75, 1.0);
            v_opacity = 0.95;
            v_isAnimated = 1.0;
        } else {
            v_color = a_color;
            v_opacity = 0.02;
            v_isAnimated = 0.0;
        }
    } else {
        v_color = a_color;
        v_opacity = a_opacity;
        v_isAnimated = a_isAnimated;
    }
}
`;

const FS_EDGE = `
precision mediump float;
varying vec3 v_color;
varying float v_opacity;
varying float v_t;
varying float v_isAnimated;

uniform float u_time;

void main() {
    if (v_isAnimated > 0.5) {
        float phase = fract(v_t - u_time * 0.55);
        float pulse = pow(max(0.0, 1.0 - abs(phase - 0.5) * 6.0), 2.0);
        float base = 0.35 + smoothstep(0.0, 0.5, v_t) * 0.45;
        vec3 bright = min(v_color * 3.0 + 0.5, vec3(1.0));
        vec3 col = mix(v_color * base, bright, pulse);
        gl_FragColor = vec4(col, v_opacity * (base + pulse * 0.65));
    } else {
        gl_FragColor = vec4(v_color, v_opacity);
    }
}
`;

export class GraphRenderer {
    constructor(canvas) {
        this.canvas = canvas;

        this.labelCanvas = document.createElement('canvas');
        this.labelCanvas.style.position = 'absolute';
        this.labelCanvas.style.top = '0';
        this.labelCanvas.style.left = '0';
        this.labelCanvas.style.pointerEvents = 'none';
        canvas.parentNode.insertBefore(this.labelCanvas, canvas.nextSibling);
        this.ctx = this.labelCanvas.getContext("2d");

        this.gl = canvas.getContext("webgl", {alpha: false, antialias: true}) ||
            canvas.getContext("experimental-webgl", {alpha: false, antialias: true});

        if (this.gl) {
            this.initGL();
        } else {
            console.error("WebGL not supported");
        }

        this.lastHovered = undefined;
    }

    compileShader(type, source) {
        const gl = this.gl;
        const shader = gl.createShader(type);
        gl.shaderSource(shader, source);
        gl.compileShader(shader);
        if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
            console.error(gl.getShaderInfoLog(shader));
            gl.deleteShader(shader);
            return null;
        }
        return shader;
    }

    createProgram(vsSource, fsSource) {
        const gl = this.gl;
        const vs = this.compileShader(gl.VERTEX_SHADER, vsSource);
        const fs = this.compileShader(gl.FRAGMENT_SHADER, fsSource);
        const program = gl.createProgram();
        gl.attachShader(program, vs);
        gl.attachShader(program, fs);
        gl.linkProgram(program);
        return program;
    }

    initGL() {
        const gl = this.gl;
        gl.enable(gl.BLEND);
        gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);

        this.nodeProgram = this.createProgram(VS_NODE, FS_NODE);
        this.edgeProgram = this.createProgram(VS_EDGE, FS_EDGE);

        this.nodeBuffer = gl.createBuffer();
        this.edgeBuffer = gl.createBuffer();

        this.nodeLoc = {
            pos: gl.getAttribLocation(this.nodeProgram, "a_position"),
            rad: gl.getAttribLocation(this.nodeProgram, "a_radius"),
            col: gl.getAttribLocation(this.nodeProgram, "a_color"),
            opac: gl.getAttribLocation(this.nodeProgram, "a_opacity"),
            res: gl.getUniformLocation(this.nodeProgram, "u_resolution"),
            pan: gl.getUniformLocation(this.nodeProgram, "u_pan"),
            zoom: gl.getUniformLocation(this.nodeProgram, "u_zoom"),
            pr: gl.getUniformLocation(this.nodeProgram, "u_pixelRatio")
        };

        this.edgeLoc = {
            pos: gl.getAttribLocation(this.edgeProgram, "a_position"),
            col: gl.getAttribLocation(this.edgeProgram, "a_color"),
            opac: gl.getAttribLocation(this.edgeProgram, "a_opacity"),
            t: gl.getAttribLocation(this.edgeProgram, "a_t"),
            anim: gl.getAttribLocation(this.edgeProgram, "a_isAnimated"),
            sourceId: gl.getAttribLocation(this.edgeProgram, "a_sourceId"),
            targetId: gl.getAttribLocation(this.edgeProgram, "a_targetId"),
            res: gl.getUniformLocation(this.edgeProgram, "u_resolution"),
            pan: gl.getUniformLocation(this.edgeProgram, "u_pan"),
            zoom: gl.getUniformLocation(this.edgeProgram, "u_zoom"),
            time: gl.getUniformLocation(this.edgeProgram, "u_time"),
            activeNodeId: gl.getUniformLocation(this.edgeProgram, "u_activeNodeId")
        };
    }

    initEdgeBuffer(edges) {
        const gl = this.gl;
        const edgeData = new Float32Array(edges.length * 2 * 10);
        let i = 0;

        const normalEdgeColor = hexToRgb(GRAPH_CONFIG.STROKE_COLOR_NORMAL);

        for (const edge of edges) {
            const sId = edge.source.id;
            const tId = edge.target.id;

            edgeData[i++] = edge.source.x;
            edgeData[i++] = edge.source.y;
            edgeData[i++] = normalEdgeColor[0];
            edgeData[i++] = normalEdgeColor[1];
            edgeData[i++] = normalEdgeColor[2];
            edgeData[i++] = 0.4;
            edgeData[i++] = 0.0;
            edgeData[i++] = 0.0;
            edgeData[i++] = sId;
            edgeData[i++] = tId;

            edgeData[i++] = edge.target.x;
            edgeData[i++] = edge.target.y;
            edgeData[i++] = normalEdgeColor[0];
            edgeData[i++] = normalEdgeColor[1];
            edgeData[i++] = normalEdgeColor[2];
            edgeData[i++] = 0.4;
            edgeData[i++] = 1.0;
            edgeData[i++] = 0.0;
            edgeData[i++] = sId;
            edgeData[i++] = tId;
        }

        gl.bindBuffer(gl.ARRAY_BUFFER, this.edgeBuffer);
        gl.bufferData(gl.ARRAY_BUFFER, edgeData, gl.STATIC_DRAW);
        this.edgeCount = edges.length * 2;
    }

    updateNodeBuffer(nodes, neighborMap, activeFocus) {
        const gl = this.gl;
        const nodeData = new Float32Array(nodes.length * 7);
        let i = 0;

        for (const node of nodes) {
            let opacity = GRAPH_CONFIG.HOVER_OPACITY_FULL;

            if (activeFocus) if (node === activeFocus) {
                opacity = GRAPH_CONFIG.HOVER_OPACITY_FULL;
            } else if (isNeighbor(node, activeFocus, neighborMap)) {
                opacity = GRAPH_CONFIG.HOVER_OPACITY_NEIGHBOR;
            } else {
                opacity = GRAPH_CONFIG.HOVER_OPACITY_OTHER;
            }

            const color = hexToRgb(getCategoryColor(node.category));

            nodeData[i++] = node.x;
            nodeData[i++] = node.y;
            nodeData[i++] = node.radius;
            nodeData[i++] = color[0];
            nodeData[i++] = color[1];
            nodeData[i++] = color[2];
            nodeData[i++] = opacity;
        }

        gl.bindBuffer(gl.ARRAY_BUFFER, this.nodeBuffer);
        gl.bufferData(gl.ARRAY_BUFFER, nodeData, gl.DYNAMIC_DRAW);
        this.nodeCount = nodes.length;
    }

    render(nodes, edges, neighborMap, viewState) {
        if (!this.gl) return;

        const {zoom, pan, hoveredNode, selectedNode} = viewState;
        const activeFocus = selectedNode || hoveredNode;
        const w = this.canvas.clientWidth;
        const h = this.canvas.clientHeight;
        const dpr = window.devicePixelRatio || 1;

        if (this.canvas.width !== w * dpr || this.canvas.height !== h * dpr) {
            this.canvas.width = w * dpr;
            this.canvas.height = h * dpr;
            this.labelCanvas.width = w * dpr;
            this.labelCanvas.height = h * dpr;
            this.labelCanvas.style.width = w + 'px';
            this.labelCanvas.style.height = h + 'px';
            this.gl.viewport(0, 0, this.canvas.width, this.canvas.height);
        }

        if (this.edgeCount === undefined) {
            this.initEdgeBuffer(edges);
            this.updateNodeBuffer(nodes, neighborMap, activeFocus);
        }

        if (this.lastHovered !== activeFocus) {
            this.updateNodeBuffer(nodes, neighborMap, activeFocus);
            this.lastHovered = activeFocus;
        }

        const gl = this.gl;
        gl.clearColor(0.04, 0.05, 0.06, 1.0);
        gl.clear(gl.COLOR_BUFFER_BIT);

        gl.useProgram(this.edgeProgram);
        gl.bindBuffer(gl.ARRAY_BUFFER, this.edgeBuffer);

        gl.enableVertexAttribArray(this.edgeLoc.pos);
        gl.vertexAttribPointer(this.edgeLoc.pos, 2, gl.FLOAT, false, 40, 0);
        gl.enableVertexAttribArray(this.edgeLoc.col);
        gl.vertexAttribPointer(this.edgeLoc.col, 3, gl.FLOAT, false, 40, 8);
        gl.enableVertexAttribArray(this.edgeLoc.opac);
        gl.vertexAttribPointer(this.edgeLoc.opac, 1, gl.FLOAT, false, 40, 20);
        gl.enableVertexAttribArray(this.edgeLoc.t);
        gl.vertexAttribPointer(this.edgeLoc.t, 1, gl.FLOAT, false, 40, 24);
        gl.enableVertexAttribArray(this.edgeLoc.anim);
        gl.vertexAttribPointer(this.edgeLoc.anim, 1, gl.FLOAT, false, 40, 28);
        gl.enableVertexAttribArray(this.edgeLoc.sourceId);
        gl.vertexAttribPointer(this.edgeLoc.sourceId, 1, gl.FLOAT, false, 40, 32);
        gl.enableVertexAttribArray(this.edgeLoc.targetId);
        gl.vertexAttribPointer(this.edgeLoc.targetId, 1, gl.FLOAT, false, 40, 36);

        const now = performance.now() / 1000;
        gl.uniform2f(this.edgeLoc.res, w, h);
        gl.uniform2f(this.edgeLoc.pan, pan.x, pan.y);
        gl.uniform1f(this.edgeLoc.zoom, zoom);
        gl.uniform1f(this.edgeLoc.time, now);

        const activeNodeId = activeFocus ? activeFocus.id : -1.0;
        gl.uniform1f(this.edgeLoc.activeNodeId, activeNodeId);

        gl.drawArrays(gl.LINES, 0, this.edgeCount);

        if (activeFocus && !this._rafPending) {
            this._rafPending = true;
            this._rafNodes = nodes;
            this._rafEdges = edges;
            this._rafNeighborMap = neighborMap;
            this._rafViewState = viewState;
            requestAnimationFrame(() => {
                this._rafPending = false;
                if (this._rafViewState && (this._rafViewState.hoveredNode || this._rafViewState.selectedNode)) {
                    this.render(this._rafNodes, this._rafEdges, this._rafNeighborMap, this._rafViewState);
                }
            });
        }

        gl.useProgram(this.nodeProgram);
        gl.bindBuffer(gl.ARRAY_BUFFER, this.nodeBuffer);
        gl.enableVertexAttribArray(this.nodeLoc.pos);
        gl.vertexAttribPointer(this.nodeLoc.pos, 2, gl.FLOAT, false, 28, 0);
        gl.enableVertexAttribArray(this.nodeLoc.rad);
        gl.vertexAttribPointer(this.nodeLoc.rad, 1, gl.FLOAT, false, 28, 8);
        gl.enableVertexAttribArray(this.nodeLoc.col);
        gl.vertexAttribPointer(this.nodeLoc.col, 3, gl.FLOAT, false, 28, 12);
        gl.enableVertexAttribArray(this.nodeLoc.opac);
        gl.vertexAttribPointer(this.nodeLoc.opac, 1, gl.FLOAT, false, 28, 24);

        gl.uniform2f(this.nodeLoc.res, w, h);
        gl.uniform2f(this.nodeLoc.pan, pan.x, pan.y);
        gl.uniform1f(this.nodeLoc.zoom, zoom);
        gl.uniform1f(this.nodeLoc.pr, dpr);

        gl.drawArrays(gl.POINTS, 0, this.nodeCount);

        this._drawLabels(nodes, neighborMap, viewState, w, h, dpr);
    }

    _drawLabels(nodes, neighborMap, viewState, w, h, dpr) {
        const {zoom, pan, hoveredNode, selectedNode} = viewState;
        const activeFocus = selectedNode || hoveredNode;
        this.ctx.clearRect(0, 0, this.labelCanvas.width, this.labelCanvas.height);
        this.ctx.save();
        this.ctx.scale(dpr, dpr);
        this.ctx.translate(pan.x, pan.y);
        this.ctx.scale(zoom, zoom);

        const minGraphX = -pan.x / zoom;
        const maxGraphX = (w - pan.x) / zoom;
        const minGraphY = -pan.y / zoom;
        const maxGraphY = (h - pan.y) / zoom;
        const pad = 100;

        const bounds = {
            minX: minGraphX - pad,
            maxX: maxGraphX + pad,
            minY: minGraphY - pad,
            maxY: maxGraphY + pad
        };

        const fontSize = Math.max(2, 12 / zoom);
        this.ctx.font = `bold ${fontSize}px system-ui, -apple-system, sans-serif`;
        this.ctx.textAlign = "center";
        this.ctx.textBaseline = "top";
        this.ctx.lineJoin = "round";

        const drawnBoxes = [];
        const offset = Math.max(1, 5 / zoom);
        const padX = 3 / zoom;
        const padY = 2 / zoom;

        const drawLabel = (node, opacity) => {
            const textWidth = this.ctx.measureText(node.name).width;
            const labelY = node.y + node.radius + offset;

            const box = {
                minX: node.x - textWidth / 2 - padX,
                maxX: node.x + textWidth / 2 + padX,
                minY: labelY - padY,
                maxY: labelY + fontSize + padY
            };

            let collides = false;
            for (let i = 0; i < drawnBoxes.length; i++) {
                const b = drawnBoxes[i];
                if (box.minX < b.maxX && box.maxX > b.minX &&
                    box.minY < b.maxY && box.maxY > b.minY) {
                    collides = true;
                    break;
                }
            }

            if (!collides) {
                drawnBoxes.push(box);

                this.ctx.fillStyle = `rgba(${GRAPH_CONFIG.LABEL_COLOR || '255, 255, 255'}, ${opacity})`;
                this.ctx.strokeStyle = `rgba(${GRAPH_CONFIG.LABEL_STROKE_COLOR || '0, 0, 0'}, ${opacity * 0.95})`;
                this.ctx.lineWidth = Math.max(0.5, 3 / zoom);

                this.ctx.strokeText(node.name, node.x, labelY);
                this.ctx.fillText(node.name, node.x, labelY);
            }
        };

        if (activeFocus) {
            drawLabel(activeFocus, GRAPH_CONFIG.HOVER_OPACITY_FULL || 1.0);
            for (const node of nodes) {
                if (node !== activeFocus && isNeighbor(node, activeFocus, neighborMap)) drawLabel(node, 0.85);
            }
        }

        if (zoom >= 2.0) {
            const opacity = Math.min(1.0, (zoom - 2.0) * 2.0);
            let labelsDrawn = 0;
            for (const node of nodes) {
                if (labelsDrawn > 150) break;
                if (activeFocus && (node === activeFocus || isNeighbor(node, activeFocus, neighborMap))) continue;

                const rLimit = node.radius + 50;
                if (node.x < bounds.minX - rLimit || node.x > bounds.maxX + rLimit ||
                    node.y < bounds.minY - rLimit || node.y > bounds.maxY + rLimit) continue;

                const textWidth = this.ctx.measureText(node.name).width;
                const labelY = node.y + node.radius + offset;

                const box = {
                    minX: node.x - textWidth / 2 - padX,
                    maxX: node.x + textWidth / 2 + padX,
                    minY: labelY - padY,
                    maxY: labelY + fontSize + padY
                };

                let collides = false;
                for (let i = 0; i < drawnBoxes.length; i++) {
                    const b = drawnBoxes[i];
                    if (box.minX < b.maxX && box.maxX > b.minX &&
                        box.minY < b.maxY && box.maxY > b.minY) {
                        collides = true;
                        break;
                    }
                }

                if (!collides) {
                    drawnBoxes.push(box);

                    this.ctx.fillStyle = `rgba(${GRAPH_CONFIG.LABEL_COLOR || '255, 255, 255'}, ${opacity})`;
                    this.ctx.strokeStyle = `rgba(${GRAPH_CONFIG.LABEL_STROKE_COLOR || '0, 0, 0'}, ${opacity * 0.95})`;
                    this.ctx.lineWidth = Math.max(0.5, 3 / zoom);

                    this.ctx.strokeText(node.name, node.x, labelY);
                    this.ctx.fillText(node.name, node.x, labelY);

                    labelsDrawn++;
                }
            }
        }

        this.ctx.restore();
    }

    destroy() {
        if (this.labelCanvas && this.labelCanvas.parentNode) this.labelCanvas.parentNode.removeChild(this.labelCanvas);
    }
}
