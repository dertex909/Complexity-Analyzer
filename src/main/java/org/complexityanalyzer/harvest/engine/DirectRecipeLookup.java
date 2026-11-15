package org.complexityanalyzer.harvest.engine;

import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.List;

public interface DirectRecipeLookup {
    List<RecipeHolder<?>> complexity$getRecipesForDirect(RecipeType<?> type, RecipeInput input, Level level);
}