package org.complexityanalyzer.analyzer.resource.providers;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.jetbrains.annotations.Nullable;

public class PlantSimulator {

    private final Object2ObjectMap<Block, SimulationResult> cache = new Object2ObjectOpenHashMap<>();
    private final ObjectLinkedOpenHashSet<Block> knownGrounds = new ObjectLinkedOpenHashSet<>();

    private static final int AREA_RADIUS = 6;
    private static final int AREA_HEIGHT = 16;
    private static final int MAX_TICKS = 300;
    private static final int MAX_BONEMEAL = 20;
    private static final int STAGNATION_LIMIT = 30;
    private static final int MAX_FRONTIER_SIZE = 500;
    private static final long MAX_SIMULATION_MS = 2000;
    private static final int FLAG_NO_UPDATE = 2 | 16;

    public record SimulationResult(ObjectSet<Item> drops, int growthStages) {
    }

    public SimulationResult simulate(Block plantBlock, ServerLevel level) {
        var cached = cache.get(plantBlock);
        if (cached != null) return cached;

        var result = runSimulation(plantBlock, level);
        cache.put(plantBlock, result);
        return result;
    }

    public boolean isNotPlant(Block block) {
        BlockState state = block.defaultBlockState();
        boolean candidate = (block instanceof BonemealableBlock) || state.isRandomlyTicking() || findAgeProperty(block) != null;
        if (!candidate) return true;
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private SimulationResult runSimulation(Block plantBlock, ServerLevel level) {
        ObjectSet<Block> foundBlocks = new ObjectOpenHashSet<>();
        Object2ObjectMap<BlockPos, BlockState> changed = new Object2ObjectOpenHashMap<>();
        int stages = 1;

        BlockPos origin = new BlockPos(0, -60, 0);
        RandomSource random = RandomSource.create(12345);
        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(plantBlock).toString();
        long totalStart = System.currentTimeMillis();

        try {
            long t0 = System.currentTimeMillis();
            long t1 = System.currentTimeMillis();

            Block ground = findSuitableGround(plantBlock, level, origin, changed);
            long t2 = System.currentTimeMillis();
            ComplexityAnalyzer.LOGGER.debug("[PlantSim] {}: clear={}ms findGround={}ms ground={}",
                    blockId, t1 - t0, t2 - t1, ground != null ? net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(ground) : "null");
            if (ground == null) {
                restoreChanged(level, changed);
                return new SimulationResult(ObjectSets.emptySet(), 1);
            }

            prepareGround(level, origin, ground, changed);

            BlockPos plantPos = origin.above();
            BlockState plantState = plantBlock.defaultBlockState();
            setTracked(level, plantPos, plantState, changed);

            long t3 = System.currentTimeMillis();
            stages = growPlant(plantBlock, level, plantPos, random, changed);
            long t4 = System.currentTimeMillis();
            collectChangedBlocks(level, changed, plantBlock, ground, foundBlocks);
            long t5 = System.currentTimeMillis();
            restoreChanged(level, changed);
            long t6 = System.currentTimeMillis();
            ComplexityAnalyzer.LOGGER.debug("[PlantSim] {}: grow={}ms scan={}ms clear2={}ms stages={} foundBlocks={} TOTAL={}ms",
                    blockId, t4 - t3, t5 - t4, t6 - t5, stages, foundBlocks.size(), t6 - totalStart);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[PlantSim] Failed for {}: {}", blockId, e.getMessage());
            restoreChanged(level, changed);
        }

        ObjectSet<Item> drops = new ObjectOpenHashSet<>();
        for (Block b : foundBlocks) if (b != null && b != plantBlock) drops.add(b.asItem());

        return new SimulationResult(drops, stages);
    }

    @Nullable
    private Block findSuitableGround(Block plantBlock, ServerLevel level, BlockPos groundPos, Object2ObjectMap<BlockPos, BlockState> changed) {
        BlockPos plantPos = groundPos.above();
        BlockState plantState = plantBlock.defaultBlockState();

        for (Block candidate : knownGrounds) {
            if (tryGround(candidate, plantState, level, groundPos, plantPos, changed)) return candidate;
        }

        int tried = 0;
        for (Block candidate : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (knownGrounds.contains(candidate)) continue;
            tried++;
            if (tryGround(candidate, plantState, level, groundPos, plantPos, changed)) {
                knownGrounds.add(candidate);
                ComplexityAnalyzer.LOGGER.debug("[PlantSim] New ground found after {} tries: {}", tried,
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(candidate));
                return candidate;
            }
        }
        return null;
    }

    private boolean tryGround(Block candidate, BlockState plantState, ServerLevel level, BlockPos groundPos, BlockPos plantPos, Object2ObjectMap<BlockPos, BlockState> changed) {
        try {
            BlockState candidateState = candidate.defaultBlockState();
            if (!candidateState.isSolid() && candidate != Blocks.WATER) return false;

            BlockState oldGround = level.getBlockState(groundPos);
            BlockState oldPlant = level.getBlockState(plantPos);
            level.setBlock(groundPos, candidateState, FLAG_NO_UPDATE);
            level.setBlock(plantPos, Blocks.AIR.defaultBlockState(), FLAG_NO_UPDATE);

            boolean survives = plantState.canSurvive(level, plantPos);

            level.setBlock(groundPos, oldGround, FLAG_NO_UPDATE);
            level.setBlock(plantPos, oldPlant, FLAG_NO_UPDATE);

            return survives;
        } catch (Exception ignored) {
            return false;
        }
    }

    private int growPlant(Block plantBlock, ServerLevel level, BlockPos plantPos, RandomSource random, Object2ObjectMap<BlockPos, BlockState> changed) {
        int bonemealUses = 0;
        int stages = 1;
        int stagnationCounter = 0;
        Block lastObserved = plantBlock;
        long simStart = System.currentTimeMillis();

        ObjectOpenHashSet<BlockPos> frontier = new ObjectOpenHashSet<>();
        frontier.add(plantPos.immutable());

        for (int tick = 0; tick < MAX_TICKS; tick++) {
            if (System.currentTimeMillis() - simStart > MAX_SIMULATION_MS) {
                ComplexityAnalyzer.LOGGER.warn("[PlantSim] Timeout after {} ticks, frontier={}", tick, frontier.size());
                break;
            }
            if (frontier.size() > MAX_FRONTIER_SIZE) {
                ComplexityAnalyzer.LOGGER.warn("[PlantSim] Frontier limit reached ({}), stopping", frontier.size());
                break;
            }
            BlockState current = level.getBlockState(plantPos);
            Block currentBlock = current.getBlock();

            if (currentBlock != lastObserved) {
                stages++;
                lastObserved = currentBlock;
            }

            if (currentBlock == plantBlock && plantBlock instanceof BonemealableBlock bm && bonemealUses < MAX_BONEMEAL) {
                try {
                    if (bm.isValidBonemealTarget(level, plantPos, current)) {
                        Object2ObjectMap<BlockPos, BlockState> before = snapshotChangedArea(level, plantPos);
                        bm.performBonemeal(level, random, plantPos, current);
                        recordChanged(level, before, changed);
                        bonemealUses++;
                        stagnationCounter = 0;
                        expandFrontier(frontier, level, plantPos);
                        continue;
                    }
                } catch (Exception ignored) {
                }
            }

            int snapshotSize = frontier.size();
            try {
                tickFrontier(level, frontier, random, changed);
            } catch (Exception ignored) {
            }

            if (frontier.size() == snapshotSize) {
                stagnationCounter++;
                if (stagnationCounter >= STAGNATION_LIMIT) break;
            } else {
                stagnationCounter = 0;
            }

            BlockState afterTick = level.getBlockState(plantPos);
            if (afterTick.getBlock() != plantBlock && !isStillSameFamily(plantBlock, afterTick.getBlock())) break;
        }

        return Math.max(stages, 1);
    }

    private boolean isStillSameFamily(Block original, Block current) {
        if (current == Blocks.AIR) return false;
        return current == original;
    }

    private void expandFrontier(ObjectOpenHashSet<BlockPos> frontier, ServerLevel level, BlockPos center) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = -1; y <= AREA_HEIGHT; y++) {
            for (int x = -AREA_RADIUS; x <= AREA_RADIUS; x++) {
                for (int z = -AREA_RADIUS; z <= AREA_RADIUS; z++) {
                    pos.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (!level.getBlockState(pos).isAir()) {
                        frontier.add(pos.immutable());
                    }
                }
            }
        }
    }

    private void tickFrontier(ServerLevel level, ObjectOpenHashSet<BlockPos> frontier, RandomSource random, Object2ObjectMap<BlockPos, BlockState> changed) {
        ObjectArrayList<BlockPos> snapshot = new ObjectArrayList<>(frontier);
        ObjectOpenHashSet<BlockPos> newlyOccupied = new ObjectOpenHashSet<>();

        for (BlockPos p : snapshot) {
            BlockState state = level.getBlockState(p);
            if (state.isRandomlyTicking()) {
                try {
                    state.randomTick(level, p, random);
                    if (level.getBlockState(p) != state) changed.putIfAbsent(p.immutable(), state);
                } catch (Exception ignored) {
                }
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos np = p.offset(dx, dy, dz);
                        if (frontier.contains(np) || newlyOccupied.contains(np)) continue;
                        if (!level.getBlockState(np).isAir()) newlyOccupied.add(np);
                    }
                }
            }
        }
        frontier.addAll(newlyOccupied);
    }

    private void prepareGround(ServerLevel level, BlockPos origin, Block ground, Object2ObjectMap<BlockPos, BlockState> changed) {
        BlockState groundState = ground.defaultBlockState();
        setTracked(level, origin, groundState, changed);
        setTracked(level, origin.below(), groundState, changed);
    }

    private void collectChangedBlocks(ServerLevel level, Object2ObjectMap<BlockPos, BlockState> changed, Block plantedBlock, Block ground, ObjectSet<Block> found) {
        for (BlockPos pos : changed.keySet()) {
            BlockState state = level.getBlockState(pos);
            BlockState original = changed.get(pos);
            if (state == original) continue;

            Block b = state.getBlock();
            if (b == Blocks.AIR || b == ground) continue;
            if (b == plantedBlock) {
                IntegerProperty age = findAgeProperty(b);
                if (age != null) {
                    int max = state.getValue(age);
                    int maxPossible = age.getPossibleValues().stream().max(Integer::compare).orElse(0);
                    if (max == maxPossible) found.add(b);
                }
                continue;
            }
            found.add(b);
        }
    }

    private void setTracked(ServerLevel level, BlockPos pos, BlockState state, Object2ObjectMap<BlockPos, BlockState> changed) {
        BlockPos immutable = pos.immutable();
        changed.putIfAbsent(immutable, level.getBlockState(immutable));
        level.setBlock(immutable, state, FLAG_NO_UPDATE);
    }

    private void restoreChanged(ServerLevel level, Object2ObjectMap<BlockPos, BlockState> changed) {
        for (var entry : changed.object2ObjectEntrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), FLAG_NO_UPDATE);
        }
        changed.clear();
    }

    private Object2ObjectMap<BlockPos, BlockState> snapshotChangedArea(ServerLevel level, BlockPos center) {
        Object2ObjectMap<BlockPos, BlockState> snapshot = new Object2ObjectOpenHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = -1; y <= AREA_HEIGHT; y++) {
            for (int x = -AREA_RADIUS; x <= AREA_RADIUS; x++) {
                for (int z = -AREA_RADIUS; z <= AREA_RADIUS; z++) {
                    pos.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir()) snapshot.put(pos.immutable(), state);
                }
            }
        }
        return snapshot;
    }

    private void recordChanged(ServerLevel level, Object2ObjectMap<BlockPos, BlockState> before, Object2ObjectMap<BlockPos, BlockState> changed) {
        for (var entry : before.object2ObjectEntrySet()) {
            BlockPos pos = entry.getKey();
            if (level.getBlockState(pos) != entry.getValue()) {
                changed.putIfAbsent(pos, entry.getValue());
            }
        }
    }

    private IntegerProperty findAgeProperty(Block block) {
        BlockState state = block.defaultBlockState();
        for (Property<?> prop : state.getProperties()) {
            if (prop instanceof IntegerProperty intProp && intProp.getPossibleValues().size() > 1) return intProp;
        }
        return null;
    }
}
