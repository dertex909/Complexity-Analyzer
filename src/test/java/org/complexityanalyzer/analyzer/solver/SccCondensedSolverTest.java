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

package org.complexityanalyzer.analyzer.solver;

import org.complexityanalyzer.MinecraftBootstrap;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.resource.SourceManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class SccCondensedSolverTest {

    @BeforeAll
    static void initMinecraft() {
        MinecraftBootstrap.init();
    }

    @Test
    @DisplayName("Solve empty graph returns valid empty result")
    void testSolveEmptyGraph() {
        RecipeGraph graph = new RecipeGraph();
        SourceManager sourceManager = Mockito.mock(SourceManager.class);

        SccCondensedSolver solver = new SccCondensedSolver(graph, sourceManager, null);
        SolverResult result = solver.solve();

        assertThat(result).isNotNull();
        assertThat(result.converged()).isTrue();
        assertThat(result.optimalComplexities()).isEmpty();
    }
}