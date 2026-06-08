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

package org.complexityanalyzer.cache;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.geoscan.GeoDatabase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class BlockBreakCache implements ManagedCache {

    public static final BlockBreakCache INSTANCE = new BlockBreakCache();

    private static final int MAGIC = 0x42424331;
    private static final int VERSION = 1;
    private static final int LOGIC_VERSION = 1;

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;

    public record Fingerprint(long blocks, long mods, long geo, long worldSeed, long config) {
    }

    private BlockBreakCache() {
    }

    @Override
    public String id() {
        return "block_break";
    }

    @Override
    public Path file(MinecraftServer server) {
        if (server == null) return null;
        try {
            return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("complexityanalyzer").resolve("block_break.bin");
        } catch (Throwable t) {
            return null;
        }
    }

    public Fingerprint computeFingerprint(GeoDatabase geo, long worldSeed, int sampleCount, double timeCostMultiplier) {
        var blockIds = new ObjectArrayList<String>();
        for (var block : GameRegistryManager.getAllBlocks()) {
            var id = GameRegistryManager.getBlockId(block);
            blockIds.add(id != null ? id.toString() : "?");
        }
        blockIds.sort(null);
        long hBlocks = FNV_OFFSET;
        for (var id : blockIds) hBlocks = fnv(hBlocks, id);

        var modKeys = new ObjectArrayList<String>();
        for (var mod : ModList.get().getMods()) modKeys.add(mod.getModId() + "@" + mod.getVersion());
        modKeys.sort(null);
        long hMods = FNV_OFFSET;
        for (var key : modKeys) hMods = fnv(hMods, key);

        long config = FNV_OFFSET;
        config = fnvLong(config, LOGIC_VERSION);
        config = fnvLong(config, sampleCount);
        config = fnvLong(config, Double.doubleToLongBits(timeCostMultiplier));

        return new Fingerprint(hBlocks, hMods, computeGeoHash(geo), worldSeed, config);
    }

    private static long computeGeoHash(GeoDatabase geo) {
        if (geo == null || !geo.isLoaded()) return 0L;
        long acc = 0L;
        try {
            for (var dimEntry : geo.getAllDimensionData().entrySet()) {
                var dimId = dimEntry.getKey().toString();
                for (var biomeEntry : dimEntry.getValue().entrySet()) {
                    var data = biomeEntry.getValue();
                    long h = FNV_OFFSET;
                    h = fnv(h, dimId);
                    h = fnv(h, biomeEntry.getKey().toString());
                    h = fnvLong(h, data.getTotalBlocks());
                    for (var bc : data.getInternalBlockCounts().reference2LongEntrySet()) {
                        long e = FNV_OFFSET;
                        var id = GameRegistryManager.getBlockId(bc.getKey());
                        e = fnv(e, id != null ? id.toString() : "?");
                        e = fnvLong(e, bc.getLongValue());
                        h += e;
                    }
                    acc += h;
                }
            }
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[Block Break] Failed to hash geo data: {}", t.toString());
            return 0L;
        }
        return acc;
    }

    public int tryLoad(Path file, Fingerprint expected, IResourceSource source,
                       Reference2ObjectMap<Item, ObjectList<BaseResourceData>> target) {
        if (file == null || !Files.isRegularFile(file)) return -1;

        ByteBuf raw = null;
        try {
            byte[] bytes = Files.readAllBytes(file);
            raw = Unpooled.wrappedBuffer(bytes);
            var buf = new FriendlyByteBuf(raw);

            if (buf.readInt() != MAGIC) {
                ComplexityAnalyzer.LOGGER.warn("[Block Break] Cache has bad header, rebuilding.");
                return -1;
            }
            if (buf.readInt() != VERSION) {
                ComplexityAnalyzer.LOGGER.info("[Block Break] Cache format outdated, rebuilding.");
                return -1;
            }

            var stored = new Fingerprint(buf.readLong(), buf.readLong(), buf.readLong(), buf.readLong(), buf.readLong());
            if (!stored.equals(expected)) {
                ComplexityAnalyzer.LOGGER.info("[Block Break] Cache invalidated ({}), recomputing.", diff(stored, expected));
                return -1;
            }

            int itemCount = buf.readVarInt();
            int restored = 0;
            for (int i = 0; i < itemCount; i++) {
                var itemId = buf.readResourceLocation();
                var item = GameRegistryManager.getItem(itemId);
                int pathCount = buf.readVarInt();

                ObjectList<BaseResourceData> paths = (item != null && item != Items.AIR) ? new ObjectArrayList<>(pathCount) : null;
                for (int j = 0; j < pathCount; j++) {
                    var data = readData(buf, item, source);
                    if (paths != null && data != null) {
                        paths.add(data);
                        restored++;
                    }
                }
                if (paths != null && !paths.isEmpty()) target.put(item, paths);
            }
            return restored;
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[Block Break] Failed to load cache (rebuilding): {}", t.toString());
            target.clear();
            return -1;
        } finally {
            if (raw != null) raw.release();
        }
    }

    public void save(Path file, Fingerprint fingerprint, Reference2ObjectMap<Item, ObjectList<BaseResourceData>> allPaths) {
        if (file == null) return;
        ByteBuf raw = Unpooled.buffer();
        try {
            var buf = new FriendlyByteBuf(raw);
            buf.writeInt(MAGIC);
            buf.writeInt(VERSION);
            buf.writeLong(fingerprint.blocks());
            buf.writeLong(fingerprint.mods());
            buf.writeLong(fingerprint.geo());
            buf.writeLong(fingerprint.worldSeed());
            buf.writeLong(fingerprint.config());

            buf.writeVarInt(allPaths.size());
            for (var entry : allPaths.reference2ObjectEntrySet()) {
                var itemId = GameRegistryManager.getItemId(entry.getKey());
                buf.writeResourceLocation(itemId != null ? itemId : ResourceLocation.withDefaultNamespace("air"));
                var paths = entry.getValue();
                buf.writeVarInt(paths.size());
                for (var data : paths) writeData(buf, data);
            }

            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);

            Files.createDirectories(file.getParent());
            var tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            ComplexityAnalyzer.LOGGER.info("[Block Break] Saved cache: {} items -> {}", allPaths.size(), file);
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[Block Break] Failed to save cache: {}", t.toString());
        } finally {
            raw.release();
        }
    }

    private static void writeData(FriendlyByteBuf buf, BaseResourceData data) {
        buf.writeUtf(data.getSourceType().name());
        buf.writeDouble(data.getBaseFactor());
        buf.writeUtf(data.getSourceSpecifier());
        buf.writeUtf(data.getDetails());

        var sourceItems = data.getSourceItems();
        buf.writeVarInt(sourceItems.size());
        for (var e : sourceItems.reference2DoubleEntrySet()) {
            var id = GameRegistryManager.getItemId(e.getKey());
            buf.writeResourceLocation(id != null ? id : ResourceLocation.withDefaultNamespace("air"));
            buf.writeDouble(e.getDoubleValue());
        }

        var metadata = data.getMetadata();
        buf.writeVarInt(metadata.size());
        for (var e : metadata.object2ObjectEntrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeUtf(e.getValue());
        }
    }

    private static BaseResourceData readData(FriendlyByteBuf buf, Item item, IResourceSource source) {
        var typeName = buf.readUtf();
        double baseFactor = buf.readDouble();
        var specifier = buf.readUtf();
        var details = buf.readUtf();

        int siCount = buf.readVarInt();
        var builder = item != null ? new BaseResourceData.Builder(item, source) : null;
        for (int k = 0; k < siCount; k++) {
            var id = buf.readResourceLocation();
            double amount = buf.readDouble();
            if (builder != null) {
                var si = GameRegistryManager.getItem(id);
                if (si != null && si != Items.AIR) builder.addSourceItem(si, amount);
            }
        }

        int mdCount = buf.readVarInt();
        for (int k = 0; k < mdCount; k++) {
            var key = buf.readUtf();
            var value = buf.readUtf();
            if (builder != null) builder.addMetadata(key, value);
        }

        if (builder == null) return null;
        BaseResourceData.ResourceSourceType type;
        try {
            type = BaseResourceData.ResourceSourceType.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            type = BaseResourceData.ResourceSourceType.BLOCK_TRANSFORMATION;
        }
        return builder.sourceType(type).baseFactor(baseFactor).sourceSpecifier(specifier).details(details).build();
    }

    private static String diff(Fingerprint stored, Fingerprint expected) {
        var sb = new StringBuilder();
        if (stored.blocks() != expected.blocks()) sb.append("blocks ");
        if (stored.mods() != expected.mods()) sb.append("mods ");
        if (stored.geo() != expected.geo()) sb.append("geo ");
        if (stored.worldSeed() != expected.worldSeed()) sb.append("seed ");
        if (stored.config() != expected.config()) sb.append("config ");
        return sb.toString().trim();
    }

    private static long fnv(long h, String s) {
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static long fnvLong(long h, long v) {
        h ^= v;
        h *= 0x100000001b3L;
        return h;
    }
}