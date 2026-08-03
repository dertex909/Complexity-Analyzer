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

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalTypeRegistryTest {

    @Test
    @DisplayName("Identify primitive and standard Java types as terminal")
    void testStandardTerminalTypes() {
        assertThat(TerminalTypeRegistry.isTerminalType(int.class)).isTrue();
        assertThat(TerminalTypeRegistry.isTerminalType(String.class)).isTrue();
        assertThat(TerminalTypeRegistry.isTerminalType(Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("Identify Minecraft terminal types")
    void testMinecraftTerminalTypes() {
        assertThat(TerminalTypeRegistry.isTerminalType(ResourceLocation.class)).isTrue();
        assertThat(TerminalTypeRegistry.isTerminalType(Item.class)).isTrue();
    }

    @Test
    @DisplayName("Non-terminal complex classes")
    void testNonTerminalTypes() {
        assertThat(TerminalTypeRegistry.isTerminalType(ItemStack.class)).isFalse();
    }
}