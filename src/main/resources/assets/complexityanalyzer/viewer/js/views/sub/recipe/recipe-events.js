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

import {debounce} from "../../../core/utils.js";
import {setState} from "../../../core/state.js";

export function saveScrollPositions(elem) {
    const scrollPositions = [];
    let parent = elem;
    while (parent) {
        if (parent.scrollTop !== undefined) {
            scrollPositions.push({element: parent, top: parent.scrollTop, left: parent.scrollLeft});
        }
        parent = parent.parentElement;
    }
    const winTop = window.scrollY || document.documentElement.scrollTop;
    const winLeft = window.scrollX || document.documentElement.scrollLeft;
    return {
        winTop,
        winLeft,
        scrollPositions
    };
}

export function restoreScrollPositions(saved) {
    if (!saved) return;
    for (const p of saved.scrollPositions) {
        p.element.scrollTop = p.top;
        p.element.scrollLeft = p.left;
    }
    window.scrollTo(saved.winLeft, saved.winTop);
}

export function wireRecipeLinks(container) {
    container.querySelectorAll(".ingredient-link, .item-link").forEach(el => {
        el.addEventListener("click", (e) => {
            e.stopPropagation();
            setState({tab: "item-recipes", selectedItem: parseInt(el.dataset.index, 10)});
        });
    });
    container.querySelectorAll(".fluid-link").forEach(el => {
        el.addEventListener("click", (e) => {
            e.stopPropagation();
            setState({tab: "fluid-recipes", selectedItem: parseInt(el.dataset.index, 10)});
        });
    });
}

export function wireRecipeControls(container, onReRender) {
    const select = container.querySelector("#recipes-sort");
    if (select) {
        select.addEventListener("change", (e) => {
            localStorage.setItem("recipes-sort", e.target.value);
            onReRender();
        });
    }

    const searchInput = container.querySelector("#recipes-search");
    if (searchInput) {
        const onSearch = debounce((val) => {
            if (container && container._customState) {
                container._customState.searchQuery = val;
            }
            onReRender();
        }, 150);

        searchInput.addEventListener("input", (e) => onSearch(e.target.value));
    }
}

export function wireRecipeEventsDelegated(listContainer, body, onSortChange) {
    listContainer.addEventListener("click", (e) => {
        const itemLink = e.target.closest(".ingredient-link, .item-link");
        if (itemLink) {
            e.stopPropagation();
            setState({tab: "item-recipes", selectedItem: parseInt(itemLink.dataset.index, 10)});
            return;
        }

        const fluidLink = e.target.closest(".fluid-link");
        if (fluidLink) {
            e.stopPropagation();
            setState({tab: "fluid-recipes", selectedItem: parseInt(fluidLink.dataset.index, 10)});
            return;
        }

        const machineLink = e.target.closest(".machine-link");
        if (machineLink) {
            e.stopPropagation();
            setState({tab: "item-machine-recipes", selectedItem: parseInt(machineLink.dataset.index, 10)});
            return;
        }

        const trigger = e.target.closest(".variant-trigger");
        if (trigger) {
            e.stopPropagation();
            const grp = trigger.closest(".variant-group");
            if (!grp) return;

            const isActive = grp.classList.contains("active");

            listContainer.querySelectorAll(".variant-group.active").forEach(g => {
                if (g !== grp) g.classList.remove("active");
            });

            if (!isActive) {
                grp.classList.add("active");
                const onDocClick = (de) => {
                    if (!grp.contains(de.target)) {
                        grp.classList.remove("active");
                        document.removeEventListener("click", onDocClick);
                    }
                };
                setTimeout(() => {
                    document.addEventListener("click", onDocClick);
                }, 0);
            } else {
                grp.classList.remove("active");
            }
            return;
        }

        const machineSub = e.target.closest(".variant-machine-substitute");
        if (machineSub) {
            e.stopPropagation();
            const recipeKey = machineSub.dataset.recipeKey;
            const machineKey = machineSub.dataset.machineKey || recipeKey;
            const machineIndex = parseInt(machineSub.dataset.machineIndex, 10);
            if (body && body._customState) {
                body._customState.selectedMachines.set(machineKey, machineIndex);
            }
            onSortChange();
            return;
        }

        const itemSub = e.target.closest(".variant-item-substitute");
        if (itemSub) {
            e.stopPropagation();
            const recipeKey = itemSub.dataset.recipeKey;
            const slotIndex = parseInt(itemSub.dataset.slotIndex, 10);
            const variantIndex = parseInt(itemSub.dataset.variantIndex, 10);
            if (body && body._customState) {
                body._customState.selectedIngredients.set(`${recipeKey}_ing_${slotIndex}`, variantIndex);
            }
            onSortChange();
            return;
        }

        const fluidSub = e.target.closest(".variant-fluid-substitute");
        if (fluidSub) {
            e.stopPropagation();
            const recipeKey = fluidSub.dataset.recipeKey;
            const slotIndex = parseInt(fluidSub.dataset.slotIndex, 10);
            const variantIndex = parseInt(fluidSub.dataset.variantIndex, 10);
            if (body && body._customState) {
                body._customState.selectedIngredients.set(`${recipeKey}_fluid_${slotIndex}`, variantIndex);
            }
            onSortChange();
        }
    });
}
