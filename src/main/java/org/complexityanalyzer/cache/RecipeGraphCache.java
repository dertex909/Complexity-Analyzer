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
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

public final class RecipeGraphCache implements ManagedCache {

    public static final RecipeGraphCache INSTANCE = new RecipeGraphCache();

    private static final int MAGIC = 0x43414331;
    private static final int VERSION = 3;

    public record Fingerprint(long recipes, long mods, long config) {
    }

    private static final ResourceLocation AIR_ID = ResourceLocation.withDefaultNamespace("air");
    private static final ResourceLocation EMPTY_FLUID_ID = ResourceLocation.withDefaultNamespace("empty");

    private RecipeGraphCache() {
    }

    @Override
    public String id() {
        return "recipe_graph";
    }

    @Override
    public Path file(MinecraftServer server) {
        if (server == null) return null;
        try {
            return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("complexityanalyzer").resolve("recipe_graph.bin");
        } catch (Throwable t) {
            return null;
        }
    }

    public Fingerprint computeFingerprint(RecipeManager recipeManager, RegistryAccess registryAccess) {
        var holders = new ObjectArrayList<>(recipeManager.getRecipes());
        holders.sort(Comparator.comparing(RecipeHolder::id));

        long hRecipes = 0xcbf29ce484222325L;
        for (var holder : holders) {
            try {
                var resultStack = holder.value().getResultItem(registryAccess);
                if (resultStack.isEmpty()) continue;
                hRecipes = fnv(hRecipes, holder.id().toString());
                var typeId = GameRegistryManager.getRecipeTypeId(holder.value().getType());
                hRecipes = fnv(hRecipes, typeId != null ? typeId.toString() : "?");
                hRecipes = fnv(hRecipes, "->" + itemId(resultStack.getItem()).toString() + "x" + resultStack.getCount());
                for (var ingredient : holder.value().getIngredients()) {
                    if (ingredient.isEmpty()) continue;
                    hRecipes = fnv(hRecipes, "[");
                    var itemKeys = new ObjectArrayList<String>();
                    for (var stack : ingredient.getItems()) {
                        if (stack.isEmpty()) continue;
                        itemKeys.add(itemId(stack.getItem()).toString() + "x" + stack.getCount());
                    }
                    itemKeys.sort(null);
                    for (var key : itemKeys) hRecipes = fnv(hRecipes, key);
                    hRecipes = fnv(hRecipes, "]");
                }
            } catch (Throwable t) {
                hRecipes = fnv(hRecipes, "fail");
            }
        }

        var modKeys = new ObjectArrayList<String>();
        for (var mod : ModList.get().getMods()) modKeys.add(mod.getModId() + "@" + mod.getVersion());
        modKeys.sort(null);
        long hMods = 0xcbf29ce484222325L;
        for (var key : modKeys) hMods = fnv(hMods, key);

        long hConfig = 0xcbf29ce484222325L;
        hConfig = fnv(hConfig, "maxIngredientVariants=" + ComplexityConfig.MAX_INGREDIENT_VARIANTS.get());
        hConfig = fnv(hConfig, "detectionSampleSize=" + ComplexityConfig.DETECTION_SAMPLE_SIZE.get());

        return new Fingerprint(hRecipes, hMods, hConfig);
    }

    private static long fnv(long h, String s) {
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    public void save(RecipeGraph graph, Path file, Fingerprint fingerprint, Level level) {
        var nodes = graph.getAllRecipes();
        var raw = Unpooled.buffer();
        try {
            var buf = new RegistryFriendlyByteBuf(raw, level.registryAccess(), ConnectionType.NEOFORGE);
            buf.writeInt(MAGIC);
            buf.writeInt(VERSION);
            buf.writeLong(fingerprint.recipes());
            buf.writeLong(fingerprint.mods());
            buf.writeLong(fingerprint.config());
            buf.writeVarInt(nodes.size());
            for (var node : nodes) writeNode(buf, node);

            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);

            Files.createDirectories(file.getParent());
            var tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            ComplexityAnalyzer.LOGGER.info("[Harvest] Saved recipe graph cache: {} recipes -> {}", nodes.size(), file);
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[Harvest] Failed to save recipe graph cache: {}", t.toString());
        } finally {
            raw.release();
        }
    }

