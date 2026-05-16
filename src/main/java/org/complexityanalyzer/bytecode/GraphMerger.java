package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.bytecode.model.SemanticEdge;
import org.complexityanalyzer.graph.*;

public final class GraphMerger {

    private GraphMerger() {
    }

    public static int mergeInto(RecipeGraph graph, ObjectList<SemanticEdge> edges) {
        var merged = new Object2ObjectOpenHashMap<String, SemanticEdge>();
        int added = 0;

        for (var edge : edges) {
            String key = edge.edgeKey();
            var existing = merged.get(key);

            if (existing != null) {
                if (edge.weight() > existing.weight()) merged.put(key, edge);
                continue;
            }

            merged.put(key, edge);

            Item fromItem = resolveItem(edge.from());
            Item toItem = resolveItem(edge.to());

            if (fromItem == Items.AIR || toItem == Items.AIR) continue;
            if (fromItem == toItem) continue;

            var builder = new RecipeNode.Builder(toItem);
            builder.category(RecipeCategory.PROCESSING);
            builder.resultCount(1);
            builder.priority((int) (edge.weight() * 100));

            var variants = new ObjectArrayList<Item>();
            variants.add(fromItem);
            builder.addIngredient(variants, 1);

            var node = builder.build();
            if (node != null) {
                graph.addRecipe(node);
                added++;
            }
        }

        ComplexityAnalyzer.LOGGER.info("[GraphMerger] Merged {} edges into RecipeGraph ({} unique, {} new nodes)",
                edges.size(), merged.size(), added);

        return added;
    }

    private static Item resolveItem(String nodeRef) {
        try {
            String id = nodeRef.replace("ITEM:", "").replace("item:", "").trim().toLowerCase();
            if (id.isEmpty()) return Items.AIR;

            if (id.contains(":")) {
                String[] parts = id.split(":");
                if (parts.length >= 2) id = parts[parts.length - 2] + ":" + parts[parts.length - 1];
            }
            if (!id.contains(":")) id = "minecraft:" + id;

            var rl = ResourceLocation.parse(id);
            return BuiltInRegistries.ITEM.get(rl);
        } catch (Exception e) {
            return Items.AIR;
        }
    }
}