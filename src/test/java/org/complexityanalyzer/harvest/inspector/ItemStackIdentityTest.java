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

package org.complexityanalyzer.harvest.inspector;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.complexityanalyzer.MinecraftBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ItemStackIdentityTest {

    @BeforeAll
    static void initMinecraft() {
        MinecraftBootstrap.init();
    }

    @Test
    @DisplayName("Same item and empty component data matching")
    void testSameItemData() {
        ItemStack stack1 = new ItemStack(Items.DIAMOND, 1);
        ItemStack stack2 = new ItemStack(Items.DIAMOND, 1);

        assertThat(ItemStackIdentity.sameItemData(stack1, stack2)).isTrue();
        assertThat(ItemStackIdentity.sameItemDataAndCount(stack1, stack2)).isTrue();
    }

    @Test
    @DisplayName("Different items return false")
    void testDifferentItems() {
        ItemStack diamond = new ItemStack(Items.DIAMOND, 1);
        ItemStack gold = new ItemStack(Items.GOLD_INGOT, 1);

        assertThat(ItemStackIdentity.sameItemData(diamond, gold)).isFalse();
    }

    @Test
    @DisplayName("Same item with different counts")
    void testDifferentCounts() {
        ItemStack stack1 = new ItemStack(Items.DIAMOND, 1);
        ItemStack stack2 = new ItemStack(Items.DIAMOND, 64);

        assertThat(ItemStackIdentity.sameItemData(stack1, stack2)).isTrue();
        assertThat(ItemStackIdentity.sameItemDataAndCount(stack1, stack2)).isFalse();
    }
}