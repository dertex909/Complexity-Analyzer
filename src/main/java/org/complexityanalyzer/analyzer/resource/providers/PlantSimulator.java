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

package org.complexityanalyzer.analyzer.resource.providers;

import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.GameRegistryManager;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;

public class PlantSimulator {

    private final Object2ObjectMap<Block, SimulationResult> cache = new Object2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private final Object2ObjectMap<Block, GroundResult> groundCache = new Object2ObjectOpenHashMap<>();
    private final Reference2IntMap<Block> ageMaxCache = new Reference2IntOpenHashMap<>();
    private Player fakePlayer;
    private boolean platformReady = false;
    private LevelReader survivalView;
    private ServerLevel viewLevel;
    private BlockPos viewGroundPos;
    private BlockState viewGroundState;
    private BlockPos viewPlantPos;

    private final int simOriginX = SIM_ORIGIN.getX();
    private final int simOriginY = SIM_ORIGIN.getY();
    private final int simOriginZ = SIM_ORIGIN.getZ();
    private final int originChunkX = simOriginX >> 4;
    private final int originChunkZ = simOriginZ >> 4;

    private static final BlockPos SIM_ORIGIN = new BlockPos(20_000_000, 200, 20_000_000);
    private static final int BARRIER_RADIUS = 16;
    private static final int SEARCH_RADIUS = 32;
    private static final int CLEAR_EMPTY_SHELL_GAP = 8;
    private static final int DEPTH_BELOW = 30;
    private static final int HEIGHT_ABOVE = 30;
    private static final int MAX_TICKS = 50;
    private static final int MAX_BONEMEAL = 8;
    private static final long MAX_SIMULATION_MS = 2000;
    private static final int FLAG_NO_UPDATE = 2 | 16;

    private static final ObjectList<Block> PRIORITY_GROUNDS = ObjectArrayList.of(
            Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.FARMLAND, Blocks.SAND,
            Blocks.RED_SAND, Blocks.GRAVEL, Blocks.NETHERRACK, Blocks.SOUL_SAND,
            Blocks.SOUL_SOIL, Blocks.END_STONE, Blocks.WATER
    );

    public record SimulationResult(Reference2DoubleMap<Item> drops, int growthStages) {
    }

    private record GroundResult(Block block, Direction side) {
    }

    public SimulationResult simulate(Block plantBlock, ServerLevel level) {
        if (cache.containsKey(plantBlock)) return cache.get(plantBlock);
        var result = runSimulation(plantBlock, level);
        cache.put(plantBlock, result);
        return result;
    }

