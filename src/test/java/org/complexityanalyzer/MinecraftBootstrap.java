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

package org.complexityanalyzer;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.complexityanalyzer.config.ComplexityConfig;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.Collections;

public final class MinecraftBootstrap {

    private static boolean initialized = false;

    private MinecraftBootstrap() {
    }

    public static synchronized void init() {
        if (initialized) return;

        try (MockedStatic<LoadingModList> loadingModList = Mockito.mockStatic(LoadingModList.class)) {
            LoadingModList mockList = Mockito.mock(LoadingModList.class);
            Mockito.when(mockList.getModFiles()).thenReturn(Collections.emptyList());
            loadingModList.when(LoadingModList::get).thenReturn(mockList);

            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
        }

        stubConfigValues();

        initialized = true;
    }

    public static void stubConfigValues() {
        try {
            Field cachedValueField = ModConfigSpec.ConfigValue.class.getDeclaredField("cachedValue");
            cachedValueField.setAccessible(true);

            for (Field field : ComplexityConfig.class.getDeclaredFields()) {
                if (ModConfigSpec.ConfigValue.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    ModConfigSpec.ConfigValue<?> configValue = (ModConfigSpec.ConfigValue<?>) field.get(null);
                    if (configValue != null) {
                        cachedValueField.set(configValue, configValue.getDefault());
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[MinecraftBootstrap] Config stubbing error: " + t.getMessage());
        }
    }
}