    public RecipeGraph tryLoad(Path file, Fingerprint expected, Level level) {
        if (!Files.isRegularFile(file)) return null;

        ByteBuf raw = null;
        try {
            byte[] bytes = Files.readAllBytes(file);
            raw = Unpooled.wrappedBuffer(bytes);
            var buf = new RegistryFriendlyByteBuf(raw, level.registryAccess(), ConnectionType.NEOFORGE);

            if (buf.readInt() != MAGIC) {
                ComplexityAnalyzer.LOGGER.warn("[Harvest] Recipe graph cache has bad header, rebuilding.");
                return null;
            }
            if (buf.readInt() != VERSION) {
                ComplexityAnalyzer.LOGGER.info("[Harvest] Recipe graph cache format outdated, rebuilding.");
                return null;
            }

            var stored = new Fingerprint(buf.readLong(), buf.readLong(), buf.readLong());
            if (!stored.equals(expected)) {
                String diff = (stored.recipes() != expected.recipes() ? "recipes " : "")
                        + (stored.mods() != expected.mods() ? "mods " : "")
                        + (stored.config() != expected.config() ? "config" : "");
                ComplexityAnalyzer.LOGGER.info("[Harvest] Recipe set changed since last run ({}), cache invalidated.", diff.trim());
                return null;
            }

            int count = buf.readVarInt();
            var graph = new RecipeGraph();
            for (int i = 0; i < count; i++) graph.addRecipe(readNode(buf));
            return graph;
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[Harvest] Failed to load recipe graph cache (rebuilding): {}", t.toString());
            return null;
        } finally {
            if (raw != null) raw.release();
        }
    }

    private static void writeNode(RegistryFriendlyByteBuf buf, RecipeNode node) {
        buf.writeResourceLocation(itemId(node.getResultItem()));

        var typeId = node.getRecipeType() != null ? GameRegistryManager.getRecipeTypeId(node.getRecipeType()) : null;
        buf.writeBoolean(typeId != null);
        if (typeId != null) buf.writeResourceLocation(typeId);

        buf.writeEnum(node.getCategory());
        buf.writeVarInt(node.getResultCount());
        buf.writeVarInt(node.getPriority());
        buf.writeBoolean(node.isPlaceholder());
        buf.writeUtf(node.getPlaceholderId() != null ? node.getPlaceholderId() : "");

        var ingredients = node.getIngredients();
        buf.writeVarInt(ingredients.size());
        for (var slot : ingredients) {
            buf.writeVarInt(slot.getCount());
            var variants = slot.getVariants();
            buf.writeVarInt(variants.size());
            for (var stack : variants) writeItemStack(buf, stack);
        }

        var fluidIngredients = node.getFluidIngredients();
        buf.writeVarInt(fluidIngredients.size());
        for (var slot : fluidIngredients) {
            buf.writeVarInt(slot.getAmount());
            var variants = slot.getFluidVariants();
            buf.writeVarInt(variants.size());
            for (var fluid : variants) buf.writeResourceLocation(fluidId(fluid));
        }

        var chemicalIngredients = node.getChemicalIngredients();
        buf.writeVarInt(chemicalIngredients.size());
        for (var ci : chemicalIngredients) {
            buf.writeResourceLocation(ci.id());
            buf.writeVarInt(ci.amount());
        }

        var chemicalOutputs = node.getChemicalOutputs();
        buf.writeVarInt(chemicalOutputs.size());
        for (var co : chemicalOutputs) {
            buf.writeResourceLocation(co.id());
            buf.writeVarLong(co.amount());
        }

        var itemOutputs = node.getItemOutputs();
        buf.writeVarInt(itemOutputs.size());
        for (var stack : itemOutputs) writeItemStack(buf, stack);

        var fluidOutputs = node.getFluidOutputs();
        buf.writeVarInt(fluidOutputs.size());
        for (var fluidStack : fluidOutputs) writeFluidStack(buf, fluidStack);
    }

