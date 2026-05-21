import { state } from "../core/state.js";

const VERT = `
attribute vec2 a_pos;
attribute vec3 a_col;
varying vec3 v_col;
void main() {
    gl_Position = vec4(a_pos, 0.0, 1.0);
    gl_PointSize = 4.0;
    v_col = a_col;
}`;

const FRAG = `
precision mediump float;
varying vec3 v_col;
void main() {
    float d = length(gl_PointCoord - 0.5);
    if (d > 0.5) discard;
    gl_FragColor = vec4(v_col, 1.0);
}`;

function compile(gl, type, src) {
    const s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    return s;
}

function createProgram(gl, vs, fs) {
    const p = gl.createProgram();
    gl.attachShader(p, vs);
    gl.attachShader(p, fs);
    gl.linkProgram(p);
    return p;
}

const CAT_COLORS = {
    Absolute: [1,1,1], Trivial: [0.72,0.74,0.76], Simple: [0.48,0.87,0.62], Moderate: [0.94,0.86,0.47],
    Complex: [0.95,0.66,0.31], Difficult: [0.93,0.42,0.42], Expert: [0.69,0.51,0.94],
    Master: [0.41,0.84,0.89], Mythical: [0.94,0.62,0.83], Transcendent: [0.27,0.78,0.75],
    Unobtainable: [0.48,0.17,0.17], Uncalculable: [0.31,0.35,0.39], EXTERNAL_FRAGMENT: [0.63,0.63,0.63],
};

function catColor(name) { return CAT_COLORS[name] || CAT_COLORS.Uncalculable; }

function hashColor(str) {
    let h = 0;
    for (let i = 0; i < str.length; i++) h = ((h << 5) - h) + str.charCodeAt(i);
    h = Math.abs(h);
    return [(h & 0xFF) / 255, ((h >> 8) & 0xFF) / 255, ((h >> 16) & 0xFF) / 255];
}

