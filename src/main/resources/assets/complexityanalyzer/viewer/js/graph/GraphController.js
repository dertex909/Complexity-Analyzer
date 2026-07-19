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

import {GraphData} from './GraphData.js';
import {Camera} from './Camera.js';
import {Layout} from './Layout.js';
import {Renderer} from './Renderer.js';
import {screenToGraph} from './utils.js';
import {CAMERA_CONSTANTS, NODE_CONSTANTS} from './constants.js';

export class GraphController {
    constructor(canvas, textCanvas, db) {
        this.canvas = canvas;
        this.db = db;

        this.gl = canvas.getContext("webgl2", {antialias: true, alpha: false});
        if (!this.gl) throw new Error("WebGL2 is not supported on this browser.");

        this.graphData = new GraphData(db);
        this.camera = new Camera();
        this.renderer = new Renderer(this.gl, textCanvas);

        this.viewMode = localStorage.getItem("ca_graph_viewmode") || "focused";
        this.layoutStyle = localStorage.getItem("ca_graph_layout") || "pipeline";
        this.edgeStyle = localStorage.getItem("ca_graph_edges") || "smart";
        this.highlightMode = localStorage.getItem("ca_graph_highlight") || "chain";
        this.selectedCategoryFilter = "All";

        this.hoveredNode = null;
        this.selectedNodeId = -1;
        this.focusedLevels = new Map();
        this.highlightChain = {
            upstream: new Set(),
            downstream: new Set(),
            upstreamEdges: new Set(),
            downstreamEdges: new Set()
        };

        this.isPanning = false;
        this.isDraggingMinimap = false;
        this.needsRedraw = true;
        this.visibleNodes = [];
        this.animationId = null;
    }

    initialize() {
        this.graphData.build();
        this.applyLayout();
        this.fitToView(false);
    }

    applyLayout() {
        const activeNodes = this.graphData.activeNodes;
        if (activeNodes.length === 0) return;

        if (this.viewMode === "focused") {
            this.calculateFocusedSubset();
            Layout.applyFocused(activeNodes, this.focusedLevels);
        } else {
            switch (this.layoutStyle) {
                case "pipeline":
                    Layout.applyPipeline(activeNodes);
                    break;
                case "radial":
                    Layout.applyRadial(activeNodes);
                    break;
                case "category":
                    Layout.applyCategory(activeNodes);
                    break;
            }
        }

        Layout.snapToTarget(activeNodes);
        this.uploadGeometry();
        this.needsRedraw = true;
    }

