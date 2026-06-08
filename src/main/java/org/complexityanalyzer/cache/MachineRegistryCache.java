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
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
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
import org.complexityanalyzer.core.GameRegistryManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class MachineRegistryCache implements ManagedCache {

    public static final MachineRegistryCache INSTANCE = new MachineRegistryCache();

    private static final int MAGIC = 0x43414332; // "CAC2"
    private static final int VERSION = 1;

    public record Fingerprint(long blocks, long mods) {
    }

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
            return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("complexityanalyzer").resolve("machine_registry.bin");
        } catch (Throwable t) {
            return null;
        }
    }

    public Fingerprint computeFingerprint() {
        var blockIds = new ObjectArrayList<String>();
        for (var block : GameRegistryManager.getAllBlocks()) {
            var id = GameRegistryManager.getBlockId(block);
            blockIds.add(id != null ? id.toString() : "?");
        }
        blockIds.sort(null);
        long hBlocks = 0xcbf29ce484222325L;
        for (var id : blockIds) hBlocks = fnv(hBlocks, id);

        var modKeys = new ObjectArrayList<String>();
        for (var mod : ModList.get().getMods()) modKeys.add(mod.getModId() + "@" + mod.getVersion());
        modKeys.sort(null);
        long hMods = 0xcbf29ce484222325L;
        for (var key : modKeys) hMods = fnv(hMods, key);

        return new Fingerprint(hBlocks, hMods);
    }

    public int tryLoad(Path file, Fingerprint expected, Object2ObjectMap<ResourceLocation, ObjectList<Item>> target) {
        if (file == null || !Files.isRegularFile(file)) return -1;

        ByteBuf raw = null;
        try {
            byte[] bytes = Files.readAllBytes(file);
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
                    if (item != null && item != Items.AIR) {
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
        ByteBuf raw = Unpooled.buffer();
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

            Files.createDirectories(file.getParent());
            var tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Saved cache: {} recipe types -> {}", mapping.size(), file);
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[MachineRegistry] Failed to save cache: {}", t.toString());
        } finally {
            raw.release();
        }
    }

    private static long fnv(long h, String s) {
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}