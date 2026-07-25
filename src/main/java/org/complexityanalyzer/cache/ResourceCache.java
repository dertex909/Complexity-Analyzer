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

package org.complexityanalyzer.cache;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.cache.util.ManagedCache;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.resource.IResourceSource;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.util.ModFileManager;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

public final class ResourceCache implements ManagedCache {

    public static final ResourceCache UNIVERSAL_LOOT = new ResourceCache("universal_loot", "universal_loot.bin");
    public static final ResourceCache FARMING = new ResourceCache("farming", "farming.bin");
    public static final ResourceCache MOB_DROP = new ResourceCache("mob_drop", "mob_drop.bin");
    public static final ResourceCache BLOCK_BREAK = new ResourceCache("block_break", "block_break.bin");

    private static final int MAGIC = 0x43414331;
    private static final int VERSION = 1;

    private final String id;
    private final String fileName;

    private ResourceCache(String id, String fileName) {
        this.id = id;
        this.fileName = fileName;
    }

    private static boolean fingerprintMatches(FriendlyByteBuf buf, long[] expected) {
        int n = buf.readVarInt();
        if (n != expected.length) {
            for (int i = 0; i < n; i++) buf.readLong();
            return false;
        }
        boolean match = true;
        for (int i = 0; i < n; i++) if (buf.readLong() != expected[i]) match = false;
        return match;
    }

    public static void writeResourceData(FriendlyByteBuf buf, BaseResourceData data) {
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

    @Nullable
    public static BaseResourceData readResourceData(FriendlyByteBuf buf, Item item, IResourceSource source) {
        var typeName = buf.readUtf();
        double baseFactor = buf.readDouble();
        var specifier = buf.readUtf();
        var details = buf.readUtf();

        int siCount = buf.readVarInt();
        var sourceItems = new Reference2DoubleOpenHashMap<Item>();
        for (int k = 0; k < siCount; k++) {
            var id = buf.readResourceLocation();
            double amount = buf.readDouble();
            var si = GameRegistryManager.getItem(id);
            if (si != null && si != Items.AIR) sourceItems.put(si, amount);
        }

        var builder = item != null ? new BaseResourceData.Builder(item, source) : null;
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
            type = BaseResourceData.ResourceSourceType.UNKNOWN;
        }
        return builder.sourceType(type).baseFactor(baseFactor).sourceSpecifier(specifier).details(details).sourceItems(sourceItems).build();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    @Nullable
    public Path file(MinecraftServer server) {
        if (server == null) return null;
        try {
            return ModFileManager.resolve(server, fileName);
        } catch (Throwable t) {
            return null;
        }
    }

    public <T> int load(Path file, long[] fingerprint, Reader<T> reader, Reference2ObjectMap<Item, ObjectList<T>> target) {
        if (!ModFileManager.isRegularFile(file)) return -1;

        ByteBuf raw = null;
        try {
            raw = Unpooled.wrappedBuffer(ModFileManager.readCompressedBytes(file));
            var buf = new FriendlyByteBuf(raw);

            if (buf.readInt() != MAGIC || buf.readInt() != VERSION) {
                ComplexityAnalyzer.LOGGER.debug("[Cache:{}] Bad/outdated header, rebuilding.", id);
                return -1;
            }
            if (!fingerprintMatches(buf, fingerprint)) {
                ComplexityAnalyzer.LOGGER.info("[Cache:{}] Fingerprint changed, rebuilding.", id);
                return -1;
            }

            int itemCount = buf.readVarInt();
            int restored = 0;
            for (int i = 0; i < itemCount; i++) {
                var itemId = buf.readResourceLocation();
                var item = GameRegistryManager.getItem(itemId);
                boolean keep = item != null && item != Items.AIR;
                int n = buf.readVarInt();
                var list = keep ? new ObjectArrayList<T>(n) : null;
                for (int j = 0; j < n; j++) {
                    var element = reader.read(buf, item);
                    if (list != null && element != null) {
                        list.add(element);
                        restored++;
                    }
                }
                if (list != null && !list.isEmpty()) target.put(item, list);
            }
            return restored;
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[Cache:{}] Failed to load (rebuilding): {}", id, t.toString());
            target.clear();
            return -1;
        } finally {
            if (raw != null) raw.release();
        }
    }

    public <T> void save(Path file, long[] fingerprint, Writer<T> writer, Reference2ObjectMap<Item, ObjectList<T>> map) {
        if (file == null) return;
        var raw = Unpooled.buffer();
        try {
            var buf = new FriendlyByteBuf(raw);
            buf.writeInt(MAGIC);
            buf.writeInt(VERSION);
            buf.writeVarInt(fingerprint.length);
            for (long v : fingerprint) buf.writeLong(v);

            buf.writeVarInt(map.size());
            for (var entry : map.reference2ObjectEntrySet()) {
                var itemId = GameRegistryManager.getItemId(entry.getKey());
                buf.writeResourceLocation(itemId != null ? itemId : ResourceLocation.withDefaultNamespace("air"));
                var list = entry.getValue();
                buf.writeVarInt(list.size());
                for (var element : list) writer.write(buf, element);
            }

            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);

            ModFileManager.writeCompressedAtomic(file, bytes, 5);
            ComplexityAnalyzer.LOGGER.debug("[Cache:{}] Saved compressed {} items -> {}", id, map.size(), file);
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[Cache:{}] Failed to save: {}", id, t.toString());
        } finally {
            raw.release();
        }
    }

    @FunctionalInterface
    public interface Writer<T> {
        void write(FriendlyByteBuf buf, T element);
    }

    @FunctionalInterface
    public interface Reader<T> {
        @Nullable
        T read(FriendlyByteBuf buf, Item item);
    }
}