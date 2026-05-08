package org.complexityanalyzer.analyzer.resource.providers;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.jetbrains.annotations.Nullable;

public class PlantSimulator {

    private final Object2ObjectMap<Block, SimulationResult> cache = new Object2ObjectOpenHashMap<>();
    private final LongArrayList toClearBuffer = new LongArrayList(4096);
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private final ObjectSet<Block> foundBuffer = new ObjectOpenHashSet<>(256);
    private final LevelChunk[][] chunkCache = new LevelChunk[2][2];
    private final ObjectLinkedOpenHashSet<Block> knownGrounds = new ObjectLinkedOpenHashSet<>();
    private boolean platformReady = false;

    private static final BlockPos SIM_ORIGIN = new BlockPos(20_000_000, 200, 20_000_000);

    private static final int AREA_RADIUS = 16;
    private static final int MAX_TICKS = 100;
    private static final int MAX_BONEMEAL = 8;
    private static final long MAX_SIMULATION_MS = 2000;
    private static final int FLAG_NO_UPDATE = 2 | 16;

    private static final ObjectList<Block> PRIORITY_GROUNDS = ObjectArrayList.wrap(new Block[]{
            Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.FARMLAND, Blocks.SAND,
            Blocks.RED_SAND, Blocks.GRAVEL, Blocks.NETHERRACK, Blocks.SOUL_SAND,
            Blocks.SOUL_SOIL, Blocks.END_STONE, Blocks.WATER
    });

    public record SimulationResult(ObjectSet<Item> drops, int growthStages) {
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
        Object2ObjectMap<Block, SimulationResult> results = new Object2ObjectOpenHashMap<>();
        int total = blocks.size();
        int current = 0;

        for (Block block : blocks) {
            current++;
            ComplexityAnalyzer.LOGGER.info("[PlantSim] [{}/{}] Simulating: {}", current, total, BuiltInRegistries.BLOCK.getKey(block));
            SimulationResult result = simulate(block, level);
            if (result != null) results.put(block, result);
        }

        hardClearArea(level, SIM_ORIGIN);
        killEntities(level, SIM_ORIGIN);

        return results.isEmpty() ? null : results;
    }

    private void hardClearArea(ServerLevel level, BlockPos origin) {
        int maxHeight = level.getMaxBuildHeight() - 1;
        BlockState air = Blocks.AIR.defaultBlockState();
        LevelChunkSection[] layerSecs = new LevelChunkSection[4];

        for (int y = origin.getY(); y < maxHeight; y++) {
            int secY = level.getSectionIndex(y);
            int relY = y & 15;

            for (int j = 0; j < 4; j++) layerSecs[j] = chunkCache[j & 1][j >> 1].getSections()[secY];

            if (layerSecs[0].hasOnlyAir() && layerSecs[1].hasOnlyAir() &&
                    layerSecs[2].hasOnlyAir() && layerSecs[3].hasOnlyAir()) continue;

            for (int i = 0; i < 1024; i++) {
                int x = (i & 31) - 16, z = (i >> 5) - 16;
                int cIdx = ((x >> 4) + 1) | (((z >> 4) + 1) << 1);
                LevelChunkSection section = layerSecs[cIdx];

                if (!section.hasOnlyAir() && !section.getBlockState(x & 15, relY, z & 15).isAir()) {
                    mutablePos.set(origin.getX() + x, y, origin.getZ() + z);
                    level.setBlock(mutablePos, air, FLAG_NO_UPDATE);
                }
            }
        }
    }

    public boolean isNotPlant(Block block) {
        BlockState state = block.defaultBlockState();
        String id = BuiltInRegistries.BLOCK.getKey(block).toString();

        if (state.isAir()) {
            ComplexityAnalyzer.LOGGER.debug("[PlantSim] {} skipped: is air", id);
            return true;
        }

        if (block == Blocks.AIR || block == Blocks.FIRE || block == Blocks.SNOW || block == Blocks.TURTLE_EGG) {
            ComplexityAnalyzer.LOGGER.debug("[PlantSim] {} skipped: blacklisted technical block", id);
            return true;
        }

        if (state.is(BlockTags.CROPS) || state.is(BlockTags.SAPLINGS)) {
            ComplexityAnalyzer.LOGGER.debug("[PlantSim] {} accepted: plant tag found", id);
            return false;
        }

        float hardness = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (hardness > 0.5f || hardness < 0.0f) {
            ComplexityAnalyzer.LOGGER.debug("[PlantSim] {} skipped: too hard (hardness={})", id, hardness);
            return true;
        }

        boolean isBonemealable = (block instanceof BonemealableBlock);
        boolean isRandomTicking = state.isRandomlyTicking();
        IntegerProperty ageProp = findAgeProperty(block);

        if (!isBonemealable && !isRandomTicking && ageProp == null) {
            ComplexityAnalyzer.LOGGER.debug("[PlantSim] {} skipped: no growth properties (age, ticking, bonemeal)", id);
            return true;
        }

        if (state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
            ComplexityAnalyzer.LOGGER.debug("[PlantSim] {} skipped: full block collision", id);
            return true;
        }

        ComplexityAnalyzer.LOGGER.debug("[PlantSim] {} accepted: hardness={}, ticking={}, bonemeal={}, ageProp={}",
                id, hardness, isRandomTicking, isBonemealable, (ageProp != null ? ageProp.getName() : "none"));
        return false;
    }

