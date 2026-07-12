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

package org.complexityanalyzer.data;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.complexityanalyzer.graph.RecipeNode;

public class CraftingTreeData {

    private final Item rootItem;
    private final TreeNode root;
    private final Reference2DoubleMap<Item> baseResources;
    private final TreeStatistics statistics;
    private final DisplayMode displayMode;
    private final int maxDepth;

    public CraftingTreeData(Item rootItem, TreeNode root, Reference2DoubleMap<Item> baseResources,
                            TreeStatistics statistics, DisplayMode displayMode, int maxDepth) {
        this.rootItem = rootItem;
        this.root = root;
        this.baseResources = Reference2DoubleMaps.unmodifiable(new Reference2DoubleOpenHashMap<>(baseResources));
        this.statistics = statistics;
        this.displayMode = displayMode;
        this.maxDepth = maxDepth;
    }

    public Item getRootItem() {
        return rootItem;
    }

    public TreeNode getRoot() {
        return root;
    }

    public Reference2DoubleMap<Item> getBaseResources() {
        return baseResources;
    }

    public TreeStatistics getStatistics() {
        return statistics;
    }

    public DisplayMode getDisplayMode() {
        return displayMode;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public enum DisplayMode {
        PLAYER_INSTRUCTION,
        ECONOMIC_COST
    }

    public enum NodeType {
        CRAFTING,
        BASE_RESOURCE,
        CYCLE,
        MAX_DEPTH_REACHED,
        NO_DATA
    }

    public static class TreeNode {
        private final NodeType type;
        private final Item item;
        private final ItemStack itemStack;
        private final Component itemName;
        private final double neededAmount;
        private final double complexity;
        private final RecipeNode recipe;
        private final Component machineType;
        private final ObjectList<TreeNode> itemChildren;
        private final ObjectList<FluidNode> fluidChildren;
        private final Object2ObjectMap<String, Object> metadata;

        private TreeNode(Builder builder) {
            this.type = builder.type;
            this.item = builder.item;
            this.itemStack = builder.itemStack.copy();
            this.itemName = builder.itemName;
            this.neededAmount = builder.neededAmount;
            this.complexity = builder.complexity;
            this.recipe = builder.recipe;
            this.machineType = builder.machineType;
            this.itemChildren = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.itemChildren));
            this.fluidChildren = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.fluidChildren));
            this.metadata = Object2ObjectMaps.unmodifiable(new Object2ObjectOpenHashMap<>(builder.metadata));
        }

        public static Builder builder() {
            return new Builder();
        }

        public NodeType getType() {
            return type;
        }

        public Item getItem() {
            return item;
        }

        public ItemStack getItemStack() {
            return itemStack.copy();
        }

        public Component getItemName() {
            return itemName;
        }

        public double getNeededAmount() {
            return neededAmount;
        }

        public double getComplexity() {
            return complexity;
        }

        public RecipeNode getRecipe() {
            return recipe;
        }

        public Component getMachineType() {
            return machineType;
        }

        public ObjectList<TreeNode> getItemChildren() {
            return itemChildren;
        }

        public ObjectList<FluidNode> getFluidChildren() {
            return fluidChildren;
        }

        public Object2ObjectMap<String, Object> getMetadata() {
            return metadata;
        }

        public static class Builder {
            private final ObjectList<TreeNode> itemChildren = new ObjectArrayList<>();
            private final ObjectList<FluidNode> fluidChildren = new ObjectArrayList<>();
            private final Object2ObjectMap<String, Object> metadata = new Object2ObjectOpenHashMap<>();
            private NodeType type;
            private Item item;
            private ItemStack itemStack = ItemStack.EMPTY;
            private Component itemName = Component.empty();
            private double neededAmount;
            private double complexity;
            private RecipeNode recipe;
            private Component machineType = Component.empty();

            public Builder type(NodeType type) {
                this.type = type;
                return this;
            }

            public Builder item(Item item) {
                this.item = item;
                if (item != null && itemName.getString().isEmpty()) this.itemName = item.getDescription();
                return this;
            }

            public Builder itemStack(ItemStack stack) {
                if (stack == null || stack.isEmpty()) return this;
                this.itemStack = stack.copy();
                this.item = stack.getItem();
                this.itemName = stack.getHoverName();
                return this;
            }

            public Builder neededAmount(double amount) {
                this.neededAmount = amount;
                return this;
            }

            public Builder complexity(double complexity) {
                this.complexity = complexity;
                return this;
            }

            public Builder recipe(RecipeNode recipe) {
                this.recipe = recipe;
                return this;
            }

            public Builder machineType(Component machine) {
                this.machineType = machine;
                return this;
            }

            public void addItemChild(TreeNode child) {
                this.itemChildren.add(child);
            }

            public void addFluidChild(FluidNode child) {
                this.fluidChildren.add(child);
            }

            public Builder addMetadata(String key, Object value) {
                this.metadata.put(key, value);
                return this;
            }

            public TreeNode build() {
                return new TreeNode(this);
            }
        }
    }

    public static class FluidNode {
        private final Component fluidName;
        private final double amount;

        public FluidNode(Component fluidName, double amount) {
            this.fluidName = fluidName;
            this.amount = amount;
        }

        public Component getFluidName() {
            return fluidName;
        }

        public double getAmount() {
            return amount;
        }
    }

    public static class TreeStatistics {
        private int totalNodes = 0;
        private int uniqueItems = 0;
        private int craftingSteps = 0;
        private int baseResourcesCount = 0;
        private int cyclesDetected = 0;

        public void incrementTotalNodes() {
            totalNodes++;
        }

        public void incrementCraftingSteps() {
            craftingSteps++;
        }

        public void incrementBaseResources() {
            baseResourcesCount++;
        }

        public void incrementCycles() {
            cyclesDetected++;
        }

        public int getTotalNodes() {
            return totalNodes;
        }

        public int getUniqueItems() {
            return uniqueItems;
        }

        public void setUniqueItems(int count) {
            uniqueItems = count;
        }

        public int getCraftingSteps() {
            return craftingSteps;
        }

        public int getBaseResourcesCount() {
            return baseResourcesCount;
        }

        public int getCyclesDetected() {
            return cyclesDetected;
        }
    }
}
