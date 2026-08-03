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

package org.complexityanalyzer.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ComplexityCategoryTest {

    @Test
    @DisplayName("Map negative values to UNCALCULABLE")
    void testUncalculableForNegative() {
        assertThat(ComplexityCategory.fromComplexity(-1.0)).isEqualTo(ComplexityCategory.UNCALCULABLE);
        assertThat(ComplexityCategory.fromComplexity(-100.0)).isEqualTo(ComplexityCategory.UNCALCULABLE);
    }

    @Test
    @DisplayName("Map zero to ABSOLUTE")
    void testAbsoluteForZero() {
        assertThat(ComplexityCategory.fromComplexity(0.0)).isEqualTo(ComplexityCategory.ABSOLUTE);
    }

    @Test
    @DisplayName("Map infinity to UNOBTAINABLE")
    void testUnobtainableForInfinity() {
        assertThat(ComplexityCategory.fromComplexity(Double.POSITIVE_INFINITY)).isEqualTo(ComplexityCategory.UNOBTAINABLE);
    }

    @ParameterizedTest
    @CsvSource({
            "5.0, TRIVIAL",
            "10.0, TRIVIAL",
            "50.0, SIMPLE",
            "100.0, SIMPLE",
            "500.0, MODERATE",
            "1000.0, MODERATE",
            "5000.0, COMPLEX",
            "50000.0, DIFFICULT",
            "500000.0, EXPERT",
            "5000000.0, MASTER",
            "50000000.0, MYTHICAL",
            "500000000.0, TRANSCENDENT",
            "2000000000.0, UNOBTAINABLE"
    })
    @DisplayName("Map complexity values to correct categories")
    void testComplexityThresholds(double complexity, ComplexityCategory expectedCategory) {
        assertThat(ComplexityCategory.fromComplexity(complexity)).isEqualTo(expectedCategory);
    }

    @Test
    @DisplayName("Category properties and translation keys")
    void testCategoryProperties() {
        ComplexityCategory category = ComplexityCategory.SIMPLE;

        assertThat(category.getDisplayName()).isEqualTo("Simple");
        assertThat(category.getTranslationKey()).isEqualTo("complexityanalyzer.category.simple");
        assertThat(category.getColor()).isNotNull();
    }
}