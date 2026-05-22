import {
    escapeHtml,
    formatComplexity,
    formatRawTooltip,
    getItemFlags,
    fmtInt
} from "../../core/utils.js";
import { state, setState, selectItem } from "../../core/state.js";
import { ITEM_FLAG } from "../../core/cabin.js";

export function renderItemDetail(unusedContainer, itemIndex) {
    const db = state.db;
    if (!db) return;
    const item = db.items.get(itemIndex);
    if (!item) return;

    const overlay = document.getElementById("item-modal");
    if (!overlay) return;

    const titleEl = document.getElementById("item-modal-title");
    const bodyEl = document.getElementById("item-modal-body");
    const closeEl = document.getElementById("item-modal-close");

    if (titleEl) {
        titleEl.innerHTML = `${escapeHtml(item.name)} <code class="mono-code" style="font-size:11px;">${escapeHtml(item.id)}</code>`;
    }

    const isMachine = db.machines.some(m => m.itemIndex === itemIndex);
    const hasRecipe = (item.flags & ITEM_FLAG.HAS_RECIPE) !== 0;
    const hasUses = item.usageCount > 0;
    const hasBaseSources = (item.baseDataOffset !== 0xFFFFFFFF || item.sourcesOffset !== 0xFFFFFFFF || item.sourceCount > 0);

    if (bodyEl) {
        bodyEl.innerHTML = `
            <dl class="detail-grid">
                <dt>Complexity</dt><dd class="cat-${item.categoryName || "Uncalculable"}" style="font-weight:600; font-size:14px;" title="${formatRawTooltip(item.complexity)}">${formatComplexity(item.complexity)}</dd>
                <dt>Category</dt><dd><span class="category-pill cat-${item.categoryName || "Uncalculable"}">${item.categoryName}</span></dd>
                <dt>Depth</dt><dd title="${formatRawTooltip(item.depth)}">${fmtInt.format(item.depth)}</dd>
                <dt>Total Ingredients</dt><dd title="${formatRawTooltip(item.totalIngredients)}">${fmtInt.format(item.totalIngredients)}</dd>
                <dt>Recipe Usages</dt><dd title="${formatRawTooltip(item.usageCount)}">Used in <strong>${fmtInt.format(item.usageCount)}</strong> recipe(s)</dd>
                <dt>Flags</dt><dd class="flags">${getItemFlags(item, false)}</dd>
                ${item.errorMessage ? `<dt>Error</dt><dd style="color:var(--err)">${escapeHtml(item.errorMessage)}</dd>` : ""}
            </dl>

            <div class="modal-actions-grid">
                <button class="modal-action-card" data-action="item-recipes" ${!hasRecipe ? "disabled" : ""}>
                    <span class="action-title">Recipes in Machines</span>
                    <span class="action-desc">${hasRecipe ? "Explore which machines craft this item" : "This item has no recipes"}</span>
                </button>
                <button class="modal-action-card" data-action="item-machine-recipes" ${!isMachine ? "disabled" : ""}>
                    <span class="action-title">Machine Output</span>
                    <span class="action-desc">${isMachine ? "View all recipes crafted in this machine" : "This item is not a machine"}</span>
                </button>
                <button class="modal-action-card" data-action="item-uses" ${!hasUses ? "disabled" : ""}>
                    <span class="action-title">Uses of Item</span>
                    <span class="action-desc">${hasUses ? "Check recipes requiring this ingredient" : "This item is not used in any recipe"}</span>
                </button>
                <button class="modal-action-card" data-action="item-base-sources" ${!hasBaseSources ? "disabled" : ""}>
                    <span class="action-title">Base Sources</span>
                    <span class="action-desc">${hasBaseSources ? "View world drops, ores and baseline sources" : "This item has no base sources"}</span>
                </button>
            </div>
        `;

        // Wire up action cards
        bodyEl.querySelectorAll(".modal-action-card").forEach(card => {
            if (card.hasAttribute("disabled")) return;
            card.addEventListener("click", () => {
                const action = card.dataset.action;
                overlay.hidden = true;
                setState({ tab: action });
            });
        });
    }

    // Function to close modal safely
    const closeModal = () => {
        overlay.hidden = true;
        setState({ selectedItem: -1 });
    };

    if (closeEl) {
        // Remove existing listener if any, by cloning the element
        const newClose = closeEl.cloneNode(true);
        closeEl.replaceWith(newClose);
        newClose.addEventListener("click", closeModal);
    }

    // Dynamic background click close
    overlay.onclick = (e) => {
        if (e.target === overlay) closeModal();
    };

    // Show the modal overlay
    overlay.hidden = false;
}