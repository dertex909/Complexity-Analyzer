package org.complexityanalyzer.analyzer.resource.providers;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import com.mojang.authlib.GameProfile;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.UUID;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.jetbrains.annotations.Nullable;

public class PlantSimulator {

    private final Object2ObjectMap<Block, SimulationResult> cache = new Object2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private final ObjectLinkedOpenHashSet<Block> knownGrounds = new ObjectLinkedOpenHashSet<>();
    private boolean platformReady = false;

    private static final BlockPos SIM_ORIGIN = new BlockPos(20_000_000, 200, 20_000_000);
    private static final int AREA_RADIUS = 16;
    private static final int CLEAR_RADIUS = 64;
    private static final int MAX_CLEAR_HEIGHT = 160;
    private static final int MAX_TICKS = 100;
    private static final int MAX_BONEMEAL = 16;
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
            ComplexityAnalyzer.LOGGER.info("[PlantSim] [{}/{}] Simulating: {}", current, blocks.size(), BuiltInRegistries.BLOCK.getKey(block));
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

                BlockPos groundPos = SIM_ORIGIN.above(3);
                BlockPos plantPos = groundPos.above();

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
                    ComplexityAnalyzer.LOGGER.debug("[PlantSim] Skipping {} after 3 attempts", BuiltInRegistries.BLOCK.getKey(plantBlock));
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
                BuiltInRegistries.BLOCK.getKey(plantBlock), stages, formatDrops(drops), System.currentTimeMillis() - totalStart);

        return new SimulationResult(drops, stages);
    }

    private Reference2DoubleMap<Item> simulateMatureLoot(Block block, ServerLevel level) {
        Reference2DoubleMap<Item> drops = new Reference2DoubleOpenHashMap<>();
        BlockPos lootPos = SIM_ORIGIN.above(4);
        BlockState oldState = level.getBlockState(lootPos);
        try {
            BlockState state = block.defaultBlockState();
            IntegerProperty ageProp = findAgeProperty(block);
            if (ageProp != null) {
                int max = 0;
                for (int v : ageProp.getPossibleValues()) if (v > max) max = v;
                state = state.setValue(ageProp, max);
            }

            level.setBlock(lootPos, state, FLAG_NO_UPDATE);
            Player fakePlayer = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "[PlantSim]"));
            int samples = 50;
            for (int i = 0; i < samples; i++) {
                for (ItemStack stack : Block.getDrops(state, level, lootPos, level.getBlockEntity(lootPos), fakePlayer, ItemStack.EMPTY)) {
                    addDrop(drops, stack, 1.0 / samples);
                }
            }
        } catch (Throwable e) {
            ComplexityAnalyzer.LOGGER.error("[PlantSim] Loot error for {}: {}", block, e.getMessage());
        } finally {
            level.setBlock(lootPos, oldState, FLAG_NO_UPDATE);
        }
        return drops;
    }

    @Nullable
    private Block findSuitableGround(Block plantBlock, ServerLevel level) {
        BlockPos groundPos = SIM_ORIGIN.above(3);
        BlockPos plantPos = groundPos.above();
        BlockState plantState = plantBlock.defaultBlockState();

        for (Block b : PRIORITY_GROUNDS) if (tryGroundQuickly(b, plantState, level, groundPos, plantPos)) return b;
        for (Block b : knownGrounds) if (tryGroundQuickly(b, plantState, level, groundPos, plantPos)) return b;
        for (Block b : BuiltInRegistries.BLOCK) {
            if (tryGroundQuickly(b, plantState, level, groundPos, plantPos)) {
                knownGrounds.add(b);
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
                int max = 0;
                for (int v : ageProp.getPossibleValues()) if (v > max) max = v;
                if (val >= max) break;
            }
        }
        return level.getBlockState(plantPos).equals(startState) ? 1 : stageCount;
    }

    private int estimateGrowthStages(Block block) {
        IntegerProperty ageProp = findAgeProperty(block);
        if (ageProp == null) return 2;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int v : ageProp.getPossibleValues()) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        return Math.max(2, max - min + 1);
    }

    private void ensurePlatform(ServerLevel level) {
        if (platformReady) return;
        int chunkRadius = (CLEAR_RADIUS >> 4) + 1;
        int originChunkX = SIM_ORIGIN.getX() >> 4;
        int originChunkZ = SIM_ORIGIN.getZ() >> 4;
        for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
            for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
                ChunkPos cp = new ChunkPos(originChunkX + cx, originChunkZ + cz);
                level.getChunkSource().addRegionTicket(TicketType.FORCED, cp, 2, cp);
            }
        }
        int range = AREA_RADIUS + 1;
        for (int y = SIM_ORIGIN.getY() - 1; y < level.getMaxBuildHeight(); y++) {
            for (int x = -range; x <= AREA_RADIUS; x++) {
                for (int z = -range; z <= AREA_RADIUS; z++) {
                    mutablePos.set(SIM_ORIGIN.getX() + x, y, SIM_ORIGIN.getZ() + z);
                    try {
                        if (y == SIM_ORIGIN.getY() - 1 || x == -range || x == AREA_RADIUS || z == -range || z == AREA_RADIUS) {
                            level.setBlock(mutablePos, Blocks.BARRIER.defaultBlockState(), 3);
                        } else if (y == level.getMaxBuildHeight() - 1) {
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
        AABB box = new AABB(SIM_ORIGIN.getX() - r, SIM_ORIGIN.getY() - 1, SIM_ORIGIN.getZ() - r,
                SIM_ORIGIN.getX() + r, level.getMaxBuildHeight(), SIM_ORIGIN.getZ() + r);
        for (Entity entity : level.getEntities(null, box)) {
            if (entity instanceof ItemEntity itemEntity) {
                ItemStack stack = itemEntity.getItem();
                if (drops != null) addDrop(drops, stack, 1.0);
            }
            entity.discard();
        }
    }

    private void collectAndClear(ServerLevel level, @Nullable Reference2DoubleMap<Item> drops) {
        long groundPosLong = SIM_ORIGIN.above(3).asLong();
        int minY = SIM_ORIGIN.getY() - 1;
        int maxY = Math.min(level.getMaxBuildHeight() - 1, SIM_ORIGIN.getY() + MAX_CLEAR_HEIGHT);
        Player fakePlayer = drops == null ? null : FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "[PlantSim]"));

        for (int y = minY; y <= maxY; y++) {
            for (int x = -CLEAR_RADIUS; x <= CLEAR_RADIUS; x++) {
                for (int z = -CLEAR_RADIUS; z <= CLEAR_RADIUS; z++) {
                    mutablePos.set(SIM_ORIGIN.getX() + x, y, SIM_ORIGIN.getZ() + z);
                    BlockState state = level.getBlockState(mutablePos);
                    if (state.isAir()) continue;
                    if (isManagedPlatformBlock(state)) continue;

                    BlockPos pos = mutablePos.immutable();
                    if (pos.asLong() != groundPosLong) if (drops != null) try {
                        for (ItemStack stack : Block.getDrops(state, level, pos, level.getBlockEntity(pos), fakePlayer, ItemStack.EMPTY)) {
                            addDrop(drops, stack, 1.0);
                        }
                    } catch (Throwable ignored) {
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
            sb.append(BuiltInRegistries.ITEM.getKey(entry.getKey())).append(" x").append(entry.getDoubleValue());
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
