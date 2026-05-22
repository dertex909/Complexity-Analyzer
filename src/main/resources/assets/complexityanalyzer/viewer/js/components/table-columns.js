export function generateTableHeader(columns, gridClass, headId) {
    if (!columns || !Array.isArray(columns)) {
        throw new Error("generateTableHeader requires an array of column definitions");
    }

    const headerCells = columns.map(col => {
        const isClickable = col.filter !== null;
        const clickableClass = isClickable ? "clickable-header" : "";
        const numClass = col.numeric ? "num" : "";
        const filterAttr = isClickable ? `data-filter="${col.filter}"` : "";
        const indicator = isClickable ? `<span class="filter-indicator">▼</span>` : "";
        
        return `
            <div class="th-cell ${numClass} ${clickableClass}" data-index="${col.index}" ${filterAttr}>
                <span class="th-text">${col.label} ${indicator}</span>
                <div class="col-drag-handle"></div>
            </div>
        `;
    }).join("");

    return `
        <div class="table-head ${gridClass}" id="${headId}" data-column-count="${columns.length}">
            ${headerCells}
        </div>
    `;
}