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

export const NODE_VS = `#version 300 es
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

export const NODE_FS = `#version 300 es
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

export const EDGE_VS = `#version 300 es
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

export const EDGE_FS = `#version 300 es
precision mediump float;
in vec3 v_color;
out vec4 outColor;

void main() {
    outColor = vec4(v_color, 0.35);
}`;

export function createShader(gl, type, source) {
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

export function createProgram(gl, vsSource, fsSource) {
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
