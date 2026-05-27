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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import {state, selectItem} from "../core/state.js";
import {readRecipesAt} from "../core/cabin.js";
import * as d3 from "../core/libs/d3.js";

let activeSim = null;
let activeResizeListener = null;
let cachedHash = null;
let cachedActiveNodes = null;
let cachedResolvedEdges = null;
let cachedNeighborMap = null;

const LAYOUT_VERSION = "v5_perfect_spread";
const categoryColors = new Map();

export async function renderGraph(container) {

    const db = state.db;
    if (!db) return;

    const overlay = container.querySelector("#graph-overlay");
    if (overlay) {
        overlay.innerHTML = `
            <div id="graph-loader" style="
                position: absolute;
                inset: 0;
                display: flex;
                flex-direction: column;
                justify-content: center;
                align-items: center;
                background: rgba(10, 12, 16, 0.95);
                z-index: 100;
                color: var(--text-dim);
                gap: 12px;
                pointer-events: auto;
            ">
                <div style="
                    width: 32px;
                    height: 32px;
                    border: 3px solid var(--border-light);
                    border-top-color: var(--accent);
                    border-radius: 50%;
                    animation: spin 0.8s linear infinite;
                "></div>
                <div style="font-weight: 500; color: var(--text);">Generating All-Items Graph…</div>
                <div style="font-size: 11px;">Scanning recipe connections...</div>
            </div>
            <style>
                @keyframes spin {
                    to { transform: rotate(360deg); }
                }
            </style>
        `;
    }

    const canvas = container.querySelector("#graph-canvas");
    if (!canvas) return;

    if (activeSim) {
        activeSim.stop();
        activeSim = null;
    }
    if (activeResizeListener) {
        window.removeEventListener("resize", activeResizeListener);
        activeResizeListener = null;
    }

    const newCanvas = canvas.cloneNode(true);
    canvas.parentNode.replaceChild(newCanvas, canvas);
    const mCanvas = newCanvas;
    const mCtx = mCanvas.getContext("2d");

    const currentHash = db.file.fileHash.toString();
    const cacheKey = currentHash + "_" + LAYOUT_VERSION;

    let activeNodes = [];
    let resolvedEdges = [];
    let neighborMap = new Map();
    let isLayoutCached = false;

    let zoom = 1.0;
    let pan = {x: 0, y: 0};
    let hoveredNode = null;

    let isPanning = false;
    let lastMouse = {x: 0, y: 0};
    let startMouse = {x: 0, y: 0};
    let lastHoverCheck = 0;

    function transformCoords(clientX, clientY) {
        const bounds = mCanvas.getBoundingClientRect();
        const mX = clientX - bounds.left;
        const mY = clientY - bounds.top;
        return {
            x: (mX - pan.x) / zoom,
            y: (mY - pan.y) / zoom
        };
    }

    function isNeighbor(n1, n2) {
        if (!n1 || !n2) return false;
        const neighbors = neighborMap.get(n1.id);
        return neighbors && neighbors.has(n2.id);
    }

    function getCatColor(cat) {
        if (!cat) return "#5bc0ff";
        if (categoryColors.has(cat)) return categoryColors.get(cat);
        const cssVarName = `--cat-${cat.replace(/\s+/g, "_")}`;
        let col = getComputedStyle(document.documentElement).getPropertyValue(cssVarName).trim();
        if (!col) col = getComputedStyle(document.documentElement).getPropertyValue(`--cat-${cat}`).trim();
        if (!col) col = "#5bc0ff";
        categoryColors.set(cat, col);
        return col;
    }

    function draw() {
        const w = mCanvas.clientWidth;
        const h = mCanvas.clientHeight;
        const dpr = window.devicePixelRatio || 1;

        if (mCanvas.width !== w * dpr || mCanvas.height !== h * dpr) {
            mCanvas.width = w * dpr;
            mCanvas.height = h * dpr;
        }

        mCtx.save();
        mCtx.clearRect(0, 0, mCanvas.width, mCanvas.height);
        mCtx.scale(dpr, dpr);

        mCtx.translate(pan.x, pan.y);
        mCtx.scale(zoom, zoom);

        const minGraphX = -pan.x / zoom;
        const maxGraphX = (w - pan.x) / zoom;
        const minGraphY = -pan.y / zoom;
        const maxGraphY = (h - pan.y) / zoom;

        const padEdge = 20;
        const edgeMinX = minGraphX - padEdge;
        const edgeMaxX = maxGraphX + padEdge;
        const edgeMinY = minGraphY - padEdge;
        const edgeMaxY = maxGraphY + padEdge;

        if (hoveredNode) {
            mCtx.strokeStyle = "#5bc0ff";
            mCtx.globalAlpha = 0.95;
            mCtx.lineWidth = 2.5 / zoom;
            mCtx.beginPath();
            for (const edge of resolvedEdges) {
                const hA = edge.source;
                const hB = edge.target;
                if (hA === hoveredNode || hB === hoveredNode) {
                    const segMinX = hA.x < hB.x ? hA.x : hB.x;
                    const segMaxX = hA.x > hB.x ? hA.x : hB.x;
                    const segMinY = hA.y < hB.y ? hA.y : hB.y;
                    const segMaxY = hA.y > hB.y ? hA.y : hB.y;
                    if (segMaxX < edgeMinX || segMinX > edgeMaxX || segMaxY < edgeMinY || segMinY > edgeMaxY) {
                        continue;
                    }

                    mCtx.moveTo(hA.x, hA.y);
                    mCtx.lineTo(hB.x, hB.y);
                }
            }
            mCtx.stroke();
        }
        mCtx.globalAlpha = 1.0;

        const drawStroke = zoom >= 0.2;

        for (const node of activeNodes) {
            const rDraw = Math.max(node.radius, 2.0 / zoom);
            const rLimit = rDraw + 10;

            if (node.x < minGraphX - rLimit || node.x > maxGraphX + rLimit ||
                node.y < minGraphY - rLimit || node.y > maxGraphY + rLimit) {
                continue;
            }

            let opacity = 1.0;
            let strokeColor = "#0a0c10";
            let strokeWidth = 1.2 / zoom;

            if (hoveredNode) if (node === hoveredNode) {
                opacity = 1.0;
                strokeColor = "#ffffff";
                strokeWidth = 3.0 / zoom;
            } else if (isNeighbor(node, hoveredNode)) {
                opacity = 0.95;
                strokeColor = "rgba(255, 255, 255, 0.75)";
                strokeWidth = 2.0 / zoom;
            } else {
                opacity = 0.22;
            }

            mCtx.globalAlpha = opacity;
            mCtx.fillStyle = getCatColor(node.category);

            mCtx.strokeStyle = strokeColor;
            mCtx.lineWidth = strokeWidth;
            mCtx.beginPath();
            mCtx.arc(node.x, node.y, rDraw, 0, 2 * Math.PI);
            mCtx.fill();
            if (drawStroke) mCtx.stroke();
        }
        mCtx.globalAlpha = 1.0;

        for (const node of activeNodes) {
            const rLimit = node.radius + 150;
            if (node.x < minGraphX - rLimit || node.x > maxGraphX + rLimit ||
                node.y < minGraphY - rLimit || node.y > maxGraphY + rLimit) {
                continue;
            }

            let matchesLabel = false;
            let opacity = 1.0;

            if (hoveredNode) {
                if (node === hoveredNode) {
                    matchesLabel = true;
                    opacity = 1.0;
                } else if (isNeighbor(node, hoveredNode)) {
                    matchesLabel = true;
                    opacity = 0.85;
                }
            } else {
                if (zoom >= 0.8) {
                    matchesLabel = true;
                    opacity = Math.min(1.0, (zoom - 0.7) * 4);
                } else if (zoom >= 0.4 && node.degree >= 8) {
                    matchesLabel = true;
                    opacity = Math.min(0.8, (zoom - 0.3) * 3);
                }
            }

            if (matchesLabel) {
                mCtx.fillStyle = `rgba(232, 237, 242, ${opacity})`;
                mCtx.font = "bold 9px system-ui, -apple-system, sans-serif";
                mCtx.textAlign = "center";
                mCtx.textBaseline = "top";

                mCtx.strokeStyle = `rgba(10, 12, 16, ${opacity * 0.95})`;
                mCtx.lineWidth = 3.2 / zoom;
                mCtx.lineJoin = "round";
                mCtx.strokeText(node.name, node.x, node.y + node.radius + 4);
                mCtx.fillText(node.name, node.x, node.y + node.radius + 4);
            }
        }

        mCtx.restore();
    }

    function recenterGraph() {
        if (activeNodes.length === 0) return;
        let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
        for (const n of activeNodes) {
            if (n.x < minX) minX = n.x;
            if (n.x > maxX) maxX = n.x;
            if (n.y < minY) minY = n.y;
            if (n.y > maxY) maxY = n.y;
        }

        const gW = maxX - minX;
        const gH = maxY - minY;

        const pad = 48;
        const targetW = mCanvas.clientWidth - pad * 2;
        const targetH = mCanvas.clientHeight - pad * 2;

        zoom = Math.max(0.08, Math.min(1.4, Math.min(targetW / (gW || 1), targetH / (gH || 1))));
        pan.x = (mCanvas.clientWidth - gW * zoom) / 2 - minX * zoom;
        pan.y = (mCanvas.clientHeight - gH * zoom) / 2 - minY * zoom;

        draw();
    }

    if (cachedHash === cacheKey && cachedActiveNodes && cachedResolvedEdges) {
        activeNodes = cachedActiveNodes;
        resolvedEdges = cachedResolvedEdges;
        neighborMap = cachedNeighborMap;
        isLayoutCached = true;
    } else {
        const itemsCount = db.items.count;
        const nodes = [];
        const edges = [];
        const nodeMap = new Map();
        neighborMap = new Map();

        for (let i = 0; i < itemsCount; i++) {
            const item = db.items.get(i);
            if (!item) continue;
            const nNode = {
                id: i,
                name: item.name,
                category: item.categoryName || "Unknown",
                complexity: item.complexity,
                depth: item.depth,
                flags: item.flags,
                degree: 0,
                radius: 6
            };
            nodes.push(nNode);
            nodeMap.set(i, nNode);
            neighborMap.set(i, new Set());
        }

        const edgeKeySet = new Set();

        const addEdge = (idA, idB) => {
            if (idA < 0 || idA >= itemsCount || idB < 0 || idB >= itemsCount) return;

            const minVal = Math.min(idA, idB);
            const maxVal = Math.max(idA, idB);
            const edgeK = `${minVal}-${maxVal}`;

            if (!edgeKeySet.has(edgeK)) {
                edgeKeySet.add(edgeK);
                edges.push({source: minVal, target: maxVal});

                const nA = nodeMap.get(minVal);
                const nB = nodeMap.get(maxVal);
                if (nA) nA.degree++;
                if (nB) nB.degree++;

                neighborMap.get(minVal).add(maxVal);
                neighborMap.get(maxVal).add(minVal);
            }
        };

        if (db._rB) for (let i = 0; i < itemsCount; i++) {
            const ref = db.recipeIndex.get(i);
            if (ref && ref.offset !== 0xFFFFFFFF && ref.count > 0) try {
                const recipes = readRecipesAt(db._rB, db.strings, ref.offset, ref.count);
                for (const recipe of recipes) {
                    const outInd = recipe.outputItemIndex;
                    if (outInd >= 0 && outInd < itemsCount) {
                        for (const ing of recipe.ingredients) for (const vi of ing.variants) addEdge(vi, outInd);
                        for (const otherOut of recipe.itemOutputs) addEdge(outInd, otherOut.itemIndex);
                    }
                }
            } catch (e) {
            }
        }

        activeNodes = nodes.filter(n => n.degree > 0);
        const activeNodeMap = new Map();
        activeNodes.forEach(n => {
            n.radius = Math.max(5, 3.5 + Math.sqrt(n.degree) * 1.5);
            activeNodeMap.set(n.id, n);
        });

        resolvedEdges = edges.map(e => {
            const sNode = activeNodeMap.get(e.source);
            const tNode = activeNodeMap.get(e.target);
            if (sNode && tNode) return {source: sNode, target: tNode};
            return null;
        }).filter(Boolean);

        activeNodes.forEach((n, idx) => {
            const angle = idx * 0.15;
            const radius = 22 * Math.sqrt(idx);
            n.x = radius * Math.cos(angle);
            n.y = radius * Math.sin(angle);
        });

        cachedHash = cacheKey;
        cachedActiveNodes = activeNodes;
        cachedResolvedEdges = resolvedEdges;
        cachedNeighborMap = neighborMap;
    }

    // noinspection JSUnresolvedFunction
    const simulation = d3.forceSimulation(activeNodes)
        .force("link", d3.forceLink(resolvedEdges).distance(240).strength(0.0005))
        .force("charge", d3.forceManyBody().strength(-250).distanceMax(1000))
        .force("collide", d3.forceCollide(d => d.radius * 2.5 + 40).iterations(6))
        .force("center", d3.forceCenter(0, 0).strength(0.01));

    activeSim = simulation;

    const loader = container.querySelector("#graph-loader");

    await new Promise((resolve) => {
        if (isLayoutCached) {
            simulation.stop();
            if (loader) loader.remove();
            recenterGraph();
            resolve();
            return;
        }

        const tickCount = activeNodes.length > 500 ? 180 : 250;
        let ticksRun = 0;

        const doTicks = () => {
            for (let i = 0; i < 15 && ticksRun < tickCount; i++) {
                simulation.tick();
                ticksRun++;
            }

            const loaderText = container.querySelector("#graph-loader div[style*='font-weight: 500']");
            if (loaderText) {
                const percent = Math.round((ticksRun / tickCount) * 100);
                loaderText.textContent = `Generating All-Items Graph… ${percent}%`;
            }

            if (ticksRun < tickCount) {
                requestAnimationFrame(doTicks);
            } else {
                simulation.stop();
                if (loader) loader.remove();
                recenterGraph();
                resolve();
            }
        };

        doTicks();
    });

    mCanvas.addEventListener("pointerdown", e => {
        mCanvas.setPointerCapture(e.pointerId);
        lastMouse = {x: e.clientX, y: e.clientY};
        startMouse = {x: e.clientX, y: e.clientY};

        const graphCoords = transformCoords(e.clientX, e.clientY);
        let clickedNode = null;
        let cDist = 24 / zoom;

        for (const n of activeNodes) {
            const dst = Math.hypot(n.x - graphCoords.x, n.y - graphCoords.y);
            if (dst < Math.max(n.radius, cDist)) {
                clickedNode = n;
                cDist = dst;
            }
        }
        isPanning = true;
    });

    mCanvas.addEventListener("pointermove", e => {
        const currCoords = transformCoords(e.clientX, e.clientY);

        const now = Date.now();
        if (!isPanning && (now - lastHoverCheck > 32)) {
            lastHoverCheck = now;
            let found = null;
            let bestDistSq = (20 / zoom) * (20 / zoom);

            for (const n of activeNodes) {
                const dx = n.x - currCoords.x;
                const dy = n.y - currCoords.y;
                const distSq = dx * dx + dy * dy;
                const limit = Math.max(n.radius, 20 / zoom);
                if (distSq < limit * limit && distSq < bestDistSq) {
                    found = n;
                    bestDistSq = distSq;
                }
            }

            if (hoveredNode !== found) {
                hoveredNode = found;
                draw();
            }
        }

        if (isPanning) {
            pan.x += e.clientX - lastMouse.x;
            pan.y += e.clientY - lastMouse.y;
            draw();
        }

        lastMouse = {x: e.clientX, y: e.clientY};
    });

    mCanvas.addEventListener("pointerup", e => {
        try {
            mCanvas.releasePointerCapture(e.pointerId);
        } catch (_) {
        }

        isPanning = false;
        draw();

        const moveDist = Math.hypot(e.clientX - startMouse.x, e.clientY - startMouse.y);
        if (moveDist < 4 && hoveredNode) selectItem(hoveredNode.id);
    });

    mCanvas.addEventListener("wheel", e => {
        e.preventDefault();

        const pivot = transformCoords(e.clientX, e.clientY);
        const zoomFactor = e.deltaY > 0 ? 0.88 : 1.14;
        const newZoom = Math.max(0.04, Math.min(8, zoom * zoomFactor));

        pan.x = e.clientX - mCanvas.getBoundingClientRect().left - pivot.x * newZoom;
        pan.y = e.clientY - mCanvas.getBoundingClientRect().top - pivot.y * newZoom;
        zoom = newZoom;

        draw();
    }, {passive: false});

    function onResize() {
        draw();
    }

    window.addEventListener("resize", onResize);
    activeResizeListener = onResize;

    if (overlay) {
        overlay.innerHTML = `
            <div class="card" style="
                position: absolute;
                bottom: 16px;
                left: 16px;
                pointer-events: auto;
                padding: 14px;
                width: 260px;
                background: rgba(17, 20, 24, 0.95);
                border: 1px solid var(--border);
                box-shadow: var(--shadow-lg);
                font-family: var(--sans), sans-serif;
                display: flex;
                flex-direction: column;
                gap: 8px;
            ">
                <h4 style="margin: 0; color: var(--accent); font-size: 13px; font-weight: 600;">Recipe Network Graph</h4>
                <div style="font-size: 11px; display: flex; justify-content: space-between;">
                    <span>Nodes (Connected items):</span>
                    <strong style="color: var(--text);">${activeNodes.length}</strong>
                </div>
                <div style="font-size: 11px; display: flex; justify-content: space-between;; margin-bottom: 4px;">
                    <span>Total Connections:</span>
                    <strong style="color: var(--text);">${resolvedEdges.length}</strong>
                </div>
                <div style="
                    font-size: 10px;
                    line-height: 1.4;
                    color: var(--text-dim);
                    border-top: 1px solid var(--border);
                    padding-top: 8px;
                    margin-bottom: 4px;
                ">
                    • <strong>Scroll wheel</strong> to zoom in & out<br>
                    • <strong>Left-click & drag</strong> to pan<br>
                    • <strong>Hover</strong> circles to see recipe flows<br>
                    • <strong>Click a circle</strong> to open full details
                </div>
                <button id="recenter-graph-btn" style="
                    background: var(--bg-hover);
                    border: 1px solid var(--border);
                    color: var(--text);
                    padding: 6px;
                    cursor: pointer;
                    font-size: 11px;
                    text-align: center;
                    width: 100%;
                    transition: background var(--transition-fast);
                    font-weight: 500;
                ">Recenter Graph</button>
            </div>
        `;

        const btn = overlay.querySelector("#recenter-graph-btn");
        if (btn) {
            btn.addEventListener("click", () => {
                recenterGraph();
            });
            btn.addEventListener("pointerdown", e => e.stopPropagation());
        }
    }
}