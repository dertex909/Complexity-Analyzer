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

export const DEFAULT_CAT_COLORS = {
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

export const LAYOUT_CONSTANTS = {
    PIPELINE: {
        colSpacing: 280,
        rowSpacing: 72,
        subColSpacing: 74,
        nodesPerSubCol: 14
    },
    FOCUSED: {
        colSpacing: 280,
        rowSpacing: 84
    },
    RADIAL: {
        baseRadius: 180,
        radiusStep: 190,
        angleOffset: 0.18
    },
    CATEGORY: {
        blockSpacingX: 650,
        blockSpacingY: 550,
        nodeSpacing: 76
    }
};

export const CAMERA_CONSTANTS = {
    MIN_ZOOM: 0.04,
    MAX_ZOOM: 5.0,
    ZOOM_FACTOR_IN: 1.18,
    ZOOM_FACTOR_OUT: 0.85,
    PAN_LERP_SPEED: 0.16,
    NODE_LERP_SPEED: 0.18,
    VIEWPORT_PADDING: 64,
    FIT_PADDING_MIN: 0.04,
    FIT_PADDING_MAX: 1.4
};

export const NODE_CONSTANTS = {
    MIN_RADIUS: 7,
    MAX_RADIUS: 18,
    BASE_RADIUS: 5,
    RADIUS_SCALE: 1.5,
    HIT_TEST_PADDING: 12,
    MIN_HIT_DISTANCE: 24
};

export const LABEL_CONSTANTS = {
    ZOOM_FULL_LABELS: 0.7,
    ZOOM_HIGH_DEGREE: 0.42,
    ZOOM_ULTRA_HIGH_DEGREE: 0.22,
    HIGH_DEGREE_THRESHOLD: 6,
    ULTRA_HIGH_DEGREE_THRESHOLD: 16,
    LABEL_PADDING: 12,
    LABEL_HEIGHT: 15,
    LABEL_OFFSET: 6
};

export const EDGE_CONSTANTS = {
    BASE_COLOR: [0.27, 0.33, 0.41],
    OPACITY: 0.35,
    CURVE_STEPS: 8,
    CURVE_CONTROL_FACTOR: 0.4,
    MIN_ZOOM_RENDER: 0.1
};
