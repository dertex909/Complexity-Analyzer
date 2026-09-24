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

export function mountVirtualList(container, options) {
    const {
        itemCount,
        itemHeight = 28,
        dynamicHeight = false,
        overscan = 4,
        renderRow,
        emptyMessage,
        initialScrollTop = 0
    } = options;

    let head = container.querySelector(".table-head") || (container.parentNode ? container.parentNode.querySelector(".table-head") : null);
    if (head && head.parentNode) head.parentNode.removeChild(head);

    const viewport = document.createElement("div");
    viewport.className = "virtual-viewport";
    if (head) viewport.appendChild(head);

    const spacer = document.createElement("div");
    spacer.className = "virtual-spacer";

    const rows = document.createElement("div");
    rows.className = "virtual-rows";

    viewport.appendChild(spacer);
    viewport.appendChild(rows);
    container.innerHTML = "";
    container.appendChild(viewport);

    if (itemCount === 0) {
        spacer.style.height = "0px";
        rows.innerHTML = `
            <div class="empty-state" style="padding: 40px 0;">
                <div class="icon">🔍</div>
                <div class="message">${emptyMessage || "No items match your filter."}</div>
            </div>`;
        return {
            viewport,
            destroy: () => {
            },
            update: () => {
            },
            scrollToIndex: () => {
            },
            getScrollTop: () => 0,
            setScrollTop: () => {
            }
        };
    }

    let heights = null;
    let offsets = null;

    const recomputeOffsets = (fromIndex = 0) => {
        for (let i = fromIndex; i < itemCount; i++) offsets[i + 1] = offsets[i] + heights[i];
        spacer.style.height = offsets[itemCount] + "px";
    };

    if (dynamicHeight) {
        heights = new Float32Array(itemCount);
        heights.fill(itemHeight);
        offsets = new Float64Array(itemCount + 1);
        recomputeOffsets(0);
    } else {
        spacer.style.height = (itemCount * itemHeight) + "px";
    }

    const findIndexAtOffset = (targetOffset) => {
        let low = 0;
        let high = itemCount - 1;
        while (low <= high) {
            const mid = (low + high) >> 1;
            if (offsets[mid + 1] <= targetOffset) {
                low = mid + 1;
            } else if (offsets[mid] > targetOffset) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return Math.max(0, Math.min(itemCount - 1, low));
    };

    let rafId = 0;
    let prevFirst = -1;
    let prevLast = -1;

    const render = (force = false) => {
        if (rafId) return;
        rafId = requestAnimationFrame(() => {
            rafId = 0;
            const top = viewport.scrollTop;
            const h = viewport.clientHeight;
            if (h === 0 && !force) return;

            let first, last, translateY;

            if (!dynamicHeight) {
                first = Math.max(0, Math.floor(top / itemHeight) - overscan);
                last = Math.min(itemCount, Math.ceil((top + (h || 600)) / itemHeight) + overscan);
                translateY = first * itemHeight;
            } else {
                const rawFirst = findIndexAtOffset(top);
                const rawLast = findIndexAtOffset(top + (h || 600));
                first = Math.max(0, rawFirst - overscan);
                last = Math.min(itemCount, rawLast + 1 + overscan);
                translateY = offsets[first];
            }

            if (!force && first === prevFirst && last === prevLast) return;
            prevFirst = first;
            prevLast = last;

            rows.style.transform = `translateY(${translateY}px)`;
            rows.innerHTML = "";
            const frag = document.createDocumentFragment();

            for (let i = first; i < last; i++) {
                const row = renderRow(i);
                if (row) {
                    if (!dynamicHeight) row.style.height = itemHeight + "px";
                    row.dataset.virtualIndex = String(i);
                    frag.appendChild(row);
                }
            }
            rows.appendChild(frag);

            if (dynamicHeight) {
                let changed = false;
                let firstChangedIdx = itemCount;
                const renderedChildren = rows.children;

                for (let k = 0; k < renderedChildren.length; k++) {
                    const child = renderedChildren[k];
                    const idx = first + k;
                    if (idx >= itemCount) break;

                    const measuredH = child.getBoundingClientRect().height;
                    if (measuredH > 0 && Math.abs(heights[idx] - measuredH) > 0.5) {
                        heights[idx] = measuredH;
                        changed = true;
                        if (idx < firstChangedIdx) firstChangedIdx = idx;
                    }
                }

                if (changed) {
                    recomputeOffsets(firstChangedIdx);
                    rows.style.transform = `translateY(${offsets[first]}px)`;
                }
            }
        });
    };

    const onScroll = () => render(false);
    viewport.addEventListener("scroll", onScroll, {passive: true});
    const ro = new ResizeObserver(() => render(true));
    ro.observe(viewport);
    if (initialScrollTop > 0) viewport.scrollTop = initialScrollTop;
    render(true);

    return {
        viewport,
        update: () => render(true),
        scrollToIndex: (index) => {
            if (index < 0 || index >= itemCount) return;
            viewport.scrollTop = dynamicHeight ? offsets[index] : index * itemHeight;
        },
        getScrollTop: () => viewport.scrollTop,
        setScrollTop: (val) => {
            viewport.scrollTop = val;
        },
        destroy: () => {
            if (rafId) cancelAnimationFrame(rafId);
            viewport.removeEventListener("scroll", onScroll);
            ro.disconnect();
        }
    };
}