export function renderGraph(container) {
    const canvas = container.querySelector("#graph-canvas");
    if (!canvas) return () => {};
    const gl = canvas.getContext("webgl", { antialias: false, preserveDrawingBuffer: false });
    if (!gl) { canvas.style.background = "#0e1014"; return () => {}; }
    const db = state.db;

    function resize() {
        const dpr = Math.min(window.devicePixelRatio || 1, 2);
        canvas.width = container.clientWidth * dpr;
        canvas.height = container.clientHeight * dpr;
        gl.viewport(0, 0, canvas.width, canvas.height);
    }
    resize();
    window.addEventListener("resize", resize);

    const prog = createProgram(gl, compile(gl, gl.VERTEX_SHADER, VERT), compile(gl, gl.FRAGMENT_SHADER, FRAG));
    gl.useProgram(prog);
    const uLoc = gl.getAttribLocation(prog, "a_pos");
    const cLoc = gl.getAttribLocation(prog, "a_col");

    // Build nodes + mod clusters
    const itemNodes = [];
    const modMap = new Map(); // modId -> { indices: [], color: [] }
    for (let i = 0; i < db.items.count; i++) {
        const it = db.items.get(i);
        if (!it) continue;
        itemNodes.push({ id: i, x: (Math.random() - 0.5) * 2000, y: (Math.random() - 0.5) * 2000, vx: 0, vy: 0, col: catColor(it.categoryName) });
        const modId = it.id.split(":")[0] || "unknown";
        if (!modMap.has(modId)) modMap.set(modId, { indices: [], col: hashColor(modId) });
        modMap.get(modId).indices.push(itemNodes.length - 1);
    }
    const modNodes = Array.from(modMap.entries()).map(([id, data]) => ({ id, indices: data.indices, col: data.col, x: 0, y: 0, vx: 0, vy: 0 }));

    // Edges from recipes (item -> ingredient)
    const edgePairs = [];
    for (let i = 0; i < db.items.count && edgePairs.length < 50000; i++) {
        const it = db.items.get(i);
        if (!it || !(it.flags & 0x01)) continue;
        // best-effort edge without async recipes: use usage table if loaded
        try {
            const usage = db._u ? db._u.get(i) : [];
            for (const u of usage) {
                if (u >= 0 && u < itemNodes.length) {
                    edgePairs.push(i, u);
                }
            }
        } catch (e) {}
    }

    let zoom = state.graphZoom || 0.3;
    let pan = state.graphPan || { x: canvas.width / 2, y: canvas.height / 2 };
    let dragging = false;
    let lastMouse = { x: 0, y: 0 };
    let running = true;

    function physicsStep() {
        const active = zoom < 0.6 ? modNodes : itemNodes;
        // Repulsion O(n^2) — throttle for large N
        const limit = active.length > 3000 ? 2000 : active.length;
        for (let i = 0; i < limit; i++) {
            for (let j = i + 1; j < limit; j++) {
                const dx = active[i].x - active[j].x;
                const dy = active[i].y - active[j].y;
                const dist2 = dx * dx + dy * dy + 1;
                const force = 8000 / dist2;
                const fx = (dx / Math.sqrt(dist2)) * force;
                const fy = (dy / Math.sqrt(dist2)) * force;
                active[i].vx += fx; active[i].vy += fy;
                active[j].vx -= fx; active[j].vy -= fy;
            }
        }
        for (const n of active) {
            n.vx *= 0.92;
            n.vy *= 0.92;
            n.x += n.vx;
            n.y += n.vy;
        }
        // Update mod positions from item averages
        for (const m of modNodes) {
            let sx = 0, sy = 0, c = 0;
            for (const idx of m.indices) {
                sx += itemNodes[idx].x;
                sy += itemNodes[idx].y;
                c++;
            }
            if (c) { m.x = sx / c; m.y = sy / c; }
        }
    }

    function toNDC(v, w, h, px, py, zm) {
        return [((v[0] + px) / w) * 2 - 1, -((v[1] + py) / h) * 2 + 1];
    }

    function draw() {
        const w = canvas.width, h = canvas.height;
        gl.clearColor(0.055, 0.062, 0.078, 1);
        gl.clear(gl.COLOR_BUFFER_BIT);

        const useMods = zoom < 0.6;
        const active = useMods ? modNodes : itemNodes;
        const pointSize = useMods ? 12.0 : 4.0;
        const count = Math.min(active.length, 30000);

        const posArr = new Float32Array(count * 2);
        const colArr = new Float32Array(count * 3);
        for (let i = 0; i < count; i++) {
            const n = active[i];
            const [nx, ny] = toNDC([n.x, n.y], w, h, pan.x - w / 2, pan.y - h / 2, zoom);
            posArr[i * 2] = nx;
            posArr[i * 2 + 1] = ny;
            colArr[i * 3] = n.col[0];
            colArr[i * 3 + 1] = n.col[1];
            colArr[i * 3 + 2] = n.col[2];
        }

        const posBuf = gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER, posBuf);
        gl.bufferData(gl.ARRAY_BUFFER, posArr, gl.DYNAMIC_DRAW);
        gl.enableVertexAttribArray(uLoc);
        gl.vertexAttribPointer(uLoc, 2, gl.FLOAT, false, 0, 0);

        const colBuf = gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER, colBuf);
        gl.bufferData(gl.ARRAY_BUFFER, colArr, gl.DYNAMIC_DRAW);
        gl.enableVertexAttribArray(cLoc);
        gl.vertexAttribPointer(cLoc, 3, gl.FLOAT, false, 0, 0);

        gl.enable(gl.BLEND);
        gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
        gl.drawArrays(gl.POINTS, 0, count);

        // draw edges if zoom close enough
        if (!useMods && edgePairs.length > 0 && zoom > 0.8) {
            const eCount = Math.min(edgePairs.length, 20000);
            const ePos = new Float32Array(eCount * 2);
            const eCol = new Float32Array(eCount * 3);
            const alpha = Math.min(1, (zoom - 0.8) * 2);
            for (let i = 0; i < eCount; i++) {
                const nodeIdx = edgePairs[i];
                const n = itemNodes[nodeIdx];
                const [nx, ny] = toNDC([n.x, n.y], w, h, pan.x - w / 2, pan.y - h / 2, zoom);
                ePos[i * 2] = nx;
                ePos[i * 2 + 1] = ny;
                eCol[i * 3] = 0.23; eCol[i * 3 + 1] = 0.55; eCol[i * 3 + 2] = 0.69;
            }
            gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
            gl.bufferData(gl.ARRAY_BUFFER, ePos, gl.DYNAMIC_DRAW);
            gl.vertexAttribPointer(uLoc, 2, gl.FLOAT, false, 0, 0);
            gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
            gl.bufferData(gl.ARRAY_BUFFER, eCol, gl.DYNAMIC_DRAW);
            gl.vertexAttribPointer(cLoc, 3, gl.FLOAT, false, 0, 0);
            gl.lineWidth(1);
            gl.drawArrays(gl.LINES, 0, eCount);
        }
    }

    function frame() {
        if (!running) return;
        physicsStep();
        draw();
        requestAnimationFrame(frame);
    }

    canvas.addEventListener("mousedown", e => { dragging = true; lastMouse = { x: e.clientX, y: e.clientY }; });
    window.addEventListener("mousemove", e => {
        if (!dragging) return;
        pan.x += e.clientX - lastMouse.x;
        pan.y += e.clientY - lastMouse.y;
        lastMouse = { x: e.clientX, y: e.clientY };
        state.graphPan = pan;
    });
    window.addEventListener("mouseup", () => { dragging = false; });
    canvas.addEventListener("wheel", e => {
        e.preventDefault();
        const factor = e.deltaY > 0 ? 0.9 : 1.1;
        zoom *= factor;
        zoom = Math.max(0.05, Math.min(5, zoom));
        state.graphZoom = zoom;
    });

    frame();
    return () => { running = false; window.removeEventListener("resize", resize); };
}
