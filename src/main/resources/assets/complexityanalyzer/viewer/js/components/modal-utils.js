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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import {setState} from "../core/state.js";
import {appBack} from "../core/router.js";

export function setupModalClose(overlay, closeEl, onClose) {
    const closeModal = () => {
        overlay.hidden = true;
        if (appBack()) return;
        if (onClose) onClose();
        else setState({selectedItem: -1});
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