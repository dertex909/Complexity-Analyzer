package org.complexityanalyzer.analyzer.resource.providers;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.BlockPos;
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
    private final ObjectLinkedOpenHashSet<Block> knownGrounds = new ObjectLinkedOpenHashSet<>();
    private final Object2ObjectMap<Block, Block> groundCache = new Object2ObjectOpenHashMap<>();
    private final Reference2IntMap<Block> ageMaxCache = new Reference2IntOpenHashMap<>();
    private boolean platformReady = false;

    private final int simOriginX = SIM_ORIGIN.getX();
    private final int simOriginY = SIM_ORIGIN.getY();
    private final int simOriginZ = SIM_ORIGIN.getZ();
    private final int originChunkX = simOriginX >> 4;
    private final int originChunkZ = simOriginZ >> 4;

    private static final BlockPos SIM_ORIGIN = new BlockPos(20_000_000, 200, 20_000_000);
    private static final int AREA_RADIUS = 16;
    private static final int CLEAR_RADIUS = 64;
    private static final int MAX_CLEAR_HEIGHT = 160;
    private static final int MAX_TICKS = 50;
    private static final int MAX_BONEMEAL = 8;
    private static final long MAX_SIMULATION_MS = 2000;
    private static final int FLAG_NO_UPDATE = 2 | 16;

    private static final ObjectList<Block> PRIORITY_GROUNDS = new ObjectArrayList<>();

    static {
        PRIORITY_GROUNDS.add(Blocks.GRASS_BLOCK);
        PRIORITY_GROUNDS.add(Blocks.DIRT);
        PRIORITY_GROUNDS.add(Blocks.FARMLAND);
        PRIORITY_GROUNDS.add(Blocks.SAND);
        PRIORITY_GROUNDS.add(Blocks.RED_SAND);
        PRIORITY_GROUNDS.add(Blocks.GRAVEL);
        PRIORITY_GROUNDS.add(Blocks.NETHERRACK);
        PRIORITY_GROUNDS.add(Blocks.SOUL_SAND);
        PRIORITY_GROUNDS.add(Blocks.SOUL_SOIL);
        PRIORITY_GROUNDS.add(Blocks.END_STONE);
        PRIORITY_GROUNDS.add(Blocks.WATER);
    }

    public record SimulationResult(Reference2DoubleMap<Item> drops, int growthStages) {
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
        Object2ObjectMap<Block, SimulationResult> results = new Object2ObjectOpenHashMap<>();
        int current = 0;
        for (Block block : blocks) {
            current++;
            ComplexityAnalyzer.LOGGER.info("[PlantSim] [{}/{}] Simulating: {}", current, blocks.size(), GameRegistryManager.getBlockId(block));
            SimulationResult result = simulate(block, level);
            if (result != null) results.put(block, result);
        }
        hardClearArea(level);
        killEntities(level);
        return results.isEmpty() ? null : results;
    }

    private void hardClearArea(ServerLevel level) {
        collectAndClear(level, null);
    }

    public boolean isNotPlant(Block block) {
        BlockState state = block.defaultBlockState();
        if (state.isAir() || block == Blocks.AIR || block == Blocks.FIRE || block == Blocks.SNOW
                || block == Blocks.TURTLE_EGG) return true;
        if (state.is(BlockTags.CROPS) || state.is(BlockTags.SAPLINGS)) return false;
        float hardness = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (hardness > 0.5f || hardness < 0.0f || (!(block instanceof BonemealableBlock) && !state.isRandomlyTicking()
                && findAgeProperty(block) == null))
            return true;
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private synchronized SimulationResult runSimulation(Block plantBlock, ServerLevel level) {
        Reference2DoubleMap<Item> drops = new Reference2DoubleOpenHashMap<>();
        int stages = 1;
        RandomSource random = RandomSource.create(12345);
        long totalStart = System.currentTimeMillis();

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                killEntities(level);
                collectAndClear(level, null);
                Block ground = findSuitableGround(plantBlock, level);
                if (ground == null) return null;
                killEntities(level);
                collectAndClear(level, null);

                BlockPos groundPos = new BlockPos(simOriginX, simOriginY + 3, simOriginZ);
                BlockPos plantPos = new BlockPos(simOriginX, simOriginY + 4, simOriginZ);

                level.setBlock(groundPos, ground.defaultBlockState(), FLAG_NO_UPDATE);
                level.setBlock(plantPos, plantBlock.defaultBlockState(), FLAG_NO_UPDATE);

                stages = growPlant(plantBlock, level, plantPos, random);
                Reference2DoubleMap<Item> currentDrops = new Reference2DoubleOpenHashMap<>();
                collectAndClear(level, currentDrops);
                collectAndKillEntities(level, currentDrops);
                addDrops(drops, currentDrops);
                break;
            } catch (Throwable t) {
                if (attempt == 2) {
                    ComplexityAnalyzer.LOGGER.debug("[PlantSim] Skipping {} after 3 attempts", GameRegistryManager.getBlockId(plantBlock));
                } else {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }

        mergeDropEstimates(drops, simulateMatureLoot(plantBlock, level));
        if (drops.isEmpty()) return null;
        if (stages <= 1) stages = 2;

        ComplexityAnalyzer.LOGGER.debug("[PlantSim] {}: stages={} drops={} TOTAL={}ms",
                GameRegistryManager.getBlockId(plantBlock), stages, formatDrops(drops), System.currentTimeMillis() - totalStart);

        return new SimulationResult(drops, stages);
    }

    private Reference2DoubleMap<Item> simulateMatureLoot(Block block, ServerLevel level) {
        Reference2DoubleMap<Item> drops = new Reference2DoubleOpenHashMap<>();
        BlockPos lootPos = new BlockPos(simOriginX, simOriginY + 4, simOriginZ);
        BlockState oldState = level.getBlockState(lootPos);
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
            Player fakePlayer = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "[PlantSim]"));
            
            LootTable lootTable = level.getServer().reloadableRegistries().getLootTable(block.getLootTable());
            if (lootTable == LootTable.EMPTY) return drops;
            
            int samples = 20;
            double sampleMultiplier = 1.0 / samples;
            boolean injectionWorked = false;
            
            for (int i = 0; i < samples; i++) {
                RandomSource deterministicRandom = RandomSource.create(12345 + i);
                
                LootParams params = new LootParams.Builder(level)
                        .withParameter(LootContextParams.BLOCK_STATE, state)
                        .withParameter(LootContextParams.ORIGIN, Vec3.atLowerCornerOf(lootPos))
                        .withParameter(LootContextParams.THIS_ENTITY, fakePlayer)
                        .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                        .create(LootContextParamSets.BLOCK);
                
                LootContext context = new LootContext.Builder(params).create(Optional.empty());
                
                if (i == 0 || injectionWorked) injectionWorked = injectRandomIntoContext(context, deterministicRandom);
                
                ObjectArrayList<ItemStack> lootDrops = new ObjectArrayList<>();
                lootTable.getRandomItems(context, lootDrops::add);
                
                for (ItemStack stack : lootDrops) addDrop(drops, stack, sampleMultiplier);
            }
        } catch (Throwable e) {
            ComplexityAnalyzer.LOGGER.error("[PlantSim] Loot error for {}: {}", GameRegistryManager.getBlockId(block), e.getMessage());
        } finally {
            level.setBlock(lootPos, oldState, FLAG_NO_UPDATE);
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
    private Block findSuitableGround(Block plantBlock, ServerLevel level) {
        if (groundCache.containsKey(plantBlock)) return groundCache.get(plantBlock);

        BlockPos groundPos = new BlockPos(simOriginX, simOriginY + 3, simOriginZ);
        BlockPos plantPos = new BlockPos(simOriginX, simOriginY + 4, simOriginZ);
        BlockState plantState = plantBlock.defaultBlockState();

        for (Block b : PRIORITY_GROUNDS) if (tryGroundQuickly(b, plantState, level, groundPos, plantPos)) {
            groundCache.put(plantBlock, b);
            return b;
        }
        for (Block b : knownGrounds) if (tryGroundQuickly(b, plantState, level, groundPos, plantPos)) {
            groundCache.put(plantBlock, b);
            return b;
        }
        for (Block b : GameRegistryManager.getAllBlocks()) {
            if (tryGroundQuickly(b, plantState, level, groundPos, plantPos)) {
                knownGrounds.add(b);
                groundCache.put(plantBlock, b);
                return b;
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
            if (bm != null && current.getBlock() == plantBlock && bonemealUses < MAX_BONEMEAL) {
                if (bm.isValidBonemealTarget(level, plantPos, current)) {
                    try {
                        bm.performBonemeal(level, random, plantPos, current);
                        bonemealUses++;
                        continue;
                    } catch (Throwable ignored) {
                    }
                }
            }

            if (current.isRandomlyTicking()) {
                try {
                    current.randomTick(level, plantPos, random);
                } catch (Throwable ignored) {
                }
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
        return level.getBlockState(plantPos).equals(startState) ? 1 : stageCount;
    }

    private int estimateGrowthStages(Block block) {
        IntegerProperty ageProp = findAgeProperty(block);
        if (ageProp == null) return 2;

        if (ageMaxCache.containsKey(block)) {
            int max = ageMaxCache.getInt(block);
            return Math.max(2, max + 1);
        }

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int v : ageProp.getPossibleValues()) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        ageMaxCache.put(block, max);
        return Math.max(2, max - min + 1);
    }

    private void ensurePlatform(ServerLevel level) {
        if (platformReady) return;

        int chunkRadius = (CLEAR_RADIUS >> 4) + 1;
        for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
            for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
                ChunkPos cp = new ChunkPos(originChunkX + cx, originChunkZ + cz);
                level.getChunkSource().addRegionTicket(TicketType.FORCED, cp, 2, cp);
            }
        }

        int range = AREA_RADIUS + 1;
        int maxY = level.getMaxBuildHeight();
        int baseY = simOriginY - 1;
        int lightY = maxY - 1;

        for (int y = baseY; y < maxY; y++) {
            boolean isBaseLayer = (y == baseY);
            boolean isLightLayer = (y == lightY);

            for (int x = -range; x <= AREA_RADIUS; x++) {
                boolean isXBoundary = (x == -range) | (x == AREA_RADIUS);
                int worldX = simOriginX + x;

                for (int z = -range; z <= AREA_RADIUS; z++) {
                    boolean isZBoundary = (z == -range) | (z == AREA_RADIUS);
                    mutablePos.set(worldX, y, simOriginZ + z);

                    try {
                        if (isBaseLayer | isXBoundary | isZBoundary) {
                            level.setBlock(mutablePos, Blocks.BARRIER.defaultBlockState(), 3);
                        } else if (isLightLayer) {
                            level.setBlock(mutablePos, Blocks.LIGHT.defaultBlockState(), 3);
                        } else if (!level.getBlockState(mutablePos).isAir()) {
                            level.setBlock(mutablePos, Blocks.AIR.defaultBlockState(), 3);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        killEntities(level);
        platformReady = true;
    }

    private void killEntities(ServerLevel level) {
        collectAndKillEntities(level, null);
    }

    private void collectAndKillEntities(ServerLevel level, @Nullable Reference2DoubleMap<Item> drops) {
        int r = CLEAR_RADIUS + 2;
        AABB box = new AABB(simOriginX - r, simOriginY - 1, simOriginZ - r,
                simOriginX + r, level.getMaxBuildHeight(), simOriginZ + r);
        boolean shouldCollectDrops = drops != null;
        for (Entity entity : level.getEntities(null, box)) {
            if (entity instanceof ItemEntity itemEntity) if (shouldCollectDrops) {
                ItemStack stack = itemEntity.getItem();
                addDrop(drops, stack, 1.0);
            }
            entity.discard();
        }
    }

    private void collectAndClear(ServerLevel level, @Nullable Reference2DoubleMap<Item> drops) {
        long groundPosLong = BlockPos.asLong(simOriginX, simOriginY + 3, simOriginZ);
        int minY = simOriginY - 1;
        int maxY = Math.min(level.getMaxBuildHeight() - 1, simOriginY + MAX_CLEAR_HEIGHT);
        Player fakePlayer = drops == null ? null : FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "[PlantSim]"));

        boolean shouldCollectDrops = drops != null;

        for (int y = minY; y <= maxY; y++) {
            for (int x = -CLEAR_RADIUS; x <= CLEAR_RADIUS; x++) {
                int worldX = simOriginX + x;
                for (int z = -CLEAR_RADIUS; z <= CLEAR_RADIUS; z++) {
                    mutablePos.set(worldX, y, simOriginZ + z);
                    BlockState state = level.getBlockState(mutablePos);
                    if (state.isAir()) continue;
                    if (isManagedPlatformBlock(state)) continue;

                    BlockPos pos = mutablePos.immutable();
                    if (pos.asLong() != groundPosLong) {
                        if (shouldCollectDrops) try {
                            if (state.is(BlockTags.LEAVES)) addDrop(drops, state.getBlock().asItem(), 1.0);
                            for (ItemStack stack : Block.getDrops(state, level, pos, level.getBlockEntity(pos), fakePlayer, ItemStack.EMPTY)) {
                                addDrop(drops, stack, 1.0);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    level.removeBlock(pos, false);
                }
            }
        }
    }

    private void addDrop(Reference2DoubleMap<Item> drops, ItemStack stack, double multiplier) {
        if (!stack.isEmpty()) addDrop(drops, stack.getItem(), stack.getCount() * multiplier);
    }

    private void addDrop(Reference2DoubleMap<Item> drops, Item item, double amount) {
        if (amount > 0.0) drops.put(item, drops.getDouble(item) + amount);
    }

    private void addDrops(Reference2DoubleMap<Item> target, Reference2DoubleMap<Item> source) {
        for (var entry : source.reference2DoubleEntrySet()) addDrop(target, entry.getKey(), entry.getDoubleValue());
    }

    private void mergeDropEstimates(Reference2DoubleMap<Item> target, Reference2DoubleMap<Item> source) {
        for (var entry : source.reference2DoubleEntrySet())
            if (entry.getDoubleValue() > target.getDouble(entry.getKey()))
                target.put(entry.getKey(), entry.getDoubleValue());
    }

    private String formatDrops(Reference2DoubleMap<Item> drops) {
        if (drops.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (var entry : drops.reference2DoubleEntrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(GameRegistryManager.getItemId(entry.getKey())).append(" x").append(entry.getDoubleValue());
        }
        return sb.append(']').toString();
    }

    private boolean isManagedPlatformBlock(BlockState state) {
        return state.is(Blocks.BARRIER) || state.is(Blocks.LIGHT);
    }

    private IntegerProperty findAgeProperty(Block block) {
        for (Property<?> prop : block.defaultBlockState().getProperties()) {
            if (prop instanceof IntegerProperty ip && (prop.getName().equals("age") || prop.getName().equals("growth")))
                return ip;
        }
        return null;
    }
}
