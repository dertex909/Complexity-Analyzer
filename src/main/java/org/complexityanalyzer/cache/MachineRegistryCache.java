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
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.cache.util.Fingerprints;
import org.complexityanalyzer.cache.util.ManagedCache;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.util.ModFileManager;

import java.nio.file.Path;

import static net.minecraft.world.item.Items.AIR;

public final class MachineRegistryCache implements ManagedCache {

    public static final MachineRegistryCache INSTANCE = new MachineRegistryCache();

    private static final int MAGIC = 0x43414332;
    private static final int VERSION = 1;

    private MachineRegistryCache() {
    }

    @Override
    public String id() {
        return "machine_registry";
    }

    @Override
    public Path file(MinecraftServer server) {
        if (server == null) return null;
        try {
            return ModFileManager.resolve(server, "machine_registry.bin");
        } catch (Throwable t) {
            return null;
        }
    }

    public Fingerprint computeFingerprint() {
        return new Fingerprint(Fingerprints.hashAllBlocks(), Fingerprints.hashMods());
    }

    public int tryLoad(Path file, Fingerprint expected, Object2ObjectMap<ResourceLocation, ObjectList<Item>> target) {
        if (!ModFileManager.isRegularFile(file)) return -1;

        ByteBuf raw = null;
        try {
            byte[] bytes = ModFileManager.readCompressedBytes(file);
            raw = Unpooled.wrappedBuffer(bytes);
            var buf = new FriendlyByteBuf(raw);

            if (buf.readInt() != MAGIC) {
                ComplexityAnalyzer.LOGGER.warn("[MachineRegistry] Cache has bad header, rebuilding.");
                return -1;
            }
            if (buf.readInt() != VERSION) {
                ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Cache format outdated, rebuilding.");
                return -1;
            }

            var stored = new Fingerprint(buf.readLong(), buf.readLong());
            if (!stored.equals(expected)) {
                String diff = (stored.blocks() != expected.blocks() ? "blocks " : "")
                        + (stored.mods() != expected.mods() ? "mods" : "");
                ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Block/mod set changed since last run ({}), cache invalidated.", diff.trim());
                return -1;
            }

            int typeCount = buf.readVarInt();
            int restored = 0;
            for (int i = 0; i < typeCount; i++) {
                var typeId = buf.readResourceLocation();
                int itemCount = buf.readVarInt();
                var list = new ObjectArrayList<Item>(itemCount);
                for (int j = 0; j < itemCount; j++) {
                    var itemId = buf.readResourceLocation();
                    var item = GameRegistryManager.getItem(itemId);
                    if (item != null && item != AIR) {
                        list.add(item);
                        restored++;
                    }
                }
                if (!list.isEmpty()) target.put(typeId, list);
            }
            return restored;
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[MachineRegistry] Failed to load cache (rebuilding): {}", t.toString());
            return -1;
        } finally {
            if (raw != null) raw.release();
        }
    }

    public void save(Path file, Fingerprint fingerprint, Object2ObjectMap<ResourceLocation, ObjectList<Item>> mapping) {
        if (file == null) return;
        var raw = Unpooled.buffer();
        try {
            var buf = new FriendlyByteBuf(raw);
            buf.writeInt(MAGIC);
            buf.writeInt(VERSION);
            buf.writeLong(fingerprint.blocks());
            buf.writeLong(fingerprint.mods());
            buf.writeVarInt(mapping.size());
            for (var entry : mapping.object2ObjectEntrySet()) {
                buf.writeResourceLocation(entry.getKey());
                var items = entry.getValue();
                buf.writeVarInt(items.size());
                for (var item : items) {
                    var id = GameRegistryManager.getItemId(item);
                    buf.writeResourceLocation(id != null ? id : ResourceLocation.withDefaultNamespace("air"));
                }
            }

            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);

            ModFileManager.writeCompressedAtomic(file, bytes, 5);
            ComplexityAnalyzer.LOGGER.debug("[MachineRegistry] Saved compressed cache: {} recipe types -> {}", mapping.size(), file);
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[MachineRegistry] Failed to save cache: {}", t.toString());
        } finally {
            raw.release();
        }
    }

    public record Fingerprint(long blocks, long mods) {
    }
}