    private synchronized SimulationResult runSimulation(Block plantBlock, ServerLevel level) {
        foundBuffer.clear();
        int stages = 1;

        BlockPos origin = SIM_ORIGIN;
        RandomSource random = RandomSource.create(12345);
        String blockId = BuiltInRegistries.BLOCK.getKey(plantBlock).toString();
        long totalStart = System.currentTimeMillis();

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                long startPhase = System.currentTimeMillis();
                killEntities(level, origin);
                long killTime = System.currentTimeMillis() - startPhase;

                startPhase = System.currentTimeMillis();
                Block ground = findSuitableGround(plantBlock, level, origin);
                if (ground == null) return null;

                BlockPos groundPos = origin.above(3);
                BlockPos plantPos = groundPos.above();

                level.setBlock(groundPos, ground.defaultBlockState(), FLAG_NO_UPDATE);
                level.setBlock(plantPos, plantBlock.defaultBlockState(), FLAG_NO_UPDATE);
                long setupTime = System.currentTimeMillis() - startPhase;

                startPhase = System.currentTimeMillis();
                stages = growPlant(plantBlock, level, plantPos, random);
                long growTime = System.currentTimeMillis() - startPhase;

                startPhase = System.currentTimeMillis();
                collectAndClear(level, origin, foundBuffer);
                killEntities(level, origin);
                long cleanupTime = System.currentTimeMillis() - startPhase;

                ComplexityAnalyzer.LOGGER.debug("[PlantSim] {} metrics: kill={}ms, setup={}ms, grow={}ms, cleanup={}ms (attempt {})",
                        blockId, killTime, setupTime, growTime, cleanupTime, attempt + 1);

                break; // Успех
            } catch (Throwable t) {
                if (attempt == 2) {
                    ComplexityAnalyzer.LOGGER.debug("[PlantSim] Skipping {} after 3 failed attempts: {}", blockId, t.getMessage());
                } else {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }

        ObjectSet<Item> drops = new ObjectOpenHashSet<>();
        for (Block b : foundBuffer) {
            if (b != null && b != Blocks.AIR) {
                Item it = b.asItem();
                if (it != Items.AIR) drops.add(it);
            }
        }

        drops.addAll(simulateMatureLoot(plantBlock, level));

        if (stages <= 1 || drops.isEmpty()) return null;
        ComplexityAnalyzer.LOGGER.debug("[PlantSim] {}: stages={} drops={} TOTAL={}ms",
                blockId, stages, drops.size(), System.currentTimeMillis() - totalStart);

        return new SimulationResult(drops, stages);
    }

    private ObjectSet<Item> simulateMatureLoot(Block block, ServerLevel level) {
        ObjectSet<Item> drops = new ObjectOpenHashSet<>();
        try {
            BlockState state = block.defaultBlockState();
            for (Property<?> prop : state.getProperties()) {
                if (prop instanceof IntegerProperty ip && (prop.getName().equals("age") || prop.getName().equals("growth"))) {
                    int max = 0;
                    for (int v : ip.getPossibleValues()) if (v > max) max = v;
                    state = state.setValue(ip, max);
                }
            }

            LootParams params = new LootParams.Builder(level)
                    .withParameter(LootContextParams.BLOCK_STATE, state)
                    .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                    .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                    .create(LootContextParamSets.BLOCK);

            LootTable table = level.getServer().reloadableRegistries().getLootTable(block.getLootTable());
            if (table != LootTable.EMPTY) for (int i = 0; i < 50; i++) {
                for (ItemStack stack : table.getRandomItems(params)) if (!stack.isEmpty()) drops.add(stack.getItem());
            }
        } catch (Throwable ignored) {
        }
        return drops;
    }

    @Nullable
    private Block findSuitableGround(Block plantBlock, ServerLevel level, BlockPos origin) {
        BlockPos groundPos = origin.above(3);
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
        long simStart = System.currentTimeMillis();
        BonemealableBlock bm = (plantBlock instanceof BonemealableBlock b) ? b : null;

        for (int tick = 0; tick < MAX_TICKS; tick++) {
            if ((tick & 7) == 0 && System.currentTimeMillis() - simStart > MAX_SIMULATION_MS) break;

            BlockState current = level.getBlockState(plantPos);
            Block currentBlock = current.getBlock();

            if (bm != null && currentBlock == plantBlock && bonemealUses < MAX_BONEMEAL) {
                if (bm.isValidBonemealTarget(level, plantPos, current)) try {
                    bm.performBonemeal(level, random, plantPos, current);
                    bonemealUses++;
                    continue;
                } catch (Throwable ignored) {
                }
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
                int max = 0;
                for (int v : ageProp.getPossibleValues()) if (v > max) max = v;
                if (val >= max) break;
            }
        }

        return level.getBlockState(plantPos).equals(startState) ? 1 : 2;
    }

