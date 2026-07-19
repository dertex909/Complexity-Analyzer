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

export const LAYOUT_VERSION = "v5_perfect_spread";

export const GRAPH_CONFIG = {
    VIEWPORT_PAD: 48,
    EDGE_PAD: 20,

    MIN_ZOOM: 0.04,
    MAX_ZOOM: 8,
    INITIAL_ZOOM_MIN: 0.08,
    INITIAL_ZOOM_MAX: 1.4,
    ZOOM_FACTOR: 1.14,
    ZOOM_FACTOR_OUT: 0.88,

    MIN_NODE_RADIUS: 5,
    NODE_BASE_RADIUS: 3.5,
    NODE_RADIUS_SCALE: 1.5,
    NODE_COLLISION_MULTIPLIER: 2.5,
    NODE_COLLISION_OFFSET: 40,
    MIN_DRAW_RADIUS: 2.0,
    NODE_RENDER_BUFFER: 10,

    LABEL_FULL_ZOOM: 0.8,
    LABEL_FADE_START: 0.7,
    LABEL_FADE_FACTOR: 4,
    LABEL_HUB_ZOOM: 0.4,
    LABEL_HUB_FADE_START: 0.3,
    LABEL_HUB_FADE_FACTOR: 3,
    LABEL_HUB_MIN_DEGREE: 8,
    LABEL_MAX_OPACITY: 0.8,
    LABEL_OFFSET: 4,
    LABEL_RENDER_BUFFER: 150,

    CLICK_THRESHOLD: 4,
    HOVER_CHECK_INTERVAL: 32,
    HOVER_RADIUS: 20,
    CLICK_RADIUS: 24,

    HOVER_OPACITY_FULL: 1.0,
    HOVER_OPACITY_NEIGHBOR: 0.95,
    HOVER_OPACITY_OTHER: 0.22,
    HOVER_EDGE_WIDTH: 2.5,

    STROKE_MIN_ZOOM: 0.2,
    STROKE_WIDTH_NORMAL: 1.2,
    STROKE_WIDTH_HOVER: 3.0,
    STROKE_WIDTH_NEIGHBOR: 2.0,

    FORCE_LINK_DISTANCE: 240,
    FORCE_LINK_STRENGTH: 0.0005,
    FORCE_CHARGE_STRENGTH: -250,
    FORCE_CHARGE_MAX_DISTANCE: 1000,
    FORCE_CENTER_STRENGTH: 0.01,
    FORCE_COLLIDE_ITERATIONS: 6,

    TICK_COUNT_LARGE: 180,
    TICK_COUNT_SMALL: 250,
    TICK_LARGE_THRESHOLD: 500,
    TICKS_PER_FRAME: 15,

    INITIAL_LAYOUT_ANGLE: 0.15,
    INITIAL_LAYOUT_RADIUS: 22,

    DEFAULT_COLOR: "#5bc0ff",
    STROKE_COLOR_NORMAL: "#0a0c10",
    STROKE_COLOR_HOVER: "#ffffff",
    STROKE_COLOR_NEIGHBOR: "rgba(255, 255, 255, 0.75)",
    HOVER_EDGE_COLOR: "#5bc0ff",
    LABEL_COLOR: "232, 237, 242",
    LABEL_STROKE_COLOR: "10, 12, 16",
    LABEL_STROKE_WIDTH: 3.2,
};
