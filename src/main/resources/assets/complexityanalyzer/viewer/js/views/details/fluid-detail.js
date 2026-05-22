import { state, setState } from "../../core/state.js";
import { FLUID_FLAG } from "../../core/cabin.js";

const fmt = new Intl.NumberFormat("en-US", { maximumFractionDigits: 2 });
const fmtInt = new Intl.NumberFormat("en-US");

export function renderFluidDetail(unusedContainer, fluidIndex) {
    const db = state.db;
    if (!db) return;
    const fluid = db.fluids.get(fluidIndex);
    if (!fluid) return;

    const overlay = document.getElementById("fluid-modal");
    if (!overlay) return;

    const titleEl = document.getElementById("fluid-modal-title");
    const bodyEl = document.getElementById("fluid-modal-body");
    const closeEl = document.getElementById("fluid-modal-close");

    if (titleEl) {
        titleEl.innerHTML = `${escapeHtml(fluid.name)} <code class="mono-code" style="font-size:11px;">${escapeHtml(fluid.id)}</code>`;
    }

    const hasRecipe = (fluid.flags & FLUID_FLAG.HAS_RECIPE) !== 0;
    const hasUses = fluid.usageCount > 0;

    if (bodyEl) {
        bodyEl.innerHTML = `
            <dl class="detail-grid">
                <dt>Complexity</dt><dd class="cat-${fluid.categoryName || "Uncalculable"}" style="font-weight:600; font-size:14px;" title="${formatRawTooltip(fluid.complexity)}">${formatComplexity(fluid.complexity)}</dd>
                <dt>Category</dt><dd><span class="category-pill cat-${fluid.categoryName || "Uncalculable"}">${fluid.categoryName}</span></dd>
                <dt>Recipe Usages</dt><dd title="${formatRawTooltip(fluid.usageCount)}">Used as ingredient in <strong>${fmtInt.format(fluid.usageCount)}</strong> item recipe(s)</dd>
                <dt>Flags</dt><dd class="flags">${fluidFlags(fluid)}</dd>
                ${fluid.errorMessage ? `<dt>Error</dt><dd style="color:var(--err)">${escapeHtml(fluid.errorMessage)}</dd>` : ""}
            </dl>

            <div class="modal-actions-grid" style="grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));">
                <button class="modal-action-card" data-action="fluid-recipes" ${!hasRecipe ? "disabled" : ""}>
                    <span class="action-title">Recipes in Machines</span>
                    <span class="action-desc">${hasRecipe ? "Explore which machines craft this fluid" : "This fluid has no recipes"}</span>
                </button>
                <button class="modal-action-card" data-action="fluid-uses" ${!hasUses ? "disabled" : ""}>
                    <span class="action-title">Uses of Fluid</span>
                    <span class="action-desc">${hasUses ? "Check recipes requiring this fluid as ingredient" : "This fluid is not used in any recipe"}</span>
                </button>
            </div>
        `;

        bodyEl.querySelectorAll(".modal-action-card").forEach(card => {
            if (card.hasAttribute("disabled")) return;
            card.addEventListener("click", () => {
                const action = card.dataset.action;
                overlay.hidden = true;
                setState({ tab: action });
            });
        });
    }

    const closeModal = () => {
        overlay.hidden = true;
        setState({ selectedItem: -1 }); // We reuse selectedItem in state.js for selected fluid
    };

    if (closeEl) {
        const newClose = closeEl.cloneNode(true);
        closeEl.replaceWith(newClose);
        newClose.addEventListener("click", closeModal);
    }

    overlay.onclick = (e) => {
        if (e.target === overlay) closeModal();
    };

    overlay.hidden = false;
}

function fluidFlags(fl) {
    const out = [];
    const f = fl.flags;
    if (f & FLUID_FLAG.HAS_CYCLE) out.push(`<span class="flag cycle" title="cycle">⟲ Cycle</span>`);
    if (f & FLUID_FLAG.IS_INFINITE) out.push(`<span class="flag infinite" title="unobtainable">∞ Unobtainable</span>`);
    if (!(f & FLUID_FLAG.HAS_RECIPE)) out.push(`<span class="flag no-recipe" title="no recipe">∅ No recipe</span>`);
    if (f & FLUID_FLAG.IS_PROTECTED) out.push(`<span class="flag hardcoded" title="protected">P Protected</span>`);
    return out.join("");
}

function formatRawTooltip(val) {
    if (val === undefined || val === null || !isFinite(val)) return "";
    try {
        return new Intl.NumberFormat("de-DE", { maximumFractionDigits: 10 }).format(val);
    } catch (e) {
        return String(val);
    }
}

function formatComplexity(c) {
    if (c < 0) return "—";
    if (!isFinite(c)) return "∞";
    if (c === 0) return "0";
    return fmt.format(c);
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}
