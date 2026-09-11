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

package org.complexityanalyzer.export.format;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.complexityanalyzer.export.format.ExportData.ItemData;
import org.complexityanalyzer.export.format.ExportData.MobData;

import java.io.IOException;

public class JsonExportFormat implements IExportFormat {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .registerTypeAdapter(Double.class, new DoubleSerializer())
            .registerTypeAdapter(double.class, new DoubleSerializer())
            .create();

    @Override
    public String getId() {
        return "json";
    }

    @Override
    public String getFileExtension() {
        return "json";
    }

    @Override
    public String formatItems(ExportData data) {
        return GSON.toJson(data);
    }

    @Override
    public String formatSingleItem(ItemData item) {
        return GSON.toJson(item);
    }

    @Override
    public String formatMobs(ObjectList<MobData> mobs) {
        return GSON.toJson(mobs);
    }

    @Override
    public String formatSingleMob(MobData mob) {
        return GSON.toJson(mob);
    }

    private static class DoubleSerializer extends TypeAdapter<Double> {
        @Override
        public void write(JsonWriter out, Double value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else if (Double.isInfinite(value)) {
                out.value(value > 0 ? "Infinity" : "-Infinity");
            } else if (Double.isNaN(value)) {
                out.value("NaN");
            } else {
                out.value(value);
            }
        }

        @Override
        public Double read(JsonReader in) throws IOException {
            return switch (in.peek()) {
                case STRING -> {
                    String str = in.nextString();
                    yield switch (str) {
                        case "Infinity" -> Double.POSITIVE_INFINITY;
                        case "-Infinity" -> Double.NEGATIVE_INFINITY;
                        case "NaN" -> Double.NaN;
                        default -> Double.parseDouble(str);
                    };
                }
                case NUMBER -> in.nextDouble();
                case NULL -> {
                    in.nextNull();
                    yield null;
                }
                default -> throw new JsonSyntaxException("Expected number or string");
            };
        }
    }
}