export function mountVirtualList(container, options) {
    const { itemCount, itemHeight, renderRow } = options;
    const viewport = document.createElement("div");
    viewport.className = "virtual-viewport";
    const spacer = document.createElement("div");
    spacer.className = "virtual-spacer";
    const rows = document.createElement("div");
    rows.className = "virtual-rows";
    viewport.appendChild(spacer);
    viewport.appendChild(rows);
    container.innerHTML = "";
    container.appendChild(viewport);

    spacer.style.height = (itemCount * itemHeight) + "px";

    let scheduled = false;
    const render = () => {
        if (scheduled) return;
        scheduled = true;
        requestAnimationFrame(() => {
            scheduled = false;
            const top = viewport.scrollTop;
            const h = viewport.clientHeight;
            const first = Math.max(0, Math.floor(top / itemHeight) - 4);
            const last = Math.min(itemCount, Math.ceil((top + h) / itemHeight) + 4);
            rows.style.transform = `translateY(${first * itemHeight}px)`;
            rows.innerHTML = "";
            const frag = document.createDocumentFragment();
            for (let i = first; i < last; i++) {
                const row = renderRow(i);
                if (row) {
                    row.style.height = itemHeight + "px";
                    frag.appendChild(row);
                }
            }
            rows.appendChild(frag);
        });
    };

    const head = container.parentNode ? container.parentNode.querySelector(".table-head") : null;
    const syncScrollbar = () => {
        if (head) {
            const scrollbarWidth = viewport.offsetWidth - viewport.clientWidth;
            head.style.paddingRight = `${scrollbarWidth}px`;
        }
    };

    const observer = new ResizeObserver(() => {
        syncScrollbar();
    });
    observer.observe(viewport);

    viewport.addEventListener("scroll", render, { passive: true });
    // Also listen to window resize events just in case
    window.addEventListener("resize", syncScrollbar, { passive: true });

    render();
    syncScrollbar();

    return {
        refresh: () => {
            spacer.style.height = (itemCount * itemHeight) + "px";
            render();
            syncScrollbar();
        },
        scrollToIndex: (index) => {
            viewport.scrollTop = index * itemHeight;
            render();
        },
        destroy: () => {
            viewport.removeEventListener("scroll", render);
            window.removeEventListener("resize", syncScrollbar);
            observer.disconnect();
        }
    };
}