    public Object2ObjectMap<Block, SimulationResult> simulateAll(ObjectList<Block> blocks, ServerLevel level) {
        if (blocks == null || blocks.isEmpty()) return null;
        ensurePlatform(level);
        fakePlayer = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "[PlantSim]"));
        var results = new Object2ObjectOpenHashMap<Block, SimulationResult>();

        for (var block : blocks) {
            try {
                var result = simulate(block, level);
                if (result != null) results.put(block, result);
            } catch (Throwable t) {
                ComplexityAnalyzer.LOGGER.warn("[PlantSim] Skipping {} due to error: {}", GameRegistryManager.getBlockId(block), t.toString());
            }
        }

        collectAndClear(level, null, null);
        clearEntitiesInsideBox(level);
        releasePlatform(level);
        fakePlayer = null;
        return results.isEmpty() ? null : results;
    }

    public boolean isPlant(Block block) {
        try {
            var state = block.defaultBlockState();
            if (state.isAir() || block == Blocks.AIR) return false;
            if (block == Blocks.FIRE || block == Blocks.SNOW || block == Blocks.TURTLE_EGG) return false;
            if (state.is(BlockTags.CROPS) || state.is(BlockTags.SAPLINGS)) return true;
            float hardness;
            try {
                hardness = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            } catch (Throwable t) {
                return false;
            }
            if (hardness > 0.5f || hardness < 0.0f) return false;
            if (!(block instanceof BonemealableBlock) && !state.isRandomlyTicking() && findAgeProperty(block) == null)
                return false;
            try {
                return !state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            } catch (Throwable t) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private void clearEntitiesInsideBox(ServerLevel level) {
        AABB interiorBox = new AABB(simOriginX - BARRIER_RADIUS, simOriginY - DEPTH_BELOW,
                simOriginZ - BARRIER_RADIUS, simOriginX + BARRIER_RADIUS,
                simOriginY + HEIGHT_ABOVE, simOriginZ + BARRIER_RADIUS);

        for (var entity : level.getEntities(null, interiorBox)) if (entity instanceof ItemEntity) entity.discard();
    }

    private SimulationResult runSimulation(Block plantBlock, ServerLevel level) {
        var drops = new Reference2DoubleOpenHashMap<Item>();
        var random = RandomSource.create(12345);

        collectAndKillEntities(level, null);
        collectAndClear(level, null, null);

        var ground = findSuitableGround(plantBlock, level);
        if (ground == null) return null;
        collectAndKillEntities(level, null);

        var plantPos = SIM_ORIGIN;
        var groundPos = plantPos.relative(ground.side());

        level.setBlock(groundPos, ground.block().defaultBlockState(), FLAG_NO_UPDATE);
        level.setBlock(plantPos, plantBlock.defaultBlockState(), FLAG_NO_UPDATE);

        int stages = growPlant(plantBlock, level, plantPos, random);
        var currentDrops = new Reference2DoubleOpenHashMap<Item>();
        collectAndClear(level, currentDrops, groundPos);
        collectAndKillEntities(level, currentDrops);
        addDrops(drops, currentDrops);

        var matureLoot = simulateMatureLoot(plantBlock, level, ground);
        mergeDropEstimates(drops, matureLoot);
        if (drops.isEmpty()) return null;
        if (stages <= 1) stages = 2;
        return new SimulationResult(drops, stages);
    }

    private Reference2DoubleMap<Item> simulateMatureLoot(Block block, ServerLevel level, @Nullable GroundResult ground) {
        var drops = new Reference2DoubleOpenHashMap<Item>();
        var lootPos = SIM_ORIGIN;
        var oldState = level.getBlockState(lootPos);
        BlockPos groundPos = null;
        BlockState oldGroundState = null;
        if (ground != null) {
            groundPos = lootPos.relative(ground.side());
            oldGroundState = level.getBlockState(groundPos);
            level.setBlock(groundPos, ground.block().defaultBlockState(), FLAG_NO_UPDATE);
        }

        try {
            var state = block.defaultBlockState();
            var ageProp = findAgeProperty(block);
            if (ageProp != null) {
                int max = ageMaxCache.getInt(block);
                if (max == 0) {
                    for (int v : ageProp.getPossibleValues()) if (v > max) max = v;
                    ageMaxCache.put(block, max);
                }
                state = state.setValue(ageProp, max);
            }

            level.setBlock(lootPos, state, FLAG_NO_UPDATE);

            var lootTable = level.getServer().reloadableRegistries().getLootTable(block.getLootTable());

            int samples = 50;
            double sampleMultiplier = 1.0 / samples;

            for (int i = 0; i < samples; i++) {
                var params = new LootParams.Builder(level)
                        .withParameter(LootContextParams.BLOCK_STATE, state)
                        .withParameter(LootContextParams.ORIGIN, Vec3.atLowerCornerOf(lootPos))
                        .withParameter(LootContextParams.THIS_ENTITY, PlantSimulator.this.fakePlayer)
                        .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                        .create(LootContextParamSets.BLOCK);

                var context = new LootContext.Builder(params)
                        .withOptionalRandomSource(RandomSource.create(676767 + i))
                        .create(Optional.empty());
                PlantSimulator.this.fakePlayer.setPos(lootPos.getX() + 0.5, lootPos.getY() + 0.5, lootPos.getZ() + 0.5);

                var lootDrops = new ObjectArrayList<ItemStack>();
                if (lootTable != LootTable.EMPTY) lootTable.getRandomItems(context, lootDrops::add);

                if (lootDrops.isEmpty()) lootDrops.addAll(Block.getDrops(state, level, lootPos,
                        level.getBlockEntity(lootPos), PlantSimulator.this.fakePlayer, ItemStack.EMPTY));

                for (var stack : lootDrops) addDrop(drops, stack, sampleMultiplier);
            }
        } catch (Throwable e) {
            ComplexityAnalyzer.LOGGER.error("[PlantSim] Loot error for {}: {}", GameRegistryManager.getBlockId(block), e.getMessage());
        } finally {
            level.setBlock(lootPos, oldState, FLAG_NO_UPDATE);
            if (groundPos != null) level.setBlock(groundPos, oldGroundState, FLAG_NO_UPDATE);
        }
        return drops;
    }

    @Nullable
    private GroundResult findSuitableGround(Block plantBlock, ServerLevel level) {
        if (groundCache.containsKey(plantBlock)) return groundCache.get(plantBlock);

        var plantPos = SIM_ORIGIN;
        var plantState = plantBlock.defaultBlockState();
        var result = tryFindGround(plantPos, plantState, level, PRIORITY_GROUNDS);
        if (result != null) {
            groundCache.put(plantBlock, result);
            return result;
        }

        result = tryFindGround(plantPos, plantState, level, GameRegistryManager.getAllBlocks());
        groundCache.put(plantBlock, result);
        return result;
    }

    @Nullable
    private GroundResult tryFindGround(BlockPos plantPos, BlockState plantState, ServerLevel level, Iterable<Block> blocks) {
        for (var dir : Direction.values()) {
            mutablePos.setWithOffset(plantPos, dir);
            for (var b : blocks) {
                if (b instanceof EntityBlock || b.defaultBlockState().hasBlockEntity()) continue;
                if (tryGroundQuickly(b, plantState, level, mutablePos, plantPos)) return new GroundResult(b, dir);
            }
        }
        return null;
    }

    private boolean tryGroundQuickly(Block candidate, BlockState plantState, ServerLevel level, BlockPos groundPos, BlockPos plantPos) {
        if (candidate instanceof EntityBlock || candidate.defaultBlockState().hasBlockEntity()) return false;
        var candidateState = candidate.defaultBlockState();
        if (candidateState.isAir() && candidate != Blocks.WATER) return false;

        try {
            viewLevel = level;
            viewGroundPos = groundPos;
            viewGroundState = candidateState;
            viewPlantPos = plantPos;
            return plantState.canSurvive(survivalView(), plantPos);
        } catch (Throwable t) {
            return tryGroundWithSetBlock(candidateState, plantState, level, groundPos, plantPos);
        }
    }

    private boolean tryGroundWithSetBlock(BlockState candidateState, BlockState plantState, ServerLevel level, BlockPos groundPos, BlockPos plantPos) {
        try {
            var oldGround = level.getBlockState(groundPos);
            level.setBlock(groundPos, candidateState, FLAG_NO_UPDATE);
            boolean survives = plantState.canSurvive(level, plantPos);
            level.setBlock(groundPos, oldGround, FLAG_NO_UPDATE);
            return survives;
        } catch (Exception ignored) {
            return false;
        }
    }

    private LevelReader survivalView() {
        if (survivalView == null) survivalView = (LevelReader) Proxy.newProxyInstance(
                LevelReader.class.getClassLoader(),
                new Class[]{LevelReader.class},
                (proxy, method, args) -> {
                    if (args != null && args.length == 1 && args[0] instanceof BlockPos p && "getBlockState".equals(method.getName())) {
                        if (p.equals(viewGroundPos)) return viewGroundState;
                        if (p.equals(viewPlantPos)) return Blocks.AIR.defaultBlockState();
                    }
                    return method.invoke(viewLevel, args);
                });
        return survivalView;
    }

    private int growPlant(Block plantBlock, ServerLevel level, BlockPos plantPos, RandomSource random) {
        int bonemealUses = 0;
        var startState = level.getBlockState(plantPos);
        int stageCount = estimateGrowthStages(plantBlock);
        long simStart = System.currentTimeMillis();
        var bm = (plantBlock instanceof BonemealableBlock b) ? b : null;

        for (int tick = 0; tick < MAX_TICKS; tick++) {
            if ((tick & 7) == 0 && System.currentTimeMillis() - simStart > MAX_SIMULATION_MS) break;
            var current = level.getBlockState(plantPos);

            boolean canUseBonemeal = bm != null && current.getBlock() == plantBlock && bonemealUses < MAX_BONEMEAL;
            if (canUseBonemeal && bm.isValidBonemealTarget(level, plantPos, current)) try {
                bm.performBonemeal(level, random, plantPos, current);
                bonemealUses++;
                continue;
            } catch (Throwable ignored) {
            }

            if (current.isRandomlyTicking()) try {
                current.randomTick(level, plantPos, random);
            } catch (Throwable ignored) {
            }

            var after = level.getBlockState(plantPos);
            if (after.isAir()) break;

            var ageProp = findAgeProperty(after.getBlock());
            if (ageProp != null) {
                int val = after.getValue(ageProp);
                int max = ageMaxCache.getInt(after.getBlock());
                if (max == 0) {
                    for (int v : ageProp.getPossibleValues()) if (v > max) max = v;
                    ageMaxCache.put(after.getBlock(), max);
                }
                if (val >= max) break;
            }
        }
        var endState = level.getBlockState(plantPos);
        return endState.equals(startState) ? 1 : stageCount;
    }

    private int estimateGrowthStages(Block block) {
        var ageProp = findAgeProperty(block);
        if (ageProp == null) return 2;
        if (ageMaxCache.containsKey(block)) return Math.max(2, ageMaxCache.getInt(block) + 1);

        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int v : ageProp.getPossibleValues()) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        ageMaxCache.put(block, max);
        return Math.max(2, max - min + 1);
    }

    private void ensurePlatform(ServerLevel level) {
        if (platformReady) return;

        int chunkRadius = (SEARCH_RADIUS >> 4) + 1;
        for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
            for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
                ChunkPos cp = new ChunkPos(originChunkX + cx, originChunkZ + cz);
                level.getChunkSource().addRegionTicket(TicketType.FORCED, cp, 2, cp);
            }
        }

        int range = BARRIER_RADIUS + 1;
        int maxY = Math.min(level.getMaxBuildHeight() - 1, simOriginY + HEIGHT_ABOVE);
        int baseY = simOriginY - DEPTH_BELOW;

        for (int y = baseY; y <= maxY; y++) {
            boolean isBaseLayer = y == baseY, isLightLayer = y == maxY;

            for (int x = -range; x <= BARRIER_RADIUS; x++) {
                boolean isXBoundary = x == -range | x == BARRIER_RADIUS;
                int worldX = simOriginX + x;

                for (int z = -range; z <= BARRIER_RADIUS; z++) {
                    boolean isZBoundary = z == -range | z == BARRIER_RADIUS;
                    mutablePos.set(worldX, y, simOriginZ + z);

                    try {
                        var current = level.getBlockState(mutablePos);
                        if (isBaseLayer | isXBoundary | isZBoundary) {
                            if (!current.is(Blocks.BARRIER)) {
                                level.setBlock(mutablePos, Blocks.BARRIER.defaultBlockState(), 3);
                            }
                        } else if (isLightLayer) {
                            if (!current.is(Blocks.LIGHT)) {
                                level.setBlock(mutablePos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15), 3);
                            }
                        } else if (!current.isAir()) {
                            level.setBlock(mutablePos, Blocks.AIR.defaultBlockState(), 3);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        collectAndKillEntities(level, null);
        platformReady = true;
    }

    private void releasePlatform(ServerLevel level) {
        if (!platformReady) return;
        int chunkRadius = (SEARCH_RADIUS >> 4) + 1;
        for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
            for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
                var cp = new ChunkPos(originChunkX + cx, originChunkZ + cz);
                level.getChunkSource().removeRegionTicket(TicketType.FORCED, cp, 2, cp);
            }
        }
        platformReady = false;
    }

    private void collectAndKillEntities(ServerLevel level, @Nullable Reference2DoubleMap<Item> drops) {
        int r = SEARCH_RADIUS + 2;
        var box = new AABB(simOriginX - r, simOriginY - DEPTH_BELOW - 1, simOriginZ - r,
                simOriginX + r, simOriginY + HEIGHT_ABOVE + 1, simOriginZ + r);
        boolean drop = drops != null;
        for (var entity : level.getEntities(null, box)) {
            if (entity instanceof Player) continue;
            if (entity instanceof ItemEntity itemEntity && drop) addDrop(drops, itemEntity.getItem(), 1.0);
            entity.discard();
        }
    }

    private void collectAndClear(ServerLevel level, @Nullable Reference2DoubleMap<Item> drops, @Nullable BlockPos groundPos) {
        long groundPosLong = groundPos != null ? groundPos.asLong() : BlockPos.asLong(simOriginX, simOriginY - 1, simOriginZ);
        int minY = simOriginY - DEPTH_BELOW;
        int maxY = Math.min(level.getMaxBuildHeight() - 1, simOriginY + HEIGHT_ABOVE);
        boolean shouldCollectDrops = drops != null;
        var air = Blocks.AIR.defaultBlockState();

        for (int y = minY; y <= maxY; y++) {
            mutablePos.set(simOriginX, y, simOriginZ);
            try {
                var state = level.getBlockState(mutablePos);
                if (state.isAir() || isManagedPlatformBlock(state) || mutablePos.asLong() == groundPosLong) continue;
                if (shouldCollectDrops) collectDropsAt(level, mutablePos, state, drops);
                level.setBlock(mutablePos, air, FLAG_NO_UPDATE);
            } catch (Throwable ignored) {
            }
        }

        int radius = 1;
        int lastFoundRadius = 1;
        while (radius <= SEARCH_RADIUS) {
            boolean foundBlocks = false;
            int prevRadius = radius - 2;
            for (int y = minY; y <= maxY; y++) {
                for (int x = -radius; x <= radius; x++) {
                    int worldX = simOriginX + x;
                    for (int z = -radius; z <= radius; z++) {
                        boolean isOnBoundary = Math.abs(x) == radius || Math.abs(z) == radius || Math.abs(y - simOriginY) == radius;
                        if (prevRadius > 0 && !isOnBoundary) continue;
                        mutablePos.set(worldX, y, simOriginZ + z);
                        try {
                            var state = level.getBlockState(mutablePos);
                            if (state.isAir() || isManagedPlatformBlock(state)) continue;

                            var pos = mutablePos.immutable();
                            if (pos.asLong() != groundPosLong) {
                                foundBlocks = true;
                                if (shouldCollectDrops) collectDropsAt(level, pos, state, drops);
                            }
                            level.setBlock(pos, air, FLAG_NO_UPDATE);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            if (foundBlocks) lastFoundRadius = radius;
            else if (radius - lastFoundRadius >= CLEAR_EMPTY_SHELL_GAP) break;
            radius += 2;
        }
    }

    private void collectDropsAt(ServerLevel level, BlockPos pos, BlockState state, Reference2DoubleMap<Item> drops) {
        try {
            if (state.is(BlockTags.LEAVES)) addDrop(drops, state.getBlock().asItem(), 1.0);
            fakePlayer.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            for (var stack : Block.getDrops(state, level, pos, level.getBlockEntity(pos), fakePlayer, ItemStack.EMPTY)) {
                addDrop(drops, stack, 1.0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void addDrop(Reference2DoubleMap<Item> drops, Item item, double amount) {
        if (amount > 0.0) drops.put(item, drops.getDouble(item) + amount);
    }

    private void addDrop(Reference2DoubleMap<Item> drops, ItemStack stack, double multiplier) {
        if (!stack.isEmpty()) addDrop(drops, stack.getItem(), stack.getCount() * multiplier);
    }

    private void addDrops(Reference2DoubleMap<Item> target, Reference2DoubleMap<Item> source) {
        for (var entry : source.reference2DoubleEntrySet()) addDrop(target, entry.getKey(), entry.getDoubleValue());
    }

    private void mergeDropEstimates(Reference2DoubleMap<Item> tar, Reference2DoubleMap<Item> source) {
        for (var entry : source.reference2DoubleEntrySet())
            if (entry.getDoubleValue() > tar.getDouble(entry.getKey())) tar.put(entry.getKey(), entry.getDoubleValue());
    }

    private boolean isManagedPlatformBlock(BlockState state) {
        return state.is(Blocks.BARRIER) || state.is(Blocks.LIGHT);
    }

    private IntegerProperty findAgeProperty(Block b) {
        for (var prop : b.defaultBlockState().getProperties()) {
            if (prop instanceof IntegerProperty ip && (prop.getName().equals("age") || prop.getName().equals("growth"))) {
                return ip;
            }
        }
        return null;
    }
}