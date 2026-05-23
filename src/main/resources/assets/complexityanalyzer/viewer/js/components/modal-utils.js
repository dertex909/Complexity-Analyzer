import {setState, goBack} from "../core/state.js";

export function setupModalClose(overlay, closeEl, onClose) {
    const closeModal = () => {
        overlay.hidden = true;
        if (onClose) {
            const wentBack = goBack();
            if (!wentBack) onClose();
        } else {
            const wentBack = goBack();
            if (!wentBack) setState({selectedItem: -1});
        }
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