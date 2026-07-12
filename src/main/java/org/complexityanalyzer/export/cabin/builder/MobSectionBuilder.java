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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.export.cabin.builder;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.analyzer.resource.providers.MobPropertyProvider;
import org.complexityanalyzer.analyzer.resource.sources.MobDropSource;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.export.cabin.api.CabinFormat;
import org.complexityanalyzer.export.cabin.api.LeBuf;

public final class MobSectionBuilder {

    private final SectionBuilderContext ctx;

    public MobSectionBuilder(SectionBuilderContext ctx) {
        this.ctx = ctx;
    }

    private static String safeDisplayName(Item item) {
        try {
            return item.getDescription().getString();
        } catch (Throwable t) {
            var id = GameRegistryManager.getItemId(item);
            return id != null ? id.toString() : "unknown";
        }
    }

    public MobsResult buildMobs(MobPropertyProvider mobProvider, MobDropSource mobDropSource) {
        int n = ctx.orderedMobs().size();
        var mobsBuf = new LeBuf(CabinFormat.MOB_RECORD_SIZE * n + 4);
        var dropsBuf = new LeBuf(64 * 1024);
        dropsBuf.i32(0);
        int totalDrops = 0;
        mobsBuf.i32(n);
        for (int i = 0; i < n; i++) {
            var type = ctx.orderedMobs().get(i);
            var id = GameRegistryManager.getEntityTypeId(type);
            String idStr = id != null ? id.toString() : "minecraft:unknown";
            String displayName = type.getDescription().getString();
            String catName = type.getCategory().getName();

            var props = (mobProvider != null) ? mobProvider.getProperties(type) : null;
            double health = props != null ? props.maxHealth() : 0.0;
            double damage = props != null ? props.attackDamage() : 0.0;
            double armor = props != null ? props.armor() : 0.0;
            double survivability = props != null ? props.calculateSurvivability() : 0.0;
            double threat = props != null ? props.calculateThreat() : 0.0;
            double combat = props != null ? props.calculateCombatPower() : 0.0;
            double rarity = (mobProvider != null) ? mobProvider.getRarity(type) : 1.0;
            boolean boss = mobProvider != null && mobProvider.isBoss(type);
            boolean miniBoss = mobProvider != null && mobProvider.isMiniBoss(type);

            int dropOffset = CabinFormat.NULL_OFFSET;
            int dropCount = 0;
            if (mobDropSource != null) {
                var drops = mobDropSource.getDropsForEntity(type);
                if (!drops.isEmpty()) {
                    dropOffset = dropsBuf.position();
                    int written = 0;
                    for (var drop : drops) {
                        if (written == 0xFFFF) break;
                        var it = drop.item();
                        int itIdx = ctx.itemIndex().getInt(it);
                        var itId = it != null ? GameRegistryManager.getItemId(it) : null;
                        String itIdStr = itId != null ? itId.toString() : "minecraft:air";
                        String itName = it != null ? safeDisplayName(it) : "";
                        dropsBuf.i32(itIdx);
                        dropsBuf.i32(ctx.strings().intern(itName));
                        dropsBuf.f64(drop.averageYield());
                        dropsBuf.i32(ctx.strings().intern(drop.killMethod() != null ? drop.killMethod() : ""));
                        dropsBuf.i32(ctx.strings().intern(itIdStr));
                        written++;
                        totalDrops++;
                    }
                    dropCount = written;
                }
            }

            int recordStart = mobsBuf.position();
            mobsBuf.i32(ctx.strings().intern(idStr));
            mobsBuf.i32(ctx.strings().intern(displayName));
            mobsBuf.i32(ctx.strings().intern(catName));
            mobsBuf.i32(0);
            mobsBuf.f64(health);
            mobsBuf.f64(damage);
            mobsBuf.f64(armor);
            mobsBuf.f64(survivability);
            mobsBuf.f64(threat);
            mobsBuf.f64(combat);
            mobsBuf.f64(rarity);
            mobsBuf.i32(dropOffset);
            mobsBuf.u16(dropCount);
            int flags = 0;
            if (boss) flags |= CabinFormat.MOB_FLAG_IS_BOSS;
            if (miniBoss) flags |= CabinFormat.MOB_FLAG_IS_MINIBOSS;
            mobsBuf.u8(flags);
            mobsBuf.u8(type.getCategory().ordinal());
            int written = mobsBuf.position() - recordStart;
            if (written != CabinFormat.MOB_RECORD_SIZE)
                throw new IllegalStateException("MOB record size mismatch: " + written);
        }
        dropsBuf.putI32At(0, totalDrops);
        return new MobsResult(mobsBuf.toByteArray(), dropsBuf.toByteArray(), n);
    }

    public static final class MobsResult {
        public final byte[] mobs;
        public final byte[] drops;
        public final int mobCount;

        public MobsResult(byte[] mobs, byte[] drops, int mobCount) {
            this.mobs = mobs;
            this.drops = drops;
            this.mobCount = mobCount;
        }
    }
}