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

import {DEFAULT_CAT_COLORS} from './constants.js';

const categoryColors = new Map();

export function getCatColor(cat) {
    if (!cat) return "#5bc0ff";
    if (categoryColors.has(cat)) return categoryColors.get(cat);

    const cssVarName = `--cat-${cat.replace(/\s+/g, "_")}`;
    let col = getComputedStyle(document.documentElement).getPropertyValue(cssVarName).trim();
    if (!col) col = getComputedStyle(document.documentElement).getPropertyValue(`--cat-${cat}`).trim();
    if (!col) col = DEFAULT_CAT_COLORS[cat] || "#5bc0ff";

    categoryColors.set(cat, col);
    return col;
}

export function hexToRgb(hex) {
    const shorthandRegex = /^#?([a-f\d])([a-f\d])([a-f\d])$/i;
    const fullHex = hex.replace(shorthandRegex, (m, r, g, b) => r + r + g + g + b + b);
    const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(fullHex);
    return result ? [
        parseInt(result[1], 16) / 255,
        parseInt(result[2], 16) / 255,
        parseInt(result[3], 16) / 255
    ] : [0.35, 0.75, 1.0];
}

export function screenToGraph(clientX, clientY, canvas, camera) {
    const bounds = canvas.getBoundingClientRect();
    const mX = clientX - bounds.left;
    const mY = clientY - bounds.top;
    return {x: (mX - camera.pan.x) / camera.zoom, y: (mY - camera.pan.y) / camera.zoom};
}

export function rectsOverlap(rect1, rect2) {
    return rect1.minX < rect2.maxX && rect1.maxX > rect2.minX && rect1.minY < rect2.maxY && rect1.maxY > rect2.minY;
}
