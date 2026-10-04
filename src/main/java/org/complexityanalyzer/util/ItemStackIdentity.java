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

package org.complexityanalyzer.util;

import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public final class ItemStackIdentity {

    private ItemStackIdentity() {
    }

    public static boolean sameItemData(@Nullable ItemStack a, @Nullable ItemStack b) {
        if (a == b) return true;
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        return ItemStack.isSameItemSameComponents(a, b);
    }

    public static boolean sameItemDataAndCount(@Nullable ItemStack a, @Nullable ItemStack b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return ItemStack.matches(a, b);
    }

    public static boolean hasStackData(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return !stack.isComponentsPatchEmpty();
    }

    public static int hashItemData(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        return ItemStack.hashItemAndComponents(stack);
    }

    public static int hashItemDataAndCount(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        return 31 * hashItemData(stack) + stack.getCount();
    }

    public static String dataKey(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        return "item=" + stack.getItem() + ";components=" + stack.getComponentsPatch();
    }
}