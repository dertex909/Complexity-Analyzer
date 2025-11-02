package org.complexityanalyzer.analyzer.resource.sources;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.config.ComplexityConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class WorldInteractionSource implements IResourceSource {

    private final Map<Item, InteractionRule> interactionMap = new HashMap<>();
    private record InteractionRule(Item input, Item tool, double actionCost, String method) {}

    @Override
    public void initialize(net.minecraft.world.level.Level level) {
        addDeadCoralRule(Items.TUBE_CORAL_BLOCK, Items.DEAD_TUBE_CORAL_BLOCK);
        addDeadCoralRule(Items.BRAIN_CORAL_BLOCK, Items.DEAD_BRAIN_CORAL_BLOCK);
        addDeadCoralRule(Items.BUBBLE_CORAL_BLOCK, Items.DEAD_BUBBLE_CORAL_BLOCK);
        addDeadCoralRule(Items.FIRE_CORAL_BLOCK, Items.DEAD_FIRE_CORAL_BLOCK);
        addDeadCoralRule(Items.HORN_CORAL_BLOCK, Items.DEAD_HORN_CORAL_BLOCK);

        addDeadCoralRule(Items.TUBE_CORAL, Items.DEAD_TUBE_CORAL);
        addDeadCoralRule(Items.BRAIN_CORAL, Items.DEAD_BRAIN_CORAL);
        addDeadCoralRule(Items.BUBBLE_CORAL, Items.DEAD_BUBBLE_CORAL);
        addDeadCoralRule(Items.FIRE_CORAL, Items.DEAD_FIRE_CORAL);
        addDeadCoralRule(Items.HORN_CORAL, Items.DEAD_HORN_CORAL);

        addDeadCoralRule(Items.TUBE_CORAL_FAN, Items.DEAD_TUBE_CORAL_FAN);
        addDeadCoralRule(Items.BRAIN_CORAL_FAN, Items.DEAD_BRAIN_CORAL_FAN);
        addDeadCoralRule(Items.BUBBLE_CORAL_FAN, Items.DEAD_BUBBLE_CORAL_FAN);
        addDeadCoralRule(Items.FIRE_CORAL_FAN, Items.DEAD_FIRE_CORAL_FAN);
        addDeadCoralRule(Items.HORN_CORAL_FAN, Items.DEAD_HORN_CORAL_FAN);

        addStrippingRule(Items.OAK_LOG, Items.STRIPPED_OAK_LOG);
        addStrippingRule(Items.SPRUCE_LOG, Items.STRIPPED_SPRUCE_LOG);
        addStrippingRule(Items.BIRCH_LOG, Items.STRIPPED_BIRCH_LOG);
        addStrippingRule(Items.JUNGLE_LOG, Items.STRIPPED_JUNGLE_LOG);
        addStrippingRule(Items.ACACIA_LOG, Items.STRIPPED_ACACIA_LOG);
        addStrippingRule(Items.CHERRY_LOG, Items.STRIPPED_CHERRY_LOG);
        addStrippingRule(Items.DARK_OAK_LOG, Items.STRIPPED_DARK_OAK_LOG);
        addStrippingRule(Items.MANGROVE_LOG, Items.STRIPPED_MANGROVE_LOG);
        addStrippingRule(Items.CRIMSON_STEM, Items.STRIPPED_CRIMSON_STEM);
        addStrippingRule(Items.WARPED_STEM, Items.STRIPPED_WARPED_STEM);
        addStrippingRule(Items.BAMBOO_BLOCK, Items.STRIPPED_BAMBOO_BLOCK);
        addStrippingRule(Items.OAK_WOOD, Items.STRIPPED_OAK_WOOD);
        addStrippingRule(Items.SPRUCE_WOOD, Items.STRIPPED_SPRUCE_WOOD);
        addStrippingRule(Items.BIRCH_WOOD, Items.STRIPPED_BIRCH_WOOD);
        addStrippingRule(Items.JUNGLE_WOOD, Items.STRIPPED_JUNGLE_WOOD);
        addStrippingRule(Items.ACACIA_WOOD, Items.STRIPPED_ACACIA_WOOD);
        addStrippingRule(Items.CHERRY_WOOD, Items.STRIPPED_CHERRY_WOOD);
        addStrippingRule(Items.DARK_OAK_WOOD, Items.STRIPPED_DARK_OAK_WOOD);
        addStrippingRule(Items.MANGROVE_WOOD, Items.STRIPPED_MANGROVE_WOOD);
        addStrippingRule(Items.CRIMSON_HYPHAE, Items.STRIPPED_CRIMSON_HYPHAE);
        addStrippingRule(Items.WARPED_HYPHAE, Items.STRIPPED_WARPED_HYPHAE);

        interactionMap.put(Items.CARVED_PUMPKIN, new InteractionRule(Items.PUMPKIN, Items.SHEARS, 1.0, "Carving"));

        interactionMap.put(Items.FARMLAND, new InteractionRule(Items.DIRT, Items.WOODEN_HOE, 1.0, "Tilling"));

        addConcreteRule(Items.WHITE_CONCRETE_POWDER, Items.WHITE_CONCRETE);
        addConcreteRule(Items.ORANGE_CONCRETE_POWDER, Items.ORANGE_CONCRETE);
        addConcreteRule(Items.MAGENTA_CONCRETE_POWDER, Items.MAGENTA_CONCRETE);
        addConcreteRule(Items.LIGHT_BLUE_CONCRETE_POWDER, Items.LIGHT_BLUE_CONCRETE);
        addConcreteRule(Items.YELLOW_CONCRETE_POWDER, Items.YELLOW_CONCRETE);
        addConcreteRule(Items.LIME_CONCRETE_POWDER, Items.LIME_CONCRETE);
        addConcreteRule(Items.PINK_CONCRETE_POWDER, Items.PINK_CONCRETE);
        addConcreteRule(Items.GRAY_CONCRETE_POWDER, Items.GRAY_CONCRETE);
        addConcreteRule(Items.LIGHT_GRAY_CONCRETE_POWDER, Items.LIGHT_GRAY_CONCRETE);
        addConcreteRule(Items.CYAN_CONCRETE_POWDER, Items.CYAN_CONCRETE);
        addConcreteRule(Items.PURPLE_CONCRETE_POWDER, Items.PURPLE_CONCRETE);
        addConcreteRule(Items.BLUE_CONCRETE_POWDER, Items.BLUE_CONCRETE);
        addConcreteRule(Items.BROWN_CONCRETE_POWDER, Items.BROWN_CONCRETE);
        addConcreteRule(Items.GREEN_CONCRETE_POWDER, Items.GREEN_CONCRETE);
        addConcreteRule(Items.RED_CONCRETE_POWDER, Items.RED_CONCRETE);
        addConcreteRule(Items.BLACK_CONCRETE_POWDER, Items.BLACK_CONCRETE);

        addOxidationRule(Items.COPPER_BLOCK, Items.EXPOSED_COPPER);
        addOxidationRule(Items.EXPOSED_COPPER, Items.WEATHERED_COPPER);
        addOxidationRule(Items.WEATHERED_COPPER, Items.OXIDIZED_COPPER);

        interactionMap.put(Items.CHIPPED_ANVIL, new InteractionRule(Items.ANVIL, null, 0, "Usage"));
        interactionMap.put(Items.DAMAGED_ANVIL, new InteractionRule(Items.CHIPPED_ANVIL, null, 0, "Usage"));

        addOxidationRule(Items.COPPER_DOOR, Items.EXPOSED_COPPER_DOOR);
        addOxidationRule(Items.EXPOSED_COPPER_DOOR, Items.WEATHERED_COPPER_DOOR);
        addOxidationRule(Items.WEATHERED_COPPER_DOOR, Items.OXIDIZED_COPPER_DOOR);
        addOxidationRule(Items.COPPER_TRAPDOOR, Items.EXPOSED_COPPER_TRAPDOOR);
        addOxidationRule(Items.EXPOSED_COPPER_TRAPDOOR, Items.WEATHERED_COPPER_TRAPDOOR);
        addOxidationRule(Items.WEATHERED_COPPER_TRAPDOOR, Items.OXIDIZED_COPPER_TRAPDOOR);

        interactionMap.put(Items.ROOTED_DIRT, new InteractionRule(Items.MOSS_BLOCK, Items.BONE_MEAL, 2.0, "Bonemeal on Moss"));
        interactionMap.put(Items.AZALEA_LEAVES, new InteractionRule(Items.MOSS_BLOCK, Items.BONE_MEAL, 1.0, "Bonemeal on Moss"));
        interactionMap.put(Items.FLOWERING_AZALEA_LEAVES, new InteractionRule(Items.MOSS_BLOCK, Items.BONE_MEAL, 1.0, "Bonemeal on Moss"));
    }

    private void addStrippingRule(Item input, Item output) {
        interactionMap.put(output, new InteractionRule(input, Items.WOODEN_AXE, 1.0, "Stripping"));
    }
    private void addConcreteRule(Item input, Item output) {
        interactionMap.put(output, new InteractionRule(input, null, 5.0, "Solidifying in water"));
    }
    private void addOxidationRule(Item input, Item output) {
        interactionMap.put(output, new InteractionRule(input, null, ComplexityConfig.TIME_COST_MULTIPLIER.get() * (double) 40000, "Oxidizing"));
    }
    private void addDeadCoralRule(Item input, Item output) {
        interactionMap.put(output, new InteractionRule(input, null, 1.0, "Drying out"));
    }

    @Override
    public boolean canProvide(Item item) {
        return interactionMap.containsKey(item);
    }

    @Override
    public Optional<BaseResourceData> analyze(Item item) {
        if (!canProvide(item)) return Optional.empty();

        InteractionRule rule = interactionMap.get(item);

        var builder = new BaseResourceData.Builder(item, this)
                .sourceType(BaseResourceData.ResourceSourceType.BLOCK_TRANSFORMATION)
                .baseFactor(rule.actionCost())
                .details("From: " + rule.input().getDescription().getString() + " (" + rule.method() + ")");

        builder.addSourceItem(rule.input(), 1.0);
        if (rule.tool() != null) {
            builder.addSourceItem(rule.tool(), 0.01);
        }

        return Optional.of(builder.build());
    }

    @Override
    public BaseResourceData.ResourceSourceType getSourceType() {
        return BaseResourceData.ResourceSourceType.BLOCK_TRANSFORMATION;
    }

    @Override
    public String getName() {
        return "WorldInteractionSource";
    }
}