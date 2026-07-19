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
import {readRecipesAt} from "../core/cabin.js";

// Animation frame tracking
let activeAnimationId = null;

// Module cache for categories and colors
const categoryColors = new Map();
const DEFAULT_CAT_COLORS = {
    "Absolute": "#ffffff",
    "Trivial": "#b8c4d0",
    "Simple": "#5cd99a",
    "Moderate": "#f0d060",
    "Complex": "#f2943f",
    "Difficult": "#ee5a5a",
    "Expert": "#b178f0",
    "Master": "#50d0d8",
    "Mythical": "#ef8fc8",
    "Transcendent": "#3cc8be",
    "Unobtainable": "#7a2b2b",
    "Uncalculable": "#505964"
};

function getCatColor(cat) {
    if (!cat) return "#5bc0ff";
    if (categoryColors.has(cat)) return categoryColors.get(cat);

    const cssVarName = `--cat-${cat.replace(/\s+/g, "_")}`;
    let col = getComputedStyle(document.documentElement).getPropertyValue(cssVarName).trim();
    if (!col) col = getComputedStyle(document.documentElement).getPropertyValue(`--cat-${cat}`).trim();
    if (!col) col = DEFAULT_CAT_COLORS[cat] || "#5bc0ff";

    categoryColors.set(cat, col);
    return col;
}

// Helper to convert hex to RGB normalization [0, 1] for WebGL
function hexToRgb(hex) {
    const shorthandRegex = /^#?([a-f\d])([a-f\d])([a-f\d])$/i;
    const fullHex = hex.replace(shorthandRegex, (m, r, g, b) => r + r + g + g + b + b);
    const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(fullHex);
    return result ? [
        parseInt(result[1], 16) / 255,
        parseInt(result[2], 16) / 255,
        parseInt(result[3], 16) / 255
    ] : [0.35, 0.75, 1.0];
}

// --- SHADER SOURCE CODE ---
const NODE_VS = `#version 300 es
in vec2 a_position;
in vec3 a_color;
in float a_size;

uniform vec2 u_resolution;
uniform vec2 u_pan;
uniform float u_zoom;

out vec3 v_color;

void main() {
    vec2 screenPos = a_position * u_zoom + u_pan;
    vec2 clipPos = (screenPos / u_resolution) * vec2(2.0, -2.0) + vec2(-1.0, 1.0);
    gl_Position = vec4(clipPos, 0.0, 1.0);
    gl_PointSize = a_size * u_zoom;
    v_color = a_color;
}`;

const NODE_FS = `#version 300 es
precision mediump float;
in vec3 v_color;
out vec4 outColor;

void main() {
    float dist = length(gl_PointCoord - vec2(0.5));
    if (dist > 0.5) discard;
    // Antialiasing for smooth glowing points
    float alpha = smoothstep(0.5, 0.4, dist);
    outColor = vec4(v_color, alpha);
}`;

const EDGE_VS = `#version 300 es
in vec2 a_position;
in vec3 a_color;

uniform vec2 u_resolution;
uniform vec2 u_pan;
uniform float u_zoom;

out vec3 v_color;

void main() {
    vec2 screenPos = a_position * u_zoom + u_pan;
    vec2 clipPos = (screenPos / u_resolution) * vec2(2.0, -2.0) + vec2(-1.0, 1.0);
    gl_Position = vec4(clipPos, 0.0, 1.0);
    v_color = a_color;
}`;

const EDGE_FS = `#version 300 es
precision mediump float;
in vec3 v_color;
out vec4 outColor;

void main() {
    outColor = vec4(v_color, 0.35);
}`;

