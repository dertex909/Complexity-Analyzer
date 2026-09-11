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

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.complexityanalyzer.export.format.ExportData.ItemData;
import org.complexityanalyzer.export.format.ExportData.MobData;

import java.util.StringJoiner;

public class CsvExportFormat implements IExportFormat {

    @Override
    public String getId() {
        return "csv";
    }

    @Override
    public String getFileExtension() {
        return "csv";
    }

    @Override
    public String formatItems(ExportData data) {
        return buildItemsCsv(data.items());
    }

    @Override
    public String formatSingleItem(ItemData item) {
        var list = new ObjectArrayList<ItemData>(1);
        list.add(item);
        return buildItemsCsv(list);
    }

    @Override
    public String formatMobs(ObjectList<MobData> mobs) {
        return buildMobsCsv(mobs);
    }

    @Override
    public String formatSingleMob(MobData mob) {
        var list = new ObjectArrayList<MobData>(1);
        list.add(mob);
        return buildMobsCsv(list);
    }

    private String buildItemsCsv(ObjectList<ItemData> items) {
        var sb = new StringBuilder(items.size() * 128);
        sb.append("Item ID,Display Name,Complexity,Category,Has Recipe,Crafting Depth,Used In Recipes,Is Valid,Is Hardcoded\n");
        for (var item : items) {
            sb.append("\"%s\",\"%s\",%.2f,\"%s\",%b,%d,%d,%b,%b\n".formatted(
                    item.itemId(),
                    item.displayName().replace("\"", "\"\""),
                    item.complexity(),
                    item.category().replace("\"", "\"\""),
                    item.hasRecipe(),
                    item.craftingDepth(),
                    item.usedInRecipes(),
                    item.isValid(),
                    item.isHardcoded()
            ));
        }
        return sb.toString();
    }

    private String buildMobsCsv(ObjectList<MobData> mobDataList) {
        var sb = new StringBuilder(mobDataList.size() * 256);
        sb.append("Name,ID,Category,Health,Damage,Armor,Survivability,Threat,Combat Power,Rarity,Is Boss,Is MiniBoss,Notable Drops\n");

        for (var data : mobDataList) {
            var drops = data.drops();
            String dropsStr;

            if (drops.isEmpty()) {
                dropsStr = "None";
            } else {
                var dropJoiner = new StringJoiner("; ");
                for (var d : drops) dropJoiner.add("%s (%.2f)".formatted(d.itemName(), d.yieldPerKill()));
                dropsStr = dropJoiner.toString();
            }

            sb.append("\"%s\",\"%s\",\"%s\",%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%b,%b,\"%s\"\n".formatted(
                    data.name().replace("\"", "\"\""),
                    data.id(),
                    data.category(),
                    data.health(),
                    data.damage(),
                    data.armor(),
                    data.survivability(),
                    data.threat(),
                    data.combatPower(),
                    data.rarity(),
                    data.isBoss(),
                    data.isMiniBoss(),
                    dropsStr.replace("\"", "\"\"")
            ));
        }
        return sb.toString();
    }
}