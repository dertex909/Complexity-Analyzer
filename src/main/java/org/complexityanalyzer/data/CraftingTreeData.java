/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
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

import net.minecraft.world.item.Item;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.*;

public class CraftingTreeData {

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

    private final Item rootItem;
    private final TreeNode root;
    private final Map<Item, Double> baseResources;
    private final TreeStatistics statistics;
    private final DisplayMode displayMode;
    private final int maxDepth;

    public CraftingTreeData(Item rootItem, TreeNode root, Map<Item, Double> baseResources,
                            TreeStatistics statistics, DisplayMode displayMode, int maxDepth) {
        this.rootItem = rootItem;
        this.root = root;
        this.baseResources = baseResources;
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

    public Map<Item, Double> getBaseResources() {
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


    public static class TreeNode {
        private final NodeType type;
        private final Item item;
        private final String itemName;
        private final double neededAmount;
        private final double complexity;
        private final RecipeNode recipe;
        private final String machineType;
        private final List<TreeNode> itemChildren;
        private final List<FluidNode> fluidChildren;
        private final Map<String, Object> metadata;

        private TreeNode(Builder builder) {
            this.type = builder.type;
            this.item = builder.item;
            this.itemName = builder.itemName;
            this.neededAmount = builder.neededAmount;
            this.complexity = builder.complexity;
            this.recipe = builder.recipe;
            this.machineType = builder.machineType;
            this.itemChildren = new ArrayList<>(builder.itemChildren);
            this.fluidChildren = new ArrayList<>(builder.fluidChildren);
            this.metadata = new HashMap<>(builder.metadata);
        }

        public NodeType getType() {
            return type;
        }

        public Item getItem() {
            return item;
        }

        public String getItemName() {
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

        public String getMachineType() {
            return machineType;
        }

        public List<TreeNode> getItemChildren() {
            return Collections.unmodifiableList(itemChildren);
        }

        public List<FluidNode> getFluidChildren() {
            return Collections.unmodifiableList(fluidChildren);
        }

        public Map<String, Object> getMetadata() {
            return Collections.unmodifiableMap(metadata);
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private NodeType type;
            private Item item;
            private String itemName = "";
            private double neededAmount;
            private double complexity;
            private RecipeNode recipe;
            private String machineType;
            private final List<TreeNode> itemChildren = new ArrayList<>();
            private final List<FluidNode> fluidChildren = new ArrayList<>();
            private final Map<String, Object> metadata = new HashMap<>();

            public Builder type(NodeType type) {
                this.type = type;
                return this;
            }

            public Builder item(Item item) {
                this.item = item;
                if (item != null && itemName.isEmpty()) {
                    this.itemName = item.getDescription().getString();
                }
                return this;
            }

            public Builder itemName(String name) {
                this.itemName = name;
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

            public Builder machineType(String machine) {
                this.machineType = machine;
                return this;
            }

            public Builder addItemChild(TreeNode child) {
                this.itemChildren.add(child);
                return this;
            }

            public Builder addFluidChild(FluidNode child) {
                this.fluidChildren.add(child);
                return this;
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
        private final String fluidName;
        private final double amount;
        private final String fluidType;

        public FluidNode(String fluidName, double amount, String fluidType) {
            this.fluidName = fluidName;
            this.amount = amount;
            this.fluidType = fluidType;
        }


        public FluidNode(String fluidName, double amount) {
            this(fluidName, amount, "FLUID");
        }

        public String getFluidName() {
            return fluidName;
        }

        public double getAmount() {
            return amount;
        }

        public String getFluidType() {
            return fluidType;
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

        public void setUniqueItems(int count) {
            uniqueItems = count;
        }

        public int getTotalNodes() {
            return totalNodes;
        }

        public int getUniqueItems() {
            return uniqueItems;
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