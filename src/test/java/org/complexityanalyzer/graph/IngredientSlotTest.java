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

package org.complexityanalyzer.graph;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.complexityanalyzer.MinecraftBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngredientSlotTest {

    @BeforeAll
    static void initMinecraft() {
        MinecraftBootstrap.init();
    }

    @Test
    @DisplayName("Create slot with empty variants")
    void testEmptySlot() {
        IngredientSlot slot = new IngredientSlot(new ObjectArrayList<>(), 1);

        assertThat((List<ItemStack>) slot.getVariants()).isEmpty();
        assertThat(slot.getCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Normalize count to at least 1")
    void testCountNormalization() {
        IngredientSlot slot = new IngredientSlot(new ObjectArrayList<>(), -5);

        assertThat(slot.getCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Intern duplicate non-empty slots to same reference")
    void testInterning() {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        IngredientSlot slot1 = new IngredientSlot(ObjectArrayList.of(stack), 1);
        IngredientSlot slot2 = new IngredientSlot(ObjectArrayList.of(stack), 1);

        IngredientSlot interned1 = IngredientSlot.intern(slot1);
        IngredientSlot interned2 = IngredientSlot.intern(slot2);

        assertThat(interned1).isSameAs(interned2);
    }
}