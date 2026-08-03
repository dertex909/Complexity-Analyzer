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

import net.minecraft.world.item.Item;
import org.complexityanalyzer.MinecraftBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeGraphTest {

    private RecipeGraph graph;
    private Item mockItemA;

    @BeforeAll
    static void initMinecraft() {
        MinecraftBootstrap.init();
    }

    @BeforeEach
    void setUp() {
        graph = new RecipeGraph();
        mockItemA = Mockito.mock(Item.class);
    }

    @Test
    @DisplayName("Initial graph state is empty")
    void testInitialState() {
        assertThat(graph.getTotalRecipeCount()).isEqualTo(0);
        assertThat((List<RecipeNode>) graph.getAllRecipes()).isEmpty();
        assertThat(graph.hasRecipe(mockItemA)).isFalse();
    }

    @Test
    @DisplayName("Add recipe and query by item")
    void testAddRecipeAndQuery() {
        RecipeNode.Builder builder = new RecipeNode.Builder(mockItemA).resultCount(1);
        builder.priority(1);
        RecipeNode node = builder.build();

        graph.addRecipe(node);

        assertThat(graph.getTotalRecipeCount()).isEqualTo(1);
        assertThat(graph.hasRecipe(mockItemA)).isTrue();
        assertThat((List<RecipeNode>) graph.getRecipes(mockItemA)).containsExactly(node);
        assertThat(graph.getBestRecipe(mockItemA)).isEqualTo(node);
    }

    @Test
    @DisplayName("Select best recipe according to priority")
    void testBestRecipePriority() {
        RecipeNode.Builder lowBuilder = new RecipeNode.Builder(mockItemA).resultCount(1);
        lowBuilder.priority(1);
        RecipeNode lowPriorityNode = lowBuilder.build();

        RecipeNode.Builder highBuilder = new RecipeNode.Builder(mockItemA).resultCount(2);
        highBuilder.priority(10);
        RecipeNode highPriorityNode = highBuilder.build();

        graph.addRecipe(lowPriorityNode);
        graph.addRecipe(highPriorityNode);

        assertThat((List<RecipeNode>) graph.getRecipes(mockItemA)).hasSize(2);
        assertThat(graph.getBestRecipe(mockItemA)).isEqualTo(highPriorityNode);
    }

    @Test
    @DisplayName("Clear recipe graph")
    void testClearGraph() {
        RecipeNode node = new RecipeNode.Builder(mockItemA).resultCount(1).build();
        graph.addRecipe(node);

        graph.clear();

        assertThat(graph.getTotalRecipeCount()).isEqualTo(0);
        assertThat(graph.hasRecipe(mockItemA)).isFalse();
        assertThat((List<RecipeNode>) graph.getAllRecipes()).isEmpty();
    }
}