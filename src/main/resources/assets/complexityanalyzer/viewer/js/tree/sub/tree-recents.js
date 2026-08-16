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

const RECENT_STORAGE_KEY = "ca_craft_tree_recents";
const MAX_RECENTS = 6;

export function getRecentItems() {
    try {
        const raw = localStorage.getItem(RECENT_STORAGE_KEY);
        const parsed = raw ? JSON.parse(raw) : [];
        return Array.isArray(parsed) ? parsed : [];
    } catch {
        return [];
    }
}

export function saveRecentItem(kind, index, db) {
    try {
        const entity = kind === "item" ? db.items.get(index) : db.fluids.get(index);
        if (!entity) return;

        const updated = [
            {
                kind,
                index,
                name: entity.name,
                id: entity.id,
                complexity: entity.complexity,
                cat: entity.categoryName || "Uncalculable"
            },
            ...getRecentItems().filter(r => r.kind !== kind || r.index !== index)
        ].slice(0, MAX_RECENTS);

        localStorage.setItem(RECENT_STORAGE_KEY, JSON.stringify(updated));
    } catch (e) {
        console.error("Failed to save recent item:", e);
    }
}

export function clearRecentItems() {
    try {
        localStorage.removeItem(RECENT_STORAGE_KEY);
    } catch (e) {
        console.error("Failed to clear recent items:", e);
    }
}