    private void ensurePlatform(ServerLevel level) {
        if (platformReady) return;
        BlockPos origin = SIM_ORIGIN;
        int minY = origin.getY(), maxY = level.getMaxBuildHeight();

        for (int i = 0; i < 4; i++) {
            ChunkPos cp = new ChunkPos((origin.getX() >> 4) + (i & 1), (origin.getZ() >> 4) + (i >> 1));
            level.getChunkSource().addRegionTicket(TicketType.FORCED, cp, 2, cp);
        }

        BlockState barrier = Blocks.BARRIER.defaultBlockState(), air = Blocks.AIR.defaultBlockState(), light = Blocks.LIGHT.defaultBlockState();
        int range = AREA_RADIUS + 1;
        for (int y = minY - 1; y < maxY; y++) {
            boolean isFloor = (y == minY - 1);
            boolean isCeiling = (y == maxY - 1);
            for (int x = -range; x <= AREA_RADIUS; x++) {
                for (int z = -range; z <= AREA_RADIUS; z++) {
                    mutablePos.set(origin.getX() + x, y, origin.getZ() + z);
                    boolean isWall = (x == -range || x == AREA_RADIUS || z == -range || z == AREA_RADIUS);
                    if (isFloor || isWall) level.setBlock(mutablePos, barrier, 3);
                    else if (isCeiling) level.setBlock(mutablePos, light, 3);
                    else if (!level.getBlockState(mutablePos).isAir()) level.setBlock(mutablePos, air, 3);
                }
            }
        }

        for (int i = 0; i < 4; i++) {
            chunkCache[i & 1][i >> 1] = level.getChunk((origin.getX() >> 4) + (i & 1) - 1, (origin.getZ() >> 4) + (i >> 1) - 1);
        }

        hardClearArea(level, origin);
        killEntities(level, origin);

        platformReady = true;
        ComplexityAnalyzer.LOGGER.info("[PlantSim] 2x2 Chunk Simulation Tower secured at {} (Y: {} to {})", origin, minY, maxY);
    }

    private void killEntities(ServerLevel level, BlockPos origin) {
        int r = AREA_RADIUS + 1;
        AABB box = new AABB(origin.getX() - r, origin.getY(), origin.getZ() - r,
                origin.getX() + r, level.getMaxBuildHeight(), origin.getZ() + r);
        for (Entity entity : level.getEntities(null, box)) entity.discard();
    }

    private void collectAndClear(ServerLevel level, BlockPos origin, ObjectSet<Block> found) {
        long groundPosLong = origin.above(3).asLong();
        int minY = origin.getY(), maxY = level.getMaxBuildHeight() - 1;
        BlockState air = Blocks.AIR.defaultBlockState();
        toClearBuffer.clear();
        int emptyLayers = 0;
        LevelChunkSection[] layerSecs = new LevelChunkSection[4];

        for (int y = minY; y < maxY; y++) {
            int secY = level.getSectionIndex(y);
            int relY = y & 15;

            for (int j = 0; j < 4; j++) layerSecs[j] = chunkCache[j & 1][j >> 1].getSections()[secY];

            boolean layerFound = false;
            boolean allEmpty = layerSecs[0].hasOnlyAir() && layerSecs[1].hasOnlyAir() &&
                    layerSecs[2].hasOnlyAir() && layerSecs[3].hasOnlyAir();

            if (!allEmpty) {
                for (int i = 0; i < 1024; i++) {
                    int x = (i & 31) - 16, z = (i >> 5) - 16;
                    int cIdx = ((x >> 4) + 1) | (((z >> 4) + 1) << 1);
                    LevelChunkSection section = layerSecs[cIdx];
                    if (section.hasOnlyAir()) continue;

                    BlockState state = section.getBlockState(x & 15, relY, z & 15);
                    if (!state.isAir()) {
                        layerFound = true;
                        int worldX = origin.getX() + x, worldZ = origin.getZ() + z;
                        long currentLong = BlockPos.asLong(worldX, y, worldZ);
                        if (found != null && currentLong != groundPosLong) found.add(state.getBlock());
                        toClearBuffer.add(currentLong);
                    }
                }
            }

            if (layerFound) {
                emptyLayers = 0;
            } else if (y > origin.getY() + 3) {
                if (++emptyLayers > 10) break;
            }
        }

        for (int i = 0; i < toClearBuffer.size(); i++) {
            level.setBlock(BlockPos.of(toClearBuffer.getLong(i)), air, FLAG_NO_UPDATE);
        }
    }

    private IntegerProperty findAgeProperty(Block block) {
        BlockState state = block.defaultBlockState();
        for (Property<?> prop : state.getProperties()) {
            if (prop instanceof IntegerProperty intProp && (prop.getName().equals("age") || prop.getName().equals("moisture"))) {
                return intProp;
            }
        }
        return null;
    }
}