    private static RecipeNode readNode(RegistryFriendlyByteBuf buf) {
        var result = GameRegistryManager.getItem(buf.readResourceLocation());
        if (result == null) result = Items.AIR;

        var builder = new RecipeNode.Builder(result);

        if (buf.readBoolean()) builder.recipeType(GameRegistryManager.getRecipeType(buf.readResourceLocation()));

        builder.category(buf.readEnum(RecipeCategory.class));
        builder.resultCount(buf.readVarInt());
        builder.priority(buf.readVarInt());
        builder.isPlaceholder(buf.readBoolean());
        builder.placeholderId(buf.readUtf());

        int ingredientCount = buf.readVarInt();
        for (int i = 0; i < ingredientCount; i++) {
            int count = buf.readVarInt();
            int variantCount = buf.readVarInt();
            var variants = new ObjectArrayList<ItemStack>(variantCount);
            for (int v = 0; v < variantCount; v++) variants.add(readItemStack(buf));
            builder.addIngredient(variants, count);
        }

        int fluidIngredientCount = buf.readVarInt();
        for (int i = 0; i < fluidIngredientCount; i++) {
            int amount = buf.readVarInt();
            int variantCount = buf.readVarInt();
            var variants = new ObjectArrayList<Fluid>(variantCount);
            for (int v = 0; v < variantCount; v++) {
                var fluid = GameRegistryManager.getFluid(buf.readResourceLocation());
                if (fluid != null) variants.add(fluid);
            }
            builder.addFluidIngredient(variants, amount);
        }

        int chemicalIngredientCount = buf.readVarInt();
        for (int i = 0; i < chemicalIngredientCount; i++) {
            var id = buf.readResourceLocation();
            builder.addChemicalIngredient(new RecipeNode.ChemicalIngredient(id, buf.readVarInt()));
        }

        int chemicalOutputCount = buf.readVarInt();
        for (int i = 0; i < chemicalOutputCount; i++) {
            var id = buf.readResourceLocation();
            builder.addChemicalOutput(new RecipeNode.ChemicalOutput(id, buf.readVarLong()));
        }

        int itemOutputCount = buf.readVarInt();
        if (itemOutputCount > 0) {
            var outputs = new ObjectArrayList<ItemStack>(itemOutputCount);
            for (int i = 0; i < itemOutputCount; i++) outputs.add(readItemStack(buf));
            builder.itemOutputs(outputs);
        }

        int fluidOutputCount = buf.readVarInt();
        if (fluidOutputCount > 0) {
            var outputs = new ObjectArrayList<FluidStack>(fluidOutputCount);
            for (int i = 0; i < fluidOutputCount; i++) outputs.add(readFluidStack(buf));
            builder.fluidOutputs(outputs);
        }

        return builder.build();
    }

    private static void writeItemStack(RegistryFriendlyByteBuf buf, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            buf.writeBoolean(false);
        } else {
            buf.writeBoolean(true);
            try {
                var tag = stack.save(buf.registryAccess());
                buf.writeNbt(tag);
            } catch (Throwable t) {
                buf.writeNbt(null);
            }
        }
    }

    private static ItemStack readItemStack(RegistryFriendlyByteBuf buf) {
        if (!buf.readBoolean()) return ItemStack.EMPTY;
        try {
            var tag = buf.readNbt();
            if (tag != null) return ItemStack.parse(buf.registryAccess(), tag).orElse(ItemStack.EMPTY);
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("Failed to load item stack from cache!", t);
        }
        return ItemStack.EMPTY;
    }

    private static void writeFluidStack(RegistryFriendlyByteBuf buf, FluidStack stack) {
        if (stack == null || stack.isEmpty()) {
            buf.writeBoolean(false);
        } else {
            buf.writeBoolean(true);
            buf.writeResourceLocation(fluidId(stack.getFluid()));
            buf.writeVarInt(stack.getAmount());
        }
    }

    private static FluidStack readFluidStack(RegistryFriendlyByteBuf buf) {
        if (!buf.readBoolean()) return FluidStack.EMPTY;
        var id = buf.readResourceLocation();
        int amount = buf.readVarInt();
        var fluid = GameRegistryManager.getFluid(id);
        if (fluid != null) return new FluidStack(fluid, amount);
        return FluidStack.EMPTY;
    }

    private static ResourceLocation itemId(Item item) {
        var id = GameRegistryManager.getItemId(item);
        return id != null ? id : AIR_ID;
    }

    private static ResourceLocation fluidId(Fluid fluid) {
        var id = GameRegistryManager.getFluidId(fluid);
        return id != null ? id : EMPTY_FLUID_ID;
    }
}