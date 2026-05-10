package org.complexityanalyzer.analyzer.resource.providers;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import com.mojang.authlib.GameProfile;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.mixin.LootContextAccessor;
import org.complexityanalyzer.registry.GameRegistryManager;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

public class PlantSimulator {

    private final Object2ObjectMap<Block, SimulationResult> cache = new Object2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private final Object2ObjectMap<Block, GroundResult> groundCache = new Object2ObjectOpenHashMap<>();
    private final Reference2IntMap<Block> ageMaxCache = new Reference2IntOpenHashMap<>();
    private Player fakePlayer;
    private boolean platformReady = false;

    private final int simOriginX = SIM_ORIGIN.getX();
    private final int simOriginY = SIM_ORIGIN.getY();
    private final int simOriginZ = SIM_ORIGIN.getZ();
    private final int originChunkX = simOriginX >> 4;
    private final int originChunkZ = simOriginZ >> 4;

    private static final BlockPos SIM_ORIGIN = new BlockPos(20_000_000, 200, 20_000_000);
    private static final int BARRIER_RADIUS = 16;
    private static final int SEARCH_RADIUS = 32;
    private static final int DEPTH_BELOW = 100;
    private static final int HEIGHT_ABOVE = 100;
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
        SimulationResult result = runSimulation(plantBlock, level);
        cache.put(plantBlock, result);
        return result;
    }

    public Object2ObjectMap<Block, SimulationResult> simulateAll(ObjectList<Block> blocks, ServerLevel level) {
        if (blocks == null || blocks.isEmpty()) return null;
        ensurePlatform(level);
        fakePlayer = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "[PlantSim]"));
        Object2ObjectMap<Block, SimulationResult> results = new Object2ObjectOpenHashMap<>();
        for (Block block : blocks) {
            SimulationResult result = simulate(block, level);
            if (result != null) results.put(block, result);
        }
        collectAndClear(level, null, null);
        clearEntitiesInsideBox(level);
        releasePlatform(level);
        fakePlayer = null;
        return results.isEmpty() ? null : results;
    }

    public boolean isNotPlant(Block block) {
        BlockState state = block.defaultBlockState();
        if (state.isAir() || block == Blocks.AIR || block == Blocks.FIRE || block == Blocks.SNOW
                || block == Blocks.TURTLE_EGG) return true;
        if (state.is(BlockTags.CROPS) || state.is(BlockTags.SAPLINGS)) return false;
        float hardness = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (hardness > 0.5f || hardness < 0.0f || (!(block instanceof BonemealableBlock)
                && !state.isRandomlyTicking() && findAgeProperty(block) == null)) return true;
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private void clearEntitiesInsideBox(ServerLevel level) {
        AABB interiorBox = new AABB(simOriginX - BARRIER_RADIUS, simOriginY - DEPTH_BELOW,
                simOriginZ - BARRIER_RADIUS, simOriginX + BARRIER_RADIUS,
                simOriginY + HEIGHT_ABOVE, simOriginZ + BARRIER_RADIUS);

        for (Entity entity : level.getEntities(null, interiorBox)) {
            if (entity instanceof ItemEntity) entity.discard();
        }
    }

    private SimulationResult runSimulation(Block plantBlock, ServerLevel level) {
        Reference2DoubleMap<Item> drops = new Reference2DoubleOpenHashMap<>();
        RandomSource random = RandomSource.create(12345);

        collectAndKillEntities(level, null);
        collectAndClear(level, null, null);
        GroundResult ground = findSuitableGround(plantBlock, level);
        if (ground == null) return null;
        collectAndKillEntities(level, null);

        BlockPos plantPos = SIM_ORIGIN;
        BlockPos groundPos = plantPos.relative(ground.side());

        level.setBlock(groundPos, ground.block().defaultBlockState(), FLAG_NO_UPDATE);
        level.setBlock(plantPos, plantBlock.defaultBlockState(), FLAG_NO_UPDATE);

        int stages = growPlant(plantBlock, level, plantPos, random);
        Reference2DoubleMap<Item> currentDrops = new Reference2DoubleOpenHashMap<>();
        collectAndClear(level, currentDrops, groundPos);
        collectAndKillEntities(level, currentDrops);
        addDrops(drops, currentDrops);

        mergeDropEstimates(drops, simulateMatureLoot(plantBlock, level, ground));
        if (drops.isEmpty()) return null;
        if (stages <= 1) stages = 2;
        return new SimulationResult(drops, stages);
    }

    private Reference2DoubleMap<Item> simulateMatureLoot(Block block, ServerLevel level, @Nullable GroundResult ground) {
        Reference2DoubleMap<Item> drops = new Reference2DoubleOpenHashMap<>();
        BlockPos lootPos = SIM_ORIGIN;
        BlockState oldState = level.getBlockState(lootPos);
        BlockPos groundPos = null;
        BlockState oldGroundState = null;
        if (ground != null) {
            groundPos = lootPos.relative(ground.side());
            oldGroundState = level.getBlockState(groundPos);
            level.setBlock(groundPos, ground.block().defaultBlockState(), FLAG_NO_UPDATE);
        }

        try {
            BlockState state = block.defaultBlockState();
            IntegerProperty ageProp = findAgeProperty(block);
            if (ageProp != null) {
                int max = ageMaxCache.getInt(block);
                if (max == 0) {
                    for (int v : ageProp.getPossibleValues()) if (v > max) max = v;
                    ageMaxCache.put(block, max);
                }
                state = state.setValue(ageProp, max);
            }

            level.setBlock(lootPos, state, FLAG_NO_UPDATE);

            LootTable lootTable = level.getServer().reloadableRegistries().getLootTable(block.getLootTable());

            int samples = 50;
            double sampleMultiplier = 1.0 / samples;
            boolean injectionWorked = false;

            for (int i = 0; i < samples; i++) {
                RandomSource deterministicRandom = RandomSource.create(676767 + i);
                LootParams params = new LootParams.Builder(level)
                        .withParameter(LootContextParams.BLOCK_STATE, state)
                        .withParameter(LootContextParams.ORIGIN, Vec3.atLowerCornerOf(lootPos))
                        .withParameter(LootContextParams.THIS_ENTITY, PlantSimulator.this.fakePlayer)
                        .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                        .create(LootContextParamSets.BLOCK);

                LootContext context = new LootContext.Builder(params).create(Optional.empty());
                if (i == 0 || injectionWorked) injectionWorked = injectRandomIntoContext(context, deterministicRandom);
                PlantSimulator.this.fakePlayer.setPos(lootPos.getX() + 0.5, lootPos.getY() + 0.5, lootPos.getZ() + 0.5);

                ObjectArrayList<ItemStack> lootDrops = new ObjectArrayList<>();
                if (lootTable != LootTable.EMPTY) lootTable.getRandomItems(context, lootDrops::add);

                if (lootDrops.isEmpty()) lootDrops.addAll(Block.getDrops(state, level, lootPos,
                        level.getBlockEntity(lootPos), PlantSimulator.this.fakePlayer, ItemStack.EMPTY));

                for (ItemStack stack : lootDrops) addDrop(drops, stack, sampleMultiplier);
            }
        } catch (Throwable e) {
            ComplexityAnalyzer.LOGGER.error("[PlantSim] Loot error for {}: {}", GameRegistryManager.getBlockId(block), e.getMessage());
        } finally {
            level.setBlock(lootPos, oldState, FLAG_NO_UPDATE);
            if (groundPos != null) level.setBlock(groundPos, oldGroundState, FLAG_NO_UPDATE);
        }
        return drops;
    }

    private boolean injectRandomIntoContext(LootContext context, RandomSource random) {
        try {
            ((LootContextAccessor) context).setRandom(random);
            return true;
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[PlantSim] Failed to inject random into LootContext: {}", e.getMessage());
            return false;
        }
    }

    @Nullable
    private GroundResult findSuitableGround(Block plantBlock, ServerLevel level) {
        if (groundCache.containsKey(plantBlock)) return groundCache.get(plantBlock);

        BlockPos plantPos = SIM_ORIGIN;
        BlockState plantState = plantBlock.defaultBlockState();
        GroundResult result = tryFindGround(plantPos, plantState, level, PRIORITY_GROUNDS);
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
        for (Direction dir : Direction.values()) {
            mutablePos.setWithOffset(plantPos, dir);
            for (Block b : blocks) {
                if (tryGroundQuickly(b, plantState, level, mutablePos, plantPos)) return new GroundResult(b, dir);
            }
        }
        return null;
    }

    private boolean tryGroundQuickly(Block candidate, BlockState plantState, ServerLevel level, BlockPos groundPos, BlockPos plantPos) {
        try {
            BlockState candidateState = candidate.defaultBlockState();
            if (candidateState.isAir() && candidate != Blocks.WATER) return false;
            BlockState oldGround = level.getBlockState(groundPos);
            level.setBlock(groundPos, candidateState, FLAG_NO_UPDATE);
            boolean survives = plantState.canSurvive(level, plantPos);
            level.setBlock(groundPos, oldGround, FLAG_NO_UPDATE);
            return survives;
        } catch (Exception ignored) {
            return false;
        }
    }

    private int growPlant(Block plantBlock, ServerLevel level, BlockPos plantPos, RandomSource random) {
        int bonemealUses = 0;
        BlockState startState = level.getBlockState(plantPos);
        int stageCount = estimateGrowthStages(plantBlock);
        long simStart = System.currentTimeMillis();
        BonemealableBlock bm = (plantBlock instanceof BonemealableBlock b) ? b : null;

        for (int tick = 0; tick < MAX_TICKS; tick++) {
            if ((tick & 7) == 0 && System.currentTimeMillis() - simStart > MAX_SIMULATION_MS) break;

            BlockState current = level.getBlockState(plantPos);

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

            BlockState after = level.getBlockState(plantPos);
            if (after.isAir()) break;

            IntegerProperty ageProp = findAgeProperty(after.getBlock());
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
        BlockState endState = level.getBlockState(plantPos);
        return endState.equals(startState) ? 1 : stageCount;
    }

    private int estimateGrowthStages(Block block) {
        IntegerProperty ageProp = findAgeProperty(block);
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
                        if (isBaseLayer | isXBoundary | isZBoundary)
                            level.setBlock(mutablePos, Blocks.BARRIER.defaultBlockState(), 3);
                        else if (isLightLayer)
                            level.setBlock(mutablePos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15), 3);
                        else if (!level.getBlockState(mutablePos).isAir())
                            level.setBlock(mutablePos, Blocks.AIR.defaultBlockState(), 3);
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
                ChunkPos cp = new ChunkPos(originChunkX + cx, originChunkZ + cz);
                level.getChunkSource().removeRegionTicket(TicketType.FORCED, cp, 2, cp);
            }
        }
        platformReady = false;
    }

    private void collectAndKillEntities(ServerLevel level, @Nullable Reference2DoubleMap<Item> drops) {
        int r = SEARCH_RADIUS + 2;
        AABB box = new AABB(simOriginX - r, simOriginY - DEPTH_BELOW - 1, simOriginZ - r,
                simOriginX + r, simOriginY + HEIGHT_ABOVE + 1, simOriginZ + r);
        boolean drop = drops != null;
        for (Entity entity : level.getEntities(null, box)) {
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

        if (shouldCollectDrops) {
            for (int y = minY; y <= maxY; y++) {
                mutablePos.set(simOriginX, y, simOriginZ);
                BlockState state = level.getBlockState(mutablePos);
                if (state.isAir() || isManagedPlatformBlock(state) || mutablePos.asLong() == groundPosLong) continue;
                collectDropsAt(level, mutablePos, state, drops);
                level.removeBlock(mutablePos, false);
            }

            int radius = 1;
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
                            BlockState state = level.getBlockState(mutablePos);
                            if (state.isAir() || isManagedPlatformBlock(state)) continue;

                            BlockPos pos = mutablePos.immutable();
                            if (pos.asLong() != groundPosLong) {
                                foundBlocks = true;
                                collectDropsAt(level, pos, state, drops);
                            }
                            level.removeBlock(pos, false);
                        }
                    }
                }
                if (!foundBlocks && radius > 1) break;
                radius += 2;
            }
        } else {
            int range = BARRIER_RADIUS + 1;
            for (int y = minY; y <= maxY; y++) {
                for (int x = -range; x <= range; x++) {
                    int worldX = simOriginX + x;
                    for (int z = -range; z <= range; z++) {
                        mutablePos.set(worldX, y, simOriginZ + z);
                        BlockState state = level.getBlockState(mutablePos);
                        if (state.isAir() || isManagedPlatformBlock(state)) continue;
                        level.removeBlock(mutablePos.immutable(), false);
                    }
                }
            }
        }
    }

    private void collectDropsAt(ServerLevel level, BlockPos pos, BlockState state, Reference2DoubleMap<Item> drops) {
        try {
            if (state.is(BlockTags.LEAVES)) addDrop(drops, state.getBlock().asItem(), 1.0);
            fakePlayer.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            for (ItemStack stack : Block.getDrops(state, level, pos, level.getBlockEntity(pos), fakePlayer, ItemStack.EMPTY))
                addDrop(drops, stack, 1.0);
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
        for (Property<?> prop : b.defaultBlockState().getProperties())
            if (prop instanceof IntegerProperty ip && (prop.getName().equals("age") || prop.getName().equals("growth")))
                return ip;
        return null;
    }
}