export async function renderGraph(container) {
    const db = state.db;
    if (!db) return;

    if (activeAnimationId) {
        cancelAnimationFrame(activeAnimationId);
        activeAnimationId = null;
    }
    if (window.caGraphCleanup) {
        window.caGraphCleanup();
    }

    const overlay = container.querySelector("#graph-overlay");
    const originalCanvas = container.querySelector("#graph-canvas");
    if (!originalCanvas) return;

    // Recreate canvas to clear listeners
    const canvas = originalCanvas.cloneNode(true);
    originalCanvas.parentNode.replaceChild(canvas, originalCanvas);

    // Create Text/UI overlay canvas
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
    const tCtx = textCanvas.getContext("2d");

    // Initialize WebGL2
    const gl = canvas.getContext("webgl2", {antialias: true, alpha: false});
    if (!gl) {
        console.error("WebGL2 is not supported on this browser.");
        return;
    }

    // Enable basic blending for transparency
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);

    // Compile Shader Helpers
    function createShader(gl, type, source) {
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

    function createProgram(gl, vsSource, fsSource) {
        const vs = createShader(gl, gl.VERTEX_SHADER, vsSource);
        const fs = createShader(gl, gl.FRAGMENT_SHADER, fsSource);
        const program = gl.createProgram();
        gl.attachShader(program, vs);
        gl.attachShader(program, fs);
        gl.linkProgram(program);
        if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
            console.error(gl.getProgramInfoLog(program));
            return null;
        }
        return program;
    }

    const nodeProgram = createProgram(gl, NODE_VS, NODE_FS);
    const edgeProgram = createProgram(gl, EDGE_VS, EDGE_FS);

    // Scan database items & recipes
    const itemsCount = db.items.count;
    const nodes = [];
    const nodeMap = new Map();
    const neighborMap = new Map();
    const edgeKeySet = new Set();
    const resolvedEdges = [];

    for (let i = 0; i < itemsCount; i++) {
        const item = db.items.get(i);
        if (!item) continue;
        const nNode = {
            id: i,
            name: item.name,
            category: item.categoryName || "Unknown",
            complexity: item.complexity,
            depth: item.depth || 0,
            flags: item.flags,
            degree: 0,
            radius: 8,
            x: 0, y: 0,
            targetX: 0, targetY: 0
        };
        nodes.push(nNode);
        nodeMap.set(i, nNode);
        neighborMap.set(i, new Set());
    }

    const addEdge = (idIng, idOut) => {
        if (idIng < 0 || idIng >= itemsCount || idOut < 0 || idOut >= itemsCount || idIng === idOut) return;
        const edgeK = `${idIng}->${idOut}`;
        if (!edgeKeySet.has(edgeK)) {
            edgeKeySet.add(edgeK);
            const nA = nodeMap.get(idIng);
            const nB = nodeMap.get(idOut);
            if (nA && nB) {
                nA.degree++;
                nB.degree++;
                resolvedEdges.push({source: nA, target: nB});
                neighborMap.get(idIng).add(idOut);
                neighborMap.get(idOut).add(idIng);
            }
        }
    };

    if (db._rB) {
        for (let i = 0; i < itemsCount; i++) {
            const ref = db.recipeIndex.get(i);
            if (ref && ref.offset !== 0xFFFFFFFF && ref.count > 0) {
                try {
                    const recipes = readRecipesAt(db._rB, db.strings, ref.offset, ref.count);
                    for (const recipe of recipes) {
                        const outInd = recipe.outputItemIndex;
                        if (outInd >= 0 && outInd < itemsCount) {
                            for (const ing of recipe.ingredients) {
                                for (const vi of ing.variants) {
                                    addEdge(vi, outInd);
                                }
                            }
                            for (const otherOut of recipe.itemOutputs) {
                                addEdge(outInd, otherOut.itemIndex);
                            }
                        }
                    }
                } catch (e) {
                    console.error("Error building recipe link indexes:", e);
                }
            }
        }
    }

    const activeNodes = nodes.filter(n => n.degree > 0);
    activeNodes.forEach(n => {
        n.radius = Math.max(7, Math.min(18, 5 + Math.sqrt(n.degree) * 1.5));
    });

    // Preferences
    let viewMode = localStorage.getItem("ca_graph_viewmode") || "focused";
    let layoutStyle = localStorage.getItem("ca_graph_layout") || "pipeline";
    let edgeStyle = localStorage.getItem("ca_graph_edges") || "orthogonal";
    let highlightMode = localStorage.getItem("ca_graph_highlight") || "chain";
    let flowSpeed = localStorage.getItem("ca_graph_flow") !== null ? parseFloat(localStorage.getItem("ca_graph_flow")) : 1.0;
    let selectedCategoryFilter = "All";

    // Camera state
    let zoom = 1.0;
    let pan = {x: 0, y: 0};
    let targetZoom = 1.0;
    let targetPan = {x: 0, y: 0};

    let hoveredNode = null;
    let selectedNodeId = state.selectedItem >= 0 ? state.selectedItem : -1;
    let searchHighlightNode = null;

    let isPanning = false;
    let lastMouse = {x: 0, y: 0};
    let startMouse = {x: 0, y: 0};
    let isDraggingMinimap = false;

    let needsRedraw = true;
    let visibleNodes = [];
    let focusedLevels = new Map();

    let highlightChain = {
        upstream: new Set(),
        downstream: new Set(),
        upstreamEdges: new Set(),
        downstreamEdges: new Set()
    };

    // GPU Buffers & VAOs
    const nodeVao = gl.createVertexArray();
    const nodeVbo = gl.createBuffer();
    let nodeVerticesCount = 0;

    const edgeVao = gl.createVertexArray();
    const edgeVbo = gl.createBuffer();
    let edgeVerticesCount = 0;

    function calculateFocusedSubset() {
        focusedLevels.clear();
        if (viewMode !== "focused") return;

        let rootId = selectedNodeId;
        if (rootId === -1) {
            if (state.selectedItem >= 0) {
                rootId = selectedNodeId = state.selectedItem;
            } else if (activeNodes.length > 0) {
                const maxNode = activeNodes.reduce((max, n) => n.degree > max.degree ? n : max, activeNodes[0]);
                rootId = selectedNodeId = maxNode.id;
            }
        }
        if (rootId === -1) return;

        const adjIngredients = new Map();
        const adjUsages = new Map();

        for (const edge of resolvedEdges) {
            const s = edge.source.id;
            const t = edge.target.id;
            if (!adjIngredients.has(t)) adjIngredients.set(t, []);
            adjIngredients.get(t).push(s);
            if (!adjUsages.has(s)) adjUsages.set(s, []);
            adjUsages.get(s).push(t);
        }

        focusedLevels.set(rootId, 0);

        const qUp = [rootId];
        const visitedUp = new Set([rootId]);
        while (qUp.length > 0) {
            const curr = qUp.shift();
            const currL = focusedLevels.get(curr);
            const ings = adjIngredients.get(curr) || [];
            for (const ing of ings) {
                const targetL = currL - 1;
                if (!focusedLevels.has(ing)) {
                    focusedLevels.set(ing, targetL);
                } else {
                    focusedLevels.set(ing, Math.min(focusedLevels.get(ing), targetL));
                }
                if (!visitedUp.has(ing)) {
                    visitedUp.add(ing);
                    qUp.push(ing);
                }
            }
        }

        const qDown = [rootId];
        const visitedDown = new Set([rootId]);
        while (qDown.length > 0) {
            const curr = qDown.shift();
            const currL = focusedLevels.get(curr);
            const outs = adjUsages.get(curr) || [];
            for (const out of outs) {
                const targetL = currL + 1;
                if (!focusedLevels.has(out)) {
                    focusedLevels.set(out, targetL);
                } else {
                    focusedLevels.set(out, Math.max(focusedLevels.get(out), targetL));
                }
                if (!visitedDown.has(out)) {
                    visitedDown.add(out);
                    qDown.push(out);
                }
            }
        }
    }

    function applyLayout() {
        if (activeNodes.length === 0) return;

        if (viewMode === "focused") {
            calculateFocusedSubset();
            const focusedNodes = activeNodes.filter(n => focusedLevels.has(n.id));
            if (focusedNodes.length === 0) return;

            const levelGroups = new Map();
            for (const n of focusedNodes) {
                const lvl = focusedLevels.get(n.id);
                if (!levelGroups.has(lvl)) levelGroups.set(lvl, []);
                levelGroups.get(lvl).push(n);
            }

            const colSpacing = 280;
            const rowSpacing = 84;

            for (const [lvl, grp] of levelGroups.entries()) {
                grp.sort((a, b) => a.category.localeCompare(b.category) || a.name.localeCompare(b.name));
                const count = grp.length;
                for (let i = 0; i < count; i++) {
                    const n = grp[i];
                    n.targetX = lvl * colSpacing;
                    n.targetY = (i - (count - 1) / 2) * rowSpacing;
                }
            }
        } else {
            if (layoutStyle === "pipeline") {
                const cols = new Map();
                for (const n of activeNodes) {
                    const d = n.depth;
                    if (!cols.has(d)) cols.set(d, []);
                    cols.get(d).push(n);
                }

                const colSpacing = 280;
                const rowSpacing = 72;
                const subColSpacing = 74;
                const nodesPerSubCol = 14;

                for (const [d, colNodes] of cols.entries()) {
                    colNodes.sort((a, b) => a.category.localeCompare(b.category) || a.name.localeCompare(b.name));
                    const N = colNodes.length;
                    const subColsCount = Math.ceil(N / nodesPerSubCol);

                    for (let i = 0; i < N; i++) {
                        const n = colNodes[i];
                        const subCol = i % subColsCount;
                        const row = Math.floor(i / subColsCount);
                        const numRows = Math.ceil(N / subColsCount);

                        n.targetX = d * colSpacing + subCol * subColSpacing;
                        n.targetY = (row - (numRows - 1) / 2) * rowSpacing;
                    }
                }
            } else if (layoutStyle === "radial") {
                const rings = new Map();
                for (const n of activeNodes) {
                    const d = n.depth;
                    if (!rings.has(d)) rings.set(d, []);
                    rings.get(d).push(n);
                }

                for (const [d, ringNodes] of rings.entries()) {
                    ringNodes.sort((a, b) => a.category.localeCompare(b.category) || a.name.localeCompare(b.name));
                    const N = ringNodes.length;
                    const radius = 180 + d * 190;
                    for (let i = 0; i < N; i++) {
                        const n = ringNodes[i];
                        const angle = (i / N) * 2 * Math.PI + (d * 0.18);
                        n.targetX = radius * Math.cos(angle);
                        n.targetY = radius * Math.sin(angle);
                    }
                }
            } else if (layoutStyle === "category") {
                const catGroups = new Map();
                for (const n of activeNodes) {
                    const cat = n.category;
                    if (!catGroups.has(cat)) catGroups.set(cat, []);
                    catGroups.get(cat).push(n);
                }

                const categories = Array.from(catGroups.keys()).sort();
                const numCats = categories.length;
                const gridCols = Math.ceil(Math.sqrt(numCats));
                const blockSpacingX = 650;
                const blockSpacingY = 550;

                categories.forEach((cat, catIdx) => {
                    const catNodes = catGroups.get(cat);
                    const numNodes = catNodes.length;
                    const colsCount = Math.ceil(Math.sqrt(numNodes));

                    const blockCol = catIdx % gridCols;
                    const blockRow = Math.floor(catIdx / gridCols);
                    const centerX = blockCol * blockSpacingX;
                    const centerY = blockRow * blockSpacingY;

                    catNodes.sort((a, b) => b.degree - a.degree || a.name.localeCompare(b.name));

                    const nodeSpacing = 76;
                    for (let i = 0; i < numNodes; i++) {
                        const n = catNodes[i];
                        const r = Math.floor(i / colsCount);
                        const c = i % colsCount;
                        const rowsCount = Math.ceil(numNodes / colsCount);

                        n.targetX = centerX + (c - (colsCount - 1) / 2) * nodeSpacing;
                        n.targetY = centerY + (r - (rowsCount - 1) / 2) * nodeSpacing;
                    }
                });
            }
        }

        // Instant snap for clean load, subsequent transitions will LERP
        activeNodes.forEach(n => {
            if (n.x === 0 && n.y === 0) {
                n.x = n.targetX;
                n.y = n.targetY;
            }
        });

        reuploadGpuGeometry();
        needsRedraw = true;
    }

    // --- GPU BUFFER UPLOADER ---
    function reuploadGpuGeometry() {
        const activeViewNodes = viewMode === "focused"
            ? activeNodes.filter(n => focusedLevels.has(n.id))
            : activeNodes;

        const activeViewEdges = viewMode === "focused"
            ? resolvedEdges.filter(e => focusedLevels.has(e.source.id) && focusedLevels.has(e.target.id))
            : resolvedEdges;

        // 1. Pack Node Data: x, y, r, g, b, radius
        const nodeData = [];
        for (const n of activeViewNodes) {
            const rgb = hexToRgb(getCatColor(n.category));
            nodeData.push(n.x, n.y, rgb[0], rgb[1], rgb[2], n.radius * 2);
        }

        gl.bindVertexArray(nodeVao);
        gl.bindBuffer(gl.ARRAY_BUFFER, nodeVbo);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(nodeData), gl.STATIC_DRAW);

        // Attributes: vec2 a_position, vec3 a_color, float a_size
        const stride = 6 * 4;
        gl.enableVertexAttribArray(0);
        gl.vertexAttribPointer(0, 2, gl.FLOAT, false, stride, 0);

        gl.enableVertexAttribArray(1);
        gl.vertexAttribPointer(1, 3, gl.FLOAT, false, stride, 2 * 4);

        gl.enableVertexAttribArray(2);
        gl.vertexAttribPointer(2, 1, gl.FLOAT, false, stride, 5 * 4);

        nodeVerticesCount = activeViewNodes.length;

        // 2. Pack Edge Data (Lines)
        const edgeData = [];
        const pushSegment = (x1, y1, x2, y2, rgb) => {
            edgeData.push(x1, y1, rgb[0], rgb[1], rgb[2]);
            edgeData.push(x2, y2, rgb[0], rgb[1], rgb[2]);
        };

        for (const edge of activeViewEdges) {
            const s = edge.source;
            const t = edge.target;
            const rgb = [0.27, 0.33, 0.41]; // Base grey edge

            if (edgeStyle === "orthogonal") {
                const midX = s.x + (t.x - s.x) * 0.5;
                pushSegment(s.x, s.y, midX, s.y, rgb);
                pushSegment(midX, s.y, midX, t.y, rgb);
                pushSegment(midX, t.y, t.x, t.y, rgb);
            } else if (edgeStyle === "curved") {
                // Segmented Bezier curve for GPU
                const cp = Math.abs(t.x - s.x) * 0.4;
                let prevX = s.x, prevY = s.y;
                const steps = 8;
                for (let k = 1; k <= steps; k++) {
                    const u = k / steps;
                    const omt = 1 - u;
                    const px = omt * omt * omt * s.x + 3 * omt * omt * u * (s.x + cp) + 3 * omt * u * u * (t.x - cp) + u * u * u * t.x;
                    const py = omt * omt * omt * s.y + 3 * omt * omt * u * s.y + 3 * omt * u * u * t.y + u * u * u * t.y;
                    pushSegment(prevX, prevY, px, py, rgb);
                    prevX = px;
                    prevY = py;
                }
            } else {
                pushSegment(s.x, s.y, t.x, t.y, rgb);
            }
        }

        gl.bindVertexArray(edgeVao);
        gl.bindBuffer(gl.ARRAY_BUFFER, edgeVbo);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(edgeData), gl.STATIC_DRAW);

        gl.enableVertexAttribArray(0);
        gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 5 * 4, 0);

        gl.enableVertexAttribArray(1);
        gl.vertexAttribPointer(1, 3, gl.FLOAT, false, 5 * 4, 2 * 4);

        edgeVerticesCount = edgeData.length / 5;
    }

    applyLayout();

    function getBounds() {
        const nodesToMeasure = viewMode === "focused"
            ? activeNodes.filter(n => focusedLevels.has(n.id))
            : activeNodes;

        if (nodesToMeasure.length === 0) {
            return {minX: -100, maxX: 100, minY: -100, maxY: 100, width: 200, height: 200};
        }

        let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
        for (const n of nodesToMeasure) {
            const x = n.targetX;
            const y = n.targetY;
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        const margin = 140;
        return {
            minX: minX - margin, maxX: maxX + margin,
            minY: minY - margin, maxY: maxY + margin,
            width: (maxX - minX) + margin * 2,
            height: (maxY - minY) + margin * 2
        };
    }

    function recenterGraph(smooth = true) {
        const b = getBounds();
        const w = canvas.clientWidth;
        const h = canvas.clientHeight;
        const pad = 64;

        const targetW = w - pad * 2;
        const targetH = h - pad * 2;
        const z = Math.max(0.04, Math.min(1.4, Math.min(targetW / b.width, targetH / b.height)));

        const px = w / 2 - (b.minX + b.width / 2) * z;
        const py = h / 2 - (b.minY + b.height / 2) * z;

        if (smooth) {
            targetZoom = z;
            targetPan.x = px;
            targetPan.y = py;
        } else {
            zoom = targetZoom = z;
            pan.x = targetPan.x = px;
            pan.y = targetPan.y = py;
        }
        needsRedraw = true;
    }

    function updateHighlights() {
        highlightChain.upstream.clear();
        highlightChain.downstream.clear();
        highlightChain.upstreamEdges.clear();
        highlightChain.downstreamEdges.clear();

        if (!hoveredNode) {
            needsRedraw = true;
            return;
        }

        const rootId = hoveredNode.id;

        if (highlightMode === "direct") {
            for (const edge of resolvedEdges) {
                if (edge.source.id === rootId) {
                    highlightChain.downstream.add(edge.target.id);
                    highlightChain.downstreamEdges.add(edge);
                } else if (edge.target.id === rootId) {
                    highlightChain.upstream.add(edge.source.id);
                    highlightChain.upstreamEdges.add(edge);
                }
            }
        } else {
            const outToIng = new Map();
            for (const edge of resolvedEdges) {
                const t = edge.target.id;
                if (!outToIng.has(t)) outToIng.set(t, []);
                outToIng.get(t).push(edge);
            }

            const qUp = [rootId];
            const visitedUp = new Set([rootId]);
            while (qUp.length > 0) {
                const curr = qUp.shift();
                const edges = outToIng.get(curr) || [];
                for (const e of edges) {
                    const ingId = e.source.id;
                    if (!visitedUp.has(ingId)) {
                        visitedUp.add(ingId);
                        highlightChain.upstream.add(ingId);
                        qUp.push(ingId);
                    }
                    highlightChain.upstreamEdges.add(e);
                }
            }

            const ingToOut = new Map();
            for (const edge of resolvedEdges) {
                const s = edge.source.id;
                if (!ingToOut.has(s)) ingToOut.set(s, []);
                ingToOut.get(s).push(edge);
            }

            const qDown = [rootId];
            const visitedDown = new Set([rootId]);
            while (qDown.length > 0) {
                const curr = qDown.shift();
                const edges = ingToOut.get(curr) || [];
                for (const e of edges) {
                    const outId = e.target.id;
                    if (!visitedDown.has(outId)) {
                        visitedDown.add(outId);
                        highlightChain.downstream.add(outId);
                        qDown.push(outId);
                    }
                    highlightChain.downstreamEdges.add(e);
                }
            }
        }
        needsRedraw = true;
    }

    function transformCoords(clientX, clientY) {
        const bounds = canvas.getBoundingClientRect();
        const mX = clientX - bounds.left;
        const mY = clientY - bounds.top;
        return {
            x: (mX - pan.x) / zoom,
            y: (mY - pan.y) / zoom
        };
    }

    function focusOnNode(nodeId) {
        const node = nodeMap.get(nodeId);
        if (!node) return;

        selectedNodeId = nodeId;
        searchHighlightNode = node;

        if (viewMode === "focused") {
            applyLayout();
            recenterGraph(true);
        } else {
            const w = canvas.clientWidth;
            const h = canvas.clientHeight;
            targetZoom = 1.1;
            targetPan.x = w / 2 - node.targetX * targetZoom;
            targetPan.y = h / 2 - node.targetY * targetZoom;
        }

        updateHoverDetails(node);
        needsRedraw = true;
    }

    recenterGraph(false);

    // --- RENDERING PIPELINE ---
    function draw() {
        const w = canvas.clientWidth;
        const h = canvas.clientHeight;
        const dpr = window.devicePixelRatio || 1;

        if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
            canvas.width = w * dpr;
            canvas.height = h * dpr;
            textCanvas.width = w * dpr;
            textCanvas.height = h * dpr;
        }

        gl.viewport(0, 0, canvas.width, canvas.height);

        // Clean Background Color
        gl.clearColor(0.047, 0.054, 0.07, 1.0);
        gl.clear(gl.COLOR_BUFFER_BIT);

        const res = [canvas.width / dpr, canvas.height / dpr];

        // 1. Draw Edges
        if (zoom >= 0.1) {
            gl.useProgram(edgeProgram);
            gl.uniform2f(gl.getUniformLocation(edgeProgram, "u_resolution"), res[0], res[1]);
            gl.uniform2f(gl.getUniformLocation(edgeProgram, "u_pan"), pan.x, pan.y);
            gl.uniform1f(gl.getUniformLocation(edgeProgram, "u_zoom"), zoom);

            gl.bindVertexArray(edgeVao);
            gl.drawArrays(gl.LINES, 0, edgeVerticesCount);
        }

        // 2. Draw Nodes
        gl.useProgram(nodeProgram);
        gl.uniform2f(gl.getUniformLocation(nodeProgram, "u_resolution"), res[0], res[1]);
        gl.uniform2f(gl.getUniformLocation(nodeProgram, "u_pan"), pan.x, pan.y);
        gl.uniform1f(gl.getUniformLocation(nodeProgram, "u_zoom"), zoom);

        gl.bindVertexArray(nodeVao);
        gl.drawArrays(gl.POINTS, 0, nodeVerticesCount);

        // 3. Draw Hybrid text overlay (only on-screen nodes)
        drawOverlayLabels(res[0], res[1]);
    }

    function drawOverlayLabels(viewW, viewH) {
        tCtx.clearRect(0, 0, textCanvas.width, textCanvas.height);
        tCtx.save();
        tCtx.scale(window.devicePixelRatio || 1, window.devicePixelRatio || 1);

        const minGraphX = -pan.x / zoom;
        const maxGraphX = (viewW - pan.x) / zoom;
        const minGraphY = -pan.y / zoom;
        const maxGraphY = (viewH - pan.y) / zoom;

        const activeViewNodes = viewMode === "focused"
            ? activeNodes.filter(n => focusedLevels.has(n.id))
            : activeNodes;

        // Filter and collect visible nodes to avoid overdraw / labels collision
        visibleNodes = activeViewNodes.filter(node => {
            const pad = node.radius + 15;
            return !(node.x < minGraphX - pad || node.x > maxGraphX + pad ||
                node.y < minGraphY - pad || node.y > maxGraphY + pad);
        });

        const labelBoxes = [];

        // Sort to prioritize labels of hovered or high-degree nodes
        const sortedLabelNodes = [...visibleNodes].sort((a, b) => {
            const scoreA = (hoveredNode && (a.id === hoveredNode.id || highlightChain.upstream.has(a.id) || highlightChain.downstream.has(a.id))) ? 1000 : a.degree;
            const scoreB = (hoveredNode && (b.id === hoveredNode.id || highlightChain.upstream.has(b.id) || highlightChain.downstream.has(b.id))) ? 1000 : b.degree;
            return scoreB - scoreA;
        });

        for (const node of sortedLabelNodes) {
            let showLabel = false;
            let opacity = 1.0;

            if (hoveredNode) {
                if (node.id === hoveredNode.id) {
                    showLabel = true;
                } else if (highlightChain.upstream.has(node.id) || highlightChain.downstream.has(node.id)) {
                    showLabel = true;
                    opacity = 0.85;
                }
            } else if (selectedCategoryFilter !== "All") {
                if (node.category === selectedCategoryFilter) {
                    showLabel = true;
                }
            } else {
                if (zoom >= 0.7) {
                    showLabel = true;
                    opacity = Math.min(1.0, (zoom - 0.6) * 5.0);
                } else if (zoom >= 0.42 && node.degree >= 6) {
                    showLabel = true;
                    opacity = Math.min(0.85, (zoom - 0.38) * 3.5);
                } else if (zoom >= 0.22 && node.degree >= 16) {
                    showLabel = true;
                    opacity = 0.55;
                }
            }

            if (showLabel) {
                // Project node spatial coordinates directly to flat screen coords
                const screenX = node.x * zoom + pan.x;
                const screenY = node.y * zoom + pan.y;

                tCtx.font = "bold 10px var(--sans), system-ui, sans-serif";
                const textWidth = tCtx.measureText(node.name).width;

                const labelW = textWidth + 12;
                const labelH = 15;
                const labelX = screenX - labelW / 2;
                const labelY = screenY + (node.radius * zoom) + 6;

                let hasCollision = false;
                for (const box of labelBoxes) {
                    if (labelX < box.maxX && labelX + labelW > box.minX &&
                        labelY < box.maxY && labelY + labelH > box.minY) {
                        hasCollision = true;
                        break;
                    }
                }

                const forceDisplay = hoveredNode && (node.id === hoveredNode.id || highlightChain.upstream.has(node.id) || highlightChain.downstream.has(node.id));

                if (!hasCollision || forceDisplay) {
                    labelBoxes.push({
                        minX: labelX, maxX: labelX + labelW,
                        minY: labelY, maxY: labelY + labelH
                    });

                    // Draw soft background capsule
                    tCtx.fillStyle = `rgba(13, 17, 23, ${opacity * 0.82})`;
                    tCtx.fillRect(labelX, labelY - 2, labelW, labelH);

                    // Text labels
                    tCtx.fillStyle = `rgba(235, 241, 248, ${opacity})`;
                    tCtx.textAlign = "center";
                    tCtx.textBaseline = "top";
                    tCtx.strokeStyle = `rgba(10, 12, 16, ${opacity * 0.95})`;
                    tCtx.lineWidth = 2.5;
                    tCtx.lineJoin = "round";
                    tCtx.strokeText(node.name, screenX, labelY);
                    tCtx.fillText(node.name, screenX, labelY);
                }
            }
        }
        tCtx.restore();
    }

    // --- ANIMATION LOOP ---
    function tick() {
        activeAnimationId = requestAnimationFrame(tick);
        let changed = false;

        // Camera LERP interpolation
        if (!isPanning && !isDraggingMinimap) {
            const panDx = targetPan.x - pan.x;
            const panDy = targetPan.y - pan.y;
            const zoomD = targetZoom - zoom;

            if (Math.abs(panDx) > 0.05 || Math.abs(panDy) > 0.05 || Math.abs(zoomD) > 0.001) {
                pan.x += panDx * 0.16;
                pan.y += panDy * 0.16;
                zoom += zoomD * 0.16;
                changed = true;
            } else {
                pan.x = targetPan.x;
                pan.y = targetPan.y;
                zoom = targetZoom;
            }
        }

        // Dynamic layout movement interpolation on GPU update
        let nodeGeomChanged = false;
        for (const n of activeNodes) {
            const dx = n.targetX - n.x;
            const dy = n.targetY - n.y;
            if (Math.abs(dx) > 0.05 || Math.abs(dy) > 0.05) {
                n.x += dx * 0.18;
                n.y += dy * 0.18;
                nodeGeomChanged = true;
                changed = true;
            } else {
                n.x = n.targetX;
                n.y = n.targetY;
            }
        }

        // Fast upload geometry update to GPU if nodes actually shifted
        if (nodeGeomChanged) {
            reuploadGpuGeometry();
        }

        if (changed || needsRedraw) {
            needsRedraw = false;
            draw();
        }
    }

    tick();

    // --- INTERACTIONS ---
    canvas.addEventListener("pointerdown", e => {
        const bounds = canvas.getBoundingClientRect();
        const mouseX = e.clientX - bounds.left;
        const mouseY = e.clientY - bounds.top;

        // Minimap HUD integration
        const minW = 200;
        const minH = 135;
        const minX = canvas.clientWidth - minW - 16;
        const minY = canvas.clientHeight - minH - 16;

        if (mouseX >= minX && mouseX <= minX + minW && mouseY >= minY && mouseY <= minY + minH) {
            isDraggingMinimap = true;
            canvas.setPointerCapture(e.pointerId);
            handleMinimapDrag(mouseX - minX, mouseY - minY);
            return;
        }

        canvas.setPointerCapture(e.pointerId);
        lastMouse = {x: e.clientX, y: e.clientY};
        startMouse = {x: e.clientX, y: e.clientY};
        isPanning = true;
    });

    canvas.addEventListener("pointermove", e => {
        const bounds = canvas.getBoundingClientRect();

        if (isDraggingMinimap) {
            const minW = 200;
            const minH = 135;
            const mx = canvas.clientWidth - minW - 16;
            const my = canvas.clientHeight - minH - 16;
            handleMinimapDrag(e.clientX - bounds.left - mx, e.clientY - bounds.top - my);
            return;
        }

        const currCoords = transformCoords(e.clientX, e.clientY);

        if (!isPanning) {
            let found = null;
            let bestDistSq = (24 / zoom) * (24 / zoom);

            const nodesToCheck = viewMode === "focused"
                ? activeNodes.filter(n => focusedLevels.has(n.id))
                : activeNodes;

            for (const n of nodesToCheck) {
                const dx = n.x - currCoords.x;
                const dy = n.y - currCoords.y;
                const distSq = dx * dx + dy * dy;
                const limit = Math.max(n.radius + 12, 24 / zoom);
                if (distSq < limit * limit && distSq < bestDistSq) {
                    found = n;
                    bestDistSq = distSq;
                }
            }

            if (hoveredNode !== found) {
                hoveredNode = found;
                updateHighlights();
                updateHoverDetails(found);
            }
        }

        if (isPanning) {
            const dx = e.clientX - lastMouse.x;
            const dy = e.clientY - lastMouse.y;
            pan.x += dx;
            pan.y += dy;
            targetPan.x = pan.x;
            targetPan.y = pan.y;
            needsRedraw = true;
        }

        lastMouse = {x: e.clientX, y: e.clientY};
    });

    canvas.addEventListener("pointerup", e => {
        try {
            canvas.releasePointerCapture(e.pointerId);
        } catch (_) {
        }
        isPanning = false;
        isDraggingMinimap = false;

        const moveDist = Math.hypot(e.clientX - startMouse.x, e.clientY - startMouse.y);
        if (moveDist < 5) {
            if (hoveredNode) {
                selectItem(hoveredNode.id);
            } else {
                selectedNodeId = -1;
                needsRedraw = true;
            }
        }
    });

    canvas.addEventListener("wheel", e => {
        e.preventDefault();
        const pivot = transformCoords(e.clientX, e.clientY);
        const zoomFactor = e.deltaY > 0 ? 0.85 : 1.18;

        targetZoom = Math.max(0.04, Math.min(5, targetZoom * zoomFactor));
        targetPan.x = e.clientX - canvas.getBoundingClientRect().left - pivot.x * targetZoom;
        targetPan.y = e.clientY - canvas.getBoundingClientRect().top - pivot.y * targetZoom;
        needsRedraw = true;
    }, {passive: false});

    function handleMinimapDrag(mx, my) {
        const minW = 200;
        const minH = 135;
        const gb = getBounds();
        const mapScale = Math.min((minW - 16) / gb.width, (minH - 16) / gb.height);
        const mapOffsetX = 8 + (minW - 16 - gb.width * mapScale) / 2;
        const mapOffsetY = 8 + (minH - 16 - gb.height * mapScale) / 2;

        const graphX = gb.minX + (mx - mapOffsetX) / mapScale;
        const graphY = gb.minY + (my - mapOffsetY) / mapScale;

        targetPan.x = pan.x = canvas.clientWidth / 2 - graphX * zoom;
        targetPan.y = pan.y = canvas.clientHeight / 2 - graphY * zoom;
        needsRedraw = true;
    }

    function updateHoverDetails(node) {
        const detailSection = overlay.querySelector("#graph-hud-hover-info");
        if (!detailSection) return;

        if (!node) {
            detailSection.innerHTML = `
                <div style="font-size: 11px; color: var(--text-muted); text-align: center; padding: 12px 0;">
                    Hover item to see recipe connection chains
                </div>
            `;
            return;
        }

        const catColor = getCatColor(node.category);
        detailSection.innerHTML = `
            <div style="display: flex; flex-direction: column; gap: 6px;">
                <div style="display: flex; align-items: center; gap: 8px;">
                    <div style="width: 8px; height: 8px; border-radius: 50%; background: ${catColor}; box-shadow: 0 0 6px ${catColor};"></div>
                    <strong style="color: var(--text); font-size: 13px; font-weight: 600;">${node.name}</strong>
                </div>
                <div style="font-size: 10px; color: var(--text-dim); display: grid; grid-template-columns: 80px 1fr; gap: 4px;">
                    <span>Category:</span><span style="color: ${catColor}; font-weight: 500;">${node.category}</span>
                    <span>Craft Depth:</span><span style="color: var(--text); font-family: var(--mono);">${node.depth}</span>
                    <span>Complexity:</span><span style="color: var(--accent); font-family: var(--mono); font-weight: 600;">${node.complexity === -1 ? "Infinite" : node.complexity}</span>
                    <span>Total Links:</span><span style="color: var(--text); font-family: var(--mono);">${node.degree}</span>
                </div>
            </div>
        `;
    }

    // --- OVERLAY HUD CREATION ---
    if (overlay) {
        overlay.innerHTML = `
            <!-- Search HUD -->
            <div id="graph-search-hud" style="
                position: absolute;
                top: 16px;
                left: 16px;
                pointer-events: auto;
                width: 280px;
                display: flex;
                flex-direction: column;
                gap: 4px;
                z-index: 10;
            ">
                <input type="search" id="graph-search-box" placeholder="Search item in graph..." style="
                    width: 100%;
                    box-sizing: border-box;
                    background: rgba(17, 21, 28, 0.88);
                    border: 1px solid var(--border);
                    padding: 8px 12px;
                    font-size: 11.5px;
                    color: var(--text);
                    backdrop-filter: blur(8px);
                ">
                <div id="graph-search-dropdown" style="
                    display: none;
                    max-height: 200px;
                    overflow-y: auto;
                    background: rgba(17, 21, 28, 0.95);
                    border: 1px solid var(--border);
                    backdrop-filter: blur(8px);
                    flex-direction: column;
                    box-shadow: var(--shadow-lg);
                "></div>
            </div>

            <!-- Configuration Sidepanel -->
            <div class="card" style="
                position: absolute;
                top: 16px;
                right: 16px;
                bottom: 16px;
                pointer-events: auto;
                padding: 16px;
                width: 280px;
                background: rgba(17, 21, 28, 0.88);
                border: 1px solid var(--border);
                box-shadow: var(--shadow-lg);
                font-family: var(--sans), sans-serif;
                display: flex;
                flex-direction: column;
                gap: 12px;
                backdrop-filter: blur(8px);
                z-index: 10;
            ">
                <div style="border-bottom: 1px solid var(--border-light); padding-bottom: 8px;">
                    <h3 style="margin: 0; color: var(--accent); font-size: 14px; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase;">Production Web Graph</h3>
                    <div style="font-size: 10.5px; color: var(--text-dim); margin-top: 4px;">Dynamic GPU WebGL relation map</div>
                </div>

                <div style="display: flex; justify-content: space-between; font-size: 11px; background: rgba(0,0,0,0.18); padding: 8px 10px; border: 1px solid rgba(255,255,255,0.03);">
                    <div>Nodes: <strong id="hud-nodes-count" style="color: var(--text);">${activeNodes.length}</strong></div>
                    <div>Links: <strong id="hud-links-count" style="color: var(--text);">${resolvedEdges.length}</strong></div>
                </div>

                <div style="border: 1px solid var(--border); background: rgba(10,12,16,0.4); padding: 10px; min-height: 72px;">
                    <div style="font-size: 9.5px; color: var(--text-muted); font-weight: 600; text-transform: uppercase; margin-bottom: 4px; letter-spacing: 0.05em;">Active Node Inspector</div>
                    <div id="graph-hud-hover-info">
                        <div style="font-size: 11px; color: var(--text-muted); text-align: center; padding: 12px 0;">
                            Hover item to see recipe connection chains
                        </div>
                    </div>
                </div>

                <div style="display: flex; flex-direction: column; gap: 8px; flex: 1; overflow-y: auto; padding-right: 2px;">
                    
                    <div style="display: flex; flex-direction: column; gap: 3px;">
                        <span style="font-size: 10px; color: var(--text-dim); text-transform: uppercase; font-weight: 600; letter-spacing: 0.05em;">View Mode:</span>
                        <select id="graph-opt-viewmode" style="width: 100%; font-size: 11px; background: var(--bg-raised); border: 1px solid var(--border); color: var(--text); padding: 5px;">
                            <option value="focused" ${viewMode === "focused" ? "selected" : ""}>Focused Craft Chain</option>
                            <option value="global" ${viewMode === "global" ? "selected" : ""}>Full Network Graph</option>
                        </select>
                    </div>

                    <div id="layout-select-container" style="display: ${viewMode === "global" ? "flex" : "none"}; flex-direction: column; gap: 3px;">
                        <span style="font-size: 10px; color: var(--text-dim); text-transform: uppercase; font-weight: 600; letter-spacing: 0.05em;">Graph Layout:</span>
                        <select id="graph-opt-layout" style="width: 100%; font-size: 11px; background: var(--bg-raised); border: 1px solid var(--border); color: var(--text); padding: 5px;">
                            <option value="pipeline" ${layoutStyle === "pipeline" ? "selected" : ""}>Pipeline Flow (Horizontal)</option>
                            <option value="radial" ${layoutStyle === "radial" ? "selected" : ""}>Radial Web (Concentric)</option>
                            <option value="category" ${layoutStyle === "category" ? "selected" : ""}>Category Clusters (Islands)</option>
                        </select>
                    </div>

                    <div style="display: flex; flex-direction: column; gap: 3px;">
                        <span style="font-size: 10px; color: var(--text-dim); text-transform: uppercase; font-weight: 600; letter-spacing: 0.05em;">Connection Lines:</span>
                        <select id="graph-opt-edges" style="width: 100%; font-size: 11px; background: var(--bg-raised); border: 1px solid var(--border); color: var(--text); padding: 5px;">
                            <option value="orthogonal" ${edgeStyle === "orthogonal" ? "selected" : ""}>Orthogonal Blueprint</option>
                            <option value="curved" ${edgeStyle === "curved" ? "selected" : ""}>Flowing Bezier</option>
                            <option value="straight" ${edgeStyle === "straight" ? "selected" : ""}>Straight Vector</option>
                        </select>
                    </div>

                    <div style="display: flex; flex-direction: column; gap: 3px;">
                        <span style="font-size: 10px; color: var(--text-dim); text-transform: uppercase; font-weight: 600; letter-spacing: 0.05em;">Hover Highlight Mode:</span>
                        <select id="graph-opt-highlight" style="width: 100%; font-size: 11px; background: var(--bg-raised); border: 1px solid var(--border); color: var(--text); padding: 5px;">
                            <option value="chain" ${highlightMode === "chain" ? "selected" : ""}>Full Craft Chain (Recursive)</option>
                            <option value="direct" ${highlightMode === "direct" ? "selected" : ""}>Direct Neighbors Only</option>
                        </select>
                    </div>

                    <div style="display: flex; flex-direction: column; gap: 3px;">
                        <span style="font-size: 10px; color: var(--text-dim); text-transform: uppercase; font-weight: 600; letter-spacing: 0.05em;">Highlight Category:</span>
                        <select id="graph-opt-catfilter" style="width: 100%; font-size: 11px; background: var(--bg-raised); border: 1px solid var(--border); color: var(--text); padding: 5px;">
                            <option value="All" ${selectedCategoryFilter === "All" ? "selected" : ""}>Show All Categories</option>
                            ${Object.keys(DEFAULT_CAT_COLORS).map(cat => `
                                <option value="${cat}" ${selectedCategoryFilter === cat ? "selected" : ""}>${cat}</option>
                            `).join("")}
                        </select>
                    </div>

                </div>

                <button id="recenter-graph-btn" style="
                    background: var(--bg-hover);
                    border: 1px solid var(--border);
                    color: var(--text);
                    padding: 8px;
                    cursor: pointer;
                    font-size: 11px;
                    text-align: center;
                    width: 100%;
                    transition: background var(--transition-fast);
                    font-weight: 600;
                    text-transform: uppercase;
                    letter-spacing: 0.02em;
                    flex-shrink: 0;
                ">Recenter Viewport</button>
            </div>
        `;

        const btn = overlay.querySelector("#recenter-graph-btn");
        if (btn) {
            btn.addEventListener("click", () => recenterGraph(true));
            btn.addEventListener("pointerdown", e => e.stopPropagation());
        }

        const viewModeSelector = overlay.querySelector("#graph-opt-viewmode");
        const layoutSelectContainer = overlay.querySelector("#layout-select-container");
        const hudNodesCount = overlay.querySelector("#hud-nodes-count");
        const hudLinksCount = overlay.querySelector("#hud-links-count");

        function updateStatsDisplay() {
            if (hudNodesCount && hudLinksCount) {
                const nodesCount = viewMode === "focused" ? activeNodes.filter(n => focusedLevels.has(n.id)).length : activeNodes.length;
                const linksCount = viewMode === "focused" ? resolvedEdges.filter(e => focusedLevels.has(e.source.id) && focusedLevels.has(e.target.id)).length : resolvedEdges.length;
                hudNodesCount.textContent = nodesCount;
                hudLinksCount.textContent = linksCount;
            }
        }

        if (viewModeSelector) {
            viewModeSelector.addEventListener("change", e => {
                viewMode = e.target.value;
                localStorage.setItem("ca_graph_viewmode", viewMode);
                if (layoutSelectContainer) {
                    layoutSelectContainer.style.display = viewMode === "global" ? "flex" : "none";
                }
                applyLayout();
                updateStatsDisplay();
                recenterGraph(true);
            });
            viewModeSelector.addEventListener("pointerdown", e => e.stopPropagation());
        }

        const layoutSelector = overlay.querySelector("#graph-opt-layout");
        if (layoutSelector) {
            layoutSelector.addEventListener("change", e => {
                layoutStyle = e.target.value;
                localStorage.setItem("ca_graph_layout", layoutStyle);
                applyLayout();
                recenterGraph(true);
            });
            layoutSelector.addEventListener("pointerdown", e => e.stopPropagation());
        }

        const edgeSelector = overlay.querySelector("#graph-opt-edges");
        if (edgeSelector) {
            edgeSelector.addEventListener("change", e => {
                edgeStyle = e.target.value;
                localStorage.setItem("ca_graph_edges", edgeStyle);
                reuploadGpuGeometry();
                needsRedraw = true;
            });
            edgeSelector.addEventListener("pointerdown", e => e.stopPropagation());
        }

        const highlightSelector = overlay.querySelector("#graph-opt-highlight");
        if (highlightSelector) {
            highlightSelector.addEventListener("change", e => {
                highlightMode = e.target.value;
                localStorage.setItem("ca_graph_highlight", highlightMode);
                updateHighlights();
            });
            highlightSelector.addEventListener("pointerdown", e => e.stopPropagation());
        }

        const catFilterSelector = overlay.querySelector("#graph-opt-catfilter");
        if (catFilterSelector) {
            catFilterSelector.addEventListener("change", e => {
                selectedCategoryFilter = e.target.value;
                needsRedraw = true;
            });
            catFilterSelector.addEventListener("pointerdown", e => e.stopPropagation());
        }

        // BIND SEARCH HUD
        const searchBox = overlay.querySelector("#graph-search-box");
        const searchDropdown = overlay.querySelector("#graph-search-dropdown");

        if (searchBox && searchDropdown) {
            searchBox.addEventListener("input", () => {
                const q = searchBox.value.trim().toLowerCase();
                if (q.length < 2) {
                    searchDropdown.style.display = "none";
                    searchDropdown.innerHTML = "";
                    return;
                }

                const matches = activeNodes.filter(n => n.name.toLowerCase().includes(q) || n.id.toString().includes(q)).slice(0, 15);

                if (matches.length > 0) {
                    searchDropdown.style.display = "flex";
                    searchDropdown.innerHTML = matches.map(m => `
                        <div class="search-item" data-id="${m.id}" style="
                            padding: 6px 10px;
                            cursor: pointer;
                            border-bottom: 1px solid rgba(255, 255, 255, 0.03);
                            display: flex;
                            align-items: center;
                            justify-content: space-between;
                            font-size: 11px;
                        ">
                            <span style="font-weight: 500; color: var(--text);">${m.name}</span>
                            <span style="font-size: 9.5px; color: ${getCatColor(m.category)}; font-weight: 600; text-transform: uppercase;">${m.category}</span>
                        </div>
                    `).join("");
                } else {
                    searchDropdown.style.display = "flex";
                    searchDropdown.innerHTML = `
                        <div style="padding: 8px; font-size: 11px; color: var(--text-muted); text-align: center;">No matches in graph</div>
                    `;
                }
            });

            searchDropdown.addEventListener("click", e => {
                const item = e.target.closest(".search-item");
                if (!item) return;

                const nodeId = parseInt(item.dataset.id, 10);
                focusOnNode(nodeId);
                updateStatsDisplay();

                searchDropdown.style.display = "none";
                searchBox.value = "";
            });

            searchBox.addEventListener("pointerdown", e => e.stopPropagation());
            searchDropdown.addEventListener("pointerdown", e => e.stopPropagation());

            window.addEventListener("click", ev => {
                if (!ev.target.closest("#graph-search-hud")) {
                    searchDropdown.style.display = "none";
                }
            });
        }

        updateStatsDisplay();
    }

    // Connect to state changes
    const onSelectChange = () => {
        if (state.selectedItem >= 0 && state.selectedItem !== selectedNodeId) {
            focusOnNode(state.selectedItem);
            const overlayContainer = container.querySelector("#graph-overlay");
            if (overlayContainer) {
                const nodesCount = viewMode === "focused" ? activeNodes.filter(n => focusedLevels.has(n.id)).length : activeNodes.length;
                const linksCount = viewMode === "focused" ? resolvedEdges.filter(e => focusedLevels.has(e.source.id) && focusedLevels.has(e.target.id)).length : resolvedEdges.length;
                const hudNodes = overlayContainer.querySelector("#hud-nodes-count");
                const hudLinks = overlayContainer.querySelector("#hud-links-count");
                if (hudNodes) hudNodes.textContent = nodesCount;
                if (hudLinks) hudLinks.textContent = linksCount;
            }
        }
    };
    store.addEventListener("selectItem", onSelectChange);

    // Context clean-ups
    window.caGraphCleanup = () => {
        store.removeEventListener("selectItem", onSelectChange);
        gl.deleteVertexArray(nodeVao);
        gl.deleteBuffer(nodeVbo);
        gl.deleteVertexArray(edgeVao);
        gl.deleteBuffer(edgeVbo);
        gl.deleteProgram(nodeProgram);
        gl.deleteProgram(edgeProgram);
    };

    if (selectedNodeId >= 0) {
        focusOnNode(selectedNodeId);
        recenterGraph(false);
    }
}