/*
 * Complexity Analyzer
 * Copyright (C) 2025-2026 dertex909
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

uniform vec2 u_resolution;
uniform vec2 u_pan;
uniform float u_zoom;

varying vec3 v_color;
varying float v_opacity;

void main() {
    vec2 pos = (a_position * u_zoom) + u_pan;
    vec2 clipSpace = (pos / u_resolution) * 2.0 - 1.0;
    gl_Position = vec4(clipSpace * vec2(1, -1), 0, 1);
    v_color = a_color;
    v_opacity = a_opacity;
}
`;

const FS_EDGE = `
precision mediump float;
varying vec3 v_color;
varying float v_opacity;

void main() {
    gl_FragColor = vec4(v_color, v_opacity);
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
            res: gl.getUniformLocation(this.edgeProgram, "u_resolution"),
            pan: gl.getUniformLocation(this.edgeProgram, "u_pan"),
            zoom: gl.getUniformLocation(this.edgeProgram, "u_zoom")
        };
    }

    updateBuffers(nodes, edges, neighborMap, hoveredNode) {
        const gl = this.gl;

        const edgeData = new Float32Array(edges.length * 2 * 6);
        let i = 0;

        const normalEdgeColor = hexToRgb(GRAPH_CONFIG.STROKE_COLOR_NORMAL);
        const hoverEdgeColor = hexToRgb(GRAPH_CONFIG.HOVER_EDGE_COLOR);

        for (const edge of edges) {
            let opacity = GRAPH_CONFIG.HOVER_OPACITY_OTHER;
            let color = normalEdgeColor;
            let isHovered = false;

            if (hoveredNode) {
                if (edge.source === hoveredNode || edge.target === hoveredNode) {
                    opacity = GRAPH_CONFIG.HOVER_OPACITY_NEIGHBOR;
                    color = hoverEdgeColor;
                    isHovered = true;
                } else {
                    opacity = 0.05;
                }
            } else {
                opacity = 0.4;
            }

            edgeData[i++] = edge.source.x;
            edgeData[i++] = edge.source.y;
            edgeData[i++] = color[0];
            edgeData[i++] = color[1];
            edgeData[i++] = color[2];
            edgeData[i++] = opacity;

            edgeData[i++] = edge.target.x;
            edgeData[i++] = edge.target.y;
            edgeData[i++] = color[0];
            edgeData[i++] = color[1];
            edgeData[i++] = color[2];
            edgeData[i++] = opacity;
        }

        gl.bindBuffer(gl.ARRAY_BUFFER, this.edgeBuffer);
        gl.bufferData(gl.ARRAY_BUFFER, edgeData, gl.DYNAMIC_DRAW);
        this.edgeCount = edges.length * 2;

        const nodeData = new Float32Array(nodes.length * 7);
        i = 0;

        for (const node of nodes) {
            let opacity = GRAPH_CONFIG.HOVER_OPACITY_FULL;

            if (hoveredNode) if (node === hoveredNode) {
                opacity = GRAPH_CONFIG.HOVER_OPACITY_FULL;
            } else if (isNeighbor(node, hoveredNode, neighborMap)) {
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

        const {zoom, pan, hoveredNode} = viewState;
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

        if (this.lastHovered !== hoveredNode) {
            this.updateBuffers(nodes, edges, neighborMap, hoveredNode);
            this.lastHovered = hoveredNode;
        } else if (this.nodeCount === undefined) {
            this.updateBuffers(nodes, edges, neighborMap, hoveredNode);
        }

        const gl = this.gl;
        gl.clearColor(0.04, 0.05, 0.06, 1.0);
        gl.clear(gl.COLOR_BUFFER_BIT);

        gl.useProgram(this.edgeProgram);
        gl.bindBuffer(gl.ARRAY_BUFFER, this.edgeBuffer);
        gl.enableVertexAttribArray(this.edgeLoc.pos);
        gl.vertexAttribPointer(this.edgeLoc.pos, 2, gl.FLOAT, false, 24, 0);
        gl.enableVertexAttribArray(this.edgeLoc.col);
        gl.vertexAttribPointer(this.edgeLoc.col, 3, gl.FLOAT, false, 24, 8);
        gl.enableVertexAttribArray(this.edgeLoc.opac);
        gl.vertexAttribPointer(this.edgeLoc.opac, 1, gl.FLOAT, false, 24, 20);

        gl.uniform2f(this.edgeLoc.res, w, h);
        gl.uniform2f(this.edgeLoc.pan, pan.x, pan.y);
        gl.uniform1f(this.edgeLoc.zoom, zoom);

        gl.drawArrays(gl.LINES, 0, this.edgeCount);

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
        const {zoom, pan, hoveredNode} = viewState;
        this.ctx.clearRect(0, 0, this.labelCanvas.width, this.labelCanvas.height);
        this.ctx.save();
        this.ctx.scale(dpr, dpr);
        this.ctx.translate(pan.x, pan.y);
        this.ctx.scale(zoom, zoom);

        const minGraphX = -pan.x / zoom;
        const maxGraphX = (w - pan.x) / zoom;
        const minGraphY = -pan.y / zoom;
        const maxGraphY = (h - pan.y) / zoom;
        const pad = GRAPH_CONFIG.EDGE_PAD;

        const bounds = {
            minX: minGraphX - pad,
            maxX: maxGraphX + pad,
            minY: minGraphY - pad,
            maxY: maxGraphY + pad
        };

        for (const node of nodes) {
            const rLimit = node.radius + GRAPH_CONFIG.LABEL_RENDER_BUFFER;

            if (node.x < bounds.minX - rLimit || node.x > bounds.maxX + rLimit ||
                node.y < bounds.minY - rLimit || node.y > bounds.maxY + rLimit) continue;

            const labelState = this._shouldDrawLabel(node, hoveredNode, neighborMap, zoom);
            if (labelState.shouldDraw) this._drawNodeLabel(node, labelState.opacity, zoom);
        }

        this.ctx.restore();
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
            } else if (zoom >= GRAPH_CONFIG.LABEL_HUB_ZOOM && node.degree >= GRAPH_CONFIG.LABEL_HUB_MIN_DEGREE) {
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

    destroy() {
        if (this.labelCanvas && this.labelCanvas.parentNode) this.labelCanvas.parentNode.removeChild(this.labelCanvas);
    }
}
