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

const NODE_W = 160;
const NODE_H = 28;
const LEVEL_GAP = 60;
const SIBLING_GAP = 12;
const MAX_DEPTH = 16;

export async function renderCraftTree(container, db, itemIndex) {
    container.innerHTML = `<div class="tree-canvas-container" id="tree-wrap"><canvas id="tree-canvas"></canvas></div>`;
    const wrap = container.querySelector("#tree-wrap");
    const canvas = container.querySelector("#tree-canvas");
    const ctx = canvas.getContext("2d");

    function resize() {
        canvas.width = wrap.clientWidth;
        canvas.height = wrap.clientHeight;
    }

    resize();
    window.addEventListener("resize", resize);

    const tree = await buildTree(db, itemIndex, 0);
    computeLayout(tree);
    const bounds = getBounds(tree);
    const totalW = bounds.maxX - bounds.minX + NODE_W + SIBLING_GAP * 2;
    const totalH = bounds.maxY - bounds.minY + NODE_H + LEVEL_GAP * 2;

    let zoom = Math.min(1, canvas.width / totalW, canvas.height / totalH);
    let pan = {
        x: (canvas.width - totalW * zoom) / 2 - bounds.minX * zoom,
        y: (canvas.height - totalH * zoom) / 2 - bounds.minY * zoom
    };

    function draw() {
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        ctx.save();
        ctx.translate(pan.x, pan.y);
        ctx.scale(zoom, zoom);
        drawNode(ctx, tree, db);
        ctx.restore();
    }

    function drawNode(ctx, node, db) {
        const it = db.items.get(node.itemIndex);
        const x = node.x, y = node.y;
        // box
        ctx.fillStyle = "#1d232b";
        ctx.strokeStyle = it && it.flags & 0x10 ? "#ffc76d" : "#3b8cb0";
        ctx.lineWidth = 1.5;
        roundRect(ctx, x, y, NODE_W, NODE_H, 4);
        ctx.fill();
        ctx.stroke();
        // text
        ctx.fillStyle = "#e6ecf2";
        ctx.font = "12px system-ui, sans-serif";
        ctx.textAlign = "left";
        ctx.textBaseline = "middle";
        const label = it ? it.name : "#" + node.itemIndex;
        ctx.fillText(truncate(ctx, label, NODE_W - 12), x + 6, y + NODE_H / 2);

        for (const child of node.children) {
            ctx.beginPath();
            ctx.moveTo(x + NODE_W / 2, y + NODE_H);
            ctx.lineTo(child.x + NODE_W / 2, child.y);
            ctx.strokeStyle = "#3b8cb0";
            ctx.lineWidth = 1;
            ctx.stroke();
            drawNode(ctx, child, db);
        }
    }

    let dragging = null, lastMouse = {x: 0, y: 0};
    canvas.addEventListener("mousedown", e => {
        dragging = true;
        lastMouse = {x: e.clientX, y: e.clientY};
    });
    window.addEventListener("mousemove", e => {
        if (!dragging) return;
        pan.x += e.clientX - lastMouse.x;
        pan.y += e.clientY - lastMouse.y;
        lastMouse = {x: e.clientX, y: e.clientY};
        draw();
    });
    window.addEventListener("mouseup", () => {
        dragging = false;
    });
    canvas.addEventListener("wheel", e => {
        e.preventDefault();
        const factor = e.deltaY > 0 ? 0.9 : 1.1;
        zoom *= factor;
        zoom = Math.max(0.1, Math.min(5, zoom));
        draw();
    });

    draw();
    return () => window.removeEventListener("resize", resize);
}

async function buildTree(db, itemIndex, depth) {
    const node = {itemIndex, depth, children: []};
    if (depth >= MAX_DEPTH) return node;
    try {
        const recipes = await db.getItemRecipes(itemIndex);
        const best = recipes.find(r => r.category === 0) || recipes[0];
        if (!best) return node;
        const seen = new Set();
        for (const slot of best.ingredients) {
            for (const vi of slot.variants) {
                if (seen.has(vi)) continue;
                seen.add(vi);
                const child = await buildTree(db, vi, depth + 1);
                child.slotCount = slot.count;
                node.children.push(child);
            }
        }
    } catch (e) {
        // no recipes or error
    }
    return node;
}

function computeLayout(node) {
    // post-order: compute subtree width
    let subtreeW = 0;
    for (const child of node.children) {
        computeLayout(child);
        subtreeW += child._subtreeW + SIBLING_GAP;
    }
    if (node.children.length === 0) subtreeW = NODE_W;
    else subtreeW -= SIBLING_GAP;
    node._subtreeW = subtreeW;
    node.y = node.depth * (NODE_H + LEVEL_GAP);

    // assign x positions centered on parent
    let cursor = 0;
    for (const child of node.children) {
        child.x = cursor;
        cursor += child._subtreeW + SIBLING_GAP;
    }
    // shift children so parent is centered
    if (node.children.length > 0) {
        const first = node.children[0];
        const last = node.children[node.children.length - 1];
        const childrenCenter = (first.x + last.x + last._subtreeW) / 2;
        const shift = childrenCenter - NODE_W / 2;
        for (const child of node.children) child.x -= shift;
    }
    // center this node relative to its subtree
    if (node.children.length === 0) node.x = 0;
    else {
        const first = node.children[0];
        const last = node.children[node.children.length - 1];
        node.x = (first.x + last.x + last._subtreeW - NODE_W) / 2;
    }
}

function getBounds(node, bounds = {minX: Infinity, maxX: -Infinity, minY: Infinity, maxY: -Infinity}) {
    bounds.minX = Math.min(bounds.minX, node.x);
    bounds.maxX = Math.max(bounds.maxX, node.x + NODE_W);
    bounds.minY = Math.min(bounds.minY, node.y);
    bounds.maxY = Math.max(bounds.maxY, node.y + NODE_H);
    for (const child of node.children) getBounds(child, bounds);
    return bounds;
}

function roundRect(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.arcTo(x + w, y, x + w, y + r, r);
    ctx.lineTo(x + w, y + h - r);
    ctx.arcTo(x + w, y + h, x + w - r, y + h, r);
    ctx.lineTo(x + r, y + h);
    ctx.arcTo(x, y + h, x, y + h - r, r);
    ctx.lineTo(x, y + r);
    ctx.arcTo(x, y, x + r, y, r);
    ctx.closePath();
}

function truncate(ctx, text, maxWidth) {
    let width = ctx.measureText(text).width;
    if (width <= maxWidth) return text;
    let len = text.length;
    while (len > 0) {
        const sub = text.slice(0, len) + "…";
        if (ctx.measureText(sub).width <= maxWidth) return sub;
        len--;
    }
    return "…";
}