    calculateFocusedSubset() {
        this.focusedLevels.clear();
        if (this.viewMode !== "focused") return;

        let rootId = this.selectedNodeId;
        if (rootId === -1 && this.graphData.activeNodes.length > 0) {
            const maxNode = this.graphData.activeNodes.reduce(
                (max, n) => n.degree > max.degree ? n : max, this.graphData.activeNodes[0]
            );
            rootId = this.selectedNodeId = maxNode.id;
        }
        if (rootId === -1) return;

        const adjIngredients = new Map();
        const adjUsages = new Map();

        for (const edge of this.graphData.edges) {
            const s = edge.source.id;
            const t = edge.target.id;
            if (!adjIngredients.has(t)) adjIngredients.set(t, []);
            adjIngredients.get(t).push(s);
            if (!adjUsages.has(s)) adjUsages.set(s, []);
            adjUsages.get(s).push(t);
        }

        this.focusedLevels.set(rootId, 0);

        const qUp = [rootId];
        const visitedUp = new Set([rootId]);
        while (qUp.length > 0) {
            const curr = qUp.shift();
            const currL = this.focusedLevels.get(curr);
            const ings = adjIngredients.get(curr) || [];
            for (const ing of ings) {
                const targetL = currL - 1;
                if (!this.focusedLevels.has(ing)) {
                    this.focusedLevels.set(ing, targetL);
                } else {
                    this.focusedLevels.set(ing, Math.min(this.focusedLevels.get(ing), targetL));
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
            const currL = this.focusedLevels.get(curr);
            const outs = adjUsages.get(curr) || [];
            for (const out of outs) {
                const targetL = currL + 1;
                if (!this.focusedLevels.has(out)) {
                    this.focusedLevels.set(out, targetL);
                } else {
                    this.focusedLevels.set(out, Math.max(this.focusedLevels.get(out), targetL));
                }
                if (!visitedDown.has(out)) {
                    visitedDown.add(out);
                    qDown.push(out);
                }
            }
        }
    }

    uploadGeometry() {
        const activeViewNodes = this.getVisibleNodeSet();
        const activeViewEdges = this.getVisibleEdgeSet();

        this.renderer.uploadNodes(activeViewNodes);
        this.renderer.uploadEdges(activeViewEdges, this.edgeStyle);
    }

    getVisibleNodeSet() {
        return this.viewMode === "focused"
            ? this.graphData.activeNodes.filter(n => this.focusedLevels.has(n.id))
            : this.graphData.activeNodes;
    }

    getVisibleEdgeSet() {
        return this.viewMode === "focused"
            ? this.graphData.edges.filter(e => this.focusedLevels.has(e.source.id) && this.focusedLevels.has(e.target.id))
            : this.graphData.edges;
    }

    getBounds() {
        const nodes = this.getVisibleNodeSet();
        if (nodes.length === 0) return {minX: -100, maxX: 100, minY: -100, maxY: 100, width: 200, height: 200};

        let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
        for (const n of nodes) {
            if (n.targetX < minX) minX = n.targetX;
            if (n.targetX > maxX) maxX = n.targetX;
            if (n.targetY < minY) minY = n.targetY;
            if (n.targetY > maxY) maxY = n.targetY;
        }

        const margin = 140;
        return {
            minX: minX - margin, maxX: maxX + margin,
            minY: minY - margin, maxY: maxY + margin,
            width: (maxX - minX) + margin * 2,
            height: (maxY - minY) + margin * 2
        };
    }

    fitToView(smooth = true) {
        const bounds = this.getBounds();
        this.camera.fitToBounds(bounds, this.canvas.clientWidth, this.canvas.clientHeight, smooth);
        this.needsRedraw = true;
    }

    focusOnNode(nodeId) {
        const node = this.graphData.getNode(nodeId);
        if (!node) return;

        this.selectedNodeId = nodeId;

        if (this.viewMode === "focused") {
            this.applyLayout();
            this.fitToView(true);
        } else {
            this.camera.focusOn(
                node.targetX,
                node.targetY,
                this.canvas.clientWidth,
                this.canvas.clientHeight,
                1.1
            );
        }

        this.needsRedraw = true;
    }

    updateHighlights() {
        this.highlightChain.upstream.clear();
        this.highlightChain.downstream.clear();
        this.highlightChain.upstreamEdges.clear();
        this.highlightChain.downstreamEdges.clear();

        if (!this.hoveredNode) {
            this.needsRedraw = true;
            return;
        }

        const rootId = this.hoveredNode.id;

        if (this.highlightMode === "direct") {
            for (const edge of this.graphData.edges) {
                if (edge.source.id === rootId) {
                    this.highlightChain.downstream.add(edge.target.id);
                    this.highlightChain.downstreamEdges.add(edge);
                } else if (edge.target.id === rootId) {
                    this.highlightChain.upstream.add(edge.source.id);
                    this.highlightChain.upstreamEdges.add(edge);
                }
            }
        } else {
            this.calculateChainHighlight(rootId);
        }

        this.needsRedraw = true;
    }

    calculateChainHighlight(rootId) {
        const outToIng = new Map();
        const ingToOut = new Map();

        for (const edge of this.graphData.edges) {
            const s = edge.source.id;
            const t = edge.target.id;
            if (!outToIng.has(t)) outToIng.set(t, []);
            outToIng.get(t).push(edge);
            if (!ingToOut.has(s)) ingToOut.set(s, []);
            ingToOut.get(s).push(edge);
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
                    this.highlightChain.upstream.add(ingId);
                    qUp.push(ingId);
                }
                this.highlightChain.upstreamEdges.add(e);
            }
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
                    this.highlightChain.downstream.add(outId);
                    qDown.push(outId);
                }
                this.highlightChain.downstreamEdges.add(e);
            }
        }
    }

    findNodeAt(clientX, clientY) {
        const graphPos = screenToGraph(clientX, clientY, this.canvas, this.camera.getState());
        const nodes = this.getVisibleNodeSet();

        let found = null;
        let bestDistSq = (NODE_CONSTANTS.MIN_HIT_DISTANCE / this.camera.zoom) ** 2;

        for (const n of nodes) {
            const dx = n.x - graphPos.x;
            const dy = n.y - graphPos.y;
            const distSq = dx * dx + dy * dy;
            const limit = Math.max(n.radius + NODE_CONSTANTS.HIT_TEST_PADDING,
                NODE_CONSTANTS.MIN_HIT_DISTANCE / this.camera.zoom);

            if (distSq < limit * limit && distSq < bestDistSq) {
                found = n;
                bestDistSq = distSq;
            }
        }

        return found;
    }

    update() {
        let changed = false;

        if (this.camera.update(this.isPanning || this.isDraggingMinimap)) changed = true;

        if (Layout.updatePositions(this.graphData.activeNodes, CAMERA_CONSTANTS.NODE_LERP_SPEED)) {
            this.uploadGeometry();
            changed = true;
        }

        if (changed || this.needsRedraw) {
            this.render();
            this.needsRedraw = false;
        }
    }

    render() {
        const cameraState = this.camera.getState();
        const minGraphX = -cameraState.pan.x / cameraState.zoom;
        const maxGraphX = (this.canvas.clientWidth - cameraState.pan.x) / cameraState.zoom;
        const minGraphY = -cameraState.pan.y / cameraState.zoom;
        const maxGraphY = (this.canvas.clientHeight - cameraState.pan.y) / cameraState.zoom;

        const activeViewNodes = this.getVisibleNodeSet();
        this.visibleNodes = activeViewNodes.filter(node => {
            const pad = node.radius + 15;
            return !(node.x < minGraphX - pad || node.x > maxGraphX + pad || node.y < minGraphY - pad || node.y > maxGraphY + pad);
        });

        const renderState = {
            visibleNodes: this.visibleNodes,
            hoveredNode: this.hoveredNode,
            highlightChain: this.highlightChain,
            selectedCategoryFilter: this.selectedCategoryFilter
        };

        this.renderer.render(
            cameraState,
            this.canvas.clientWidth,
            this.canvas.clientHeight,
            renderState
        );
    }

    startAnimation() {
        const tick = () => {
            this.animationId = requestAnimationFrame(tick);
            this.update();
        };
        tick();
    }

    stopAnimation() {
        if (this.animationId) {
            cancelAnimationFrame(this.animationId);
            this.animationId = null;
        }
    }

    dispose() {
        this.stopAnimation();
        this.renderer.dispose();
    }
}
