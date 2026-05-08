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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.jetbrains.annotations.Nullable;

public class PlantSimulator {

    private final Object2ObjectMap<Block, SimulationResult> cache = new Object2ObjectOpenHashMap<>();
    private final ObjectLinkedOpenHashSet<Block> knownGrounds = new ObjectLinkedOpenHashSet<>();
    private static boolean platformReady = false;

    private static final BlockPos SIM_ORIGIN = new BlockPos(20_000_000, 200, 20_000_000);

    private static final int AREA_RADIUS = 10;
    private static final int AREA_HEIGHT = 20;
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
        Object2ObjectMap<Block, SimulationResult> results = new Object2ObjectOpenHashMap<>();
        int total = blocks.size();
        int current = 0;

        for (Block block : blocks) {
            current++;
            ComplexityAnalyzer.LOGGER.info("[PlantSim] [{}/{}] Simulating: {}", current, total, BuiltInRegistries.BLOCK.getKey(block));
            SimulationResult result = simulate(block, level);
            if (result != null) results.put(block, result);
        }
        if (results.isEmpty()) return null;
        return results;
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
        ensurePlatform(level);
        ObjectSet<Block> foundBlocks = new ObjectOpenHashSet<>();
        int stages = 1;

        BlockPos origin = SIM_ORIGIN;
        RandomSource random = RandomSource.create(12345);
        String blockId = BuiltInRegistries.BLOCK.getKey(plantBlock).toString();
        long totalStart = System.currentTimeMillis();

        Object2ObjectMap<BlockPos, BlockState> initialSnapshot = new Object2ObjectOpenHashMap<>();

        try {
            Block ground = findSuitableGround(plantBlock, level, origin);
            if (ground == null) return new SimulationResult(ObjectSets.emptySet(), 1);

            takeAreaSnapshot(level, origin, initialSnapshot);

            level.setBlock(origin, ground.defaultBlockState(), FLAG_NO_UPDATE);
            level.setBlock(origin.below(), ground.defaultBlockState(), FLAG_NO_UPDATE);

            BlockPos plantPos = origin.above();
            level.setBlock(plantPos, plantBlock.defaultBlockState(), FLAG_NO_UPDATE);

            stages = growPlant(plantBlock, level, plantPos, random);
            collectChanges(level, origin, initialSnapshot, foundBlocks);

        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[PlantSim] Failed for {}: {}", blockId, e.getMessage());
        } finally {
            restoreArea(level, initialSnapshot);
        }

        ObjectSet<Item> drops = new ObjectOpenHashSet<>();
        for (Block b : foundBlocks) {
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
            BlockState matureState = block.defaultBlockState();
            for (var prop : matureState.getProperties()) {
                if (prop instanceof IntegerProperty intProp) {
                    if (prop.getName().equals("age") || prop.getName().equals("growth")) {
                        int max = intProp.getPossibleValues().stream().max(Integer::compare).orElse(0);
                        matureState = matureState.setValue(intProp, max);
                    }
                }
            }

            var builder = new LootParams.Builder(level)
                    .withParameter(LootContextParams.BLOCK_STATE, matureState)
                    .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                    .withParameter(LootContextParams.ORIGIN, Vec3.ZERO);

            var params = builder.create(LootContextParamSets.BLOCK);
            var table = level.getServer().reloadableRegistries().getLootTable(block.getLootTable());
            if (table != LootTable.EMPTY) for (int i = 0; i < 50; i++) {
                for (var stack : table.getRandomItems(params)) if (!stack.isEmpty()) drops.add(stack.getItem());
            }
        } catch (Exception ignored) {
        }
        return drops;
    }

    @Nullable
    private Block findSuitableGround(Block plantBlock, ServerLevel level, BlockPos groundPos) {
        BlockPos plantPos = groundPos.above();
        BlockState plantState = plantBlock.defaultBlockState();

        for (Block candidate : PRIORITY_GROUNDS) {
            if (tryGroundQuickly(candidate, plantState, level, groundPos, plantPos)) return candidate;
        }

        for (Block candidate : knownGrounds) {
            if (tryGroundQuickly(candidate, plantState, level, groundPos, plantPos)) return candidate;
        }

        for (Block candidate : BuiltInRegistries.BLOCK) {
            if (tryGroundQuickly(candidate, plantState, level, groundPos, plantPos)) {
                knownGrounds.add(candidate);
                return candidate;
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
        int stages = 1;
        Block lastObserved = plantBlock;
        long simStart = System.currentTimeMillis();

        for (int tick = 0; tick < MAX_TICKS; tick++) {
            if (System.currentTimeMillis() - simStart > MAX_SIMULATION_MS) break;

            BlockState current = level.getBlockState(plantPos);
            Block currentBlock = current.getBlock();

            if (currentBlock != lastObserved) {
                stages++;
                lastObserved = currentBlock;
            }

            if (currentBlock == plantBlock && plantBlock instanceof BonemealableBlock bm && bonemealUses < MAX_BONEMEAL) {
                try {
                    if (bm.isValidBonemealTarget(level, plantPos, current)) {
                        bm.performBonemeal(level, random, plantPos, current);
                        bonemealUses++;
                        continue;
                    }
                } catch (Exception ignored) {
                }
            }

            if (current.isRandomlyTicking()) try {
                current.randomTick(level, plantPos, random);
            } catch (Exception ignored) {
            }

            BlockState after = level.getBlockState(plantPos);
            if (after.isAir()) break;

            IntegerProperty age = findAgeProperty(after.getBlock());
            if (age != null) {
                int val = after.getValue(age);
                int max = age.getPossibleValues().stream().max(Integer::compare).orElse(0);
                if (val >= max) break;
            }
        }

        return Math.max(stages, 1);
    }

    private void takeAreaSnapshot(ServerLevel level, BlockPos origin, Object2ObjectMap<BlockPos, BlockState> snapshot) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = -1; y <= AREA_HEIGHT; y++) {
            for (int x = -AREA_RADIUS; x <= AREA_RADIUS; x++) {
                for (int z = -AREA_RADIUS; z <= AREA_RADIUS; z++) {
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    snapshot.put(pos.immutable(), level.getBlockState(pos));
                }
            }
        }
    }

    private void restoreArea(ServerLevel level, Object2ObjectMap<BlockPos, BlockState> snap) {
        for (var entry : snap.object2ObjectEntrySet()) level.setBlock(entry.getKey(), entry.getValue(), FLAG_NO_UPDATE);
    }

    private void ensurePlatform(ServerLevel level) {
        if (platformReady) return;

        BlockPos origin = SIM_ORIGIN;
        ChunkPos chunkPos = new ChunkPos(origin);
        level.getChunkSource().addRegionTicket(TicketType.FORCED, chunkPos, 2, chunkPos);

        BlockState barrier = Blocks.BARRIER.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState light = Blocks.LIGHT.defaultBlockState();

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int r = AREA_RADIUS + 2;
        int h = AREA_HEIGHT + 2;

        for (int x = -r; x <= r; x++) {
            for (int y = -1; y <= h; y++) {
                for (int z = -r; z <= r; z++) {
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);

                    boolean wall = Math.abs(x) == r || Math.abs(z) == r || y == -1 || y == h;
                    if (wall) {
                        level.setBlock(pos, barrier, 3);
                    } else if (y == h - 1) {
                        level.setBlock(pos, light, 3);
                    } else {
                        level.setBlock(pos, air, 3);
                    }
                }
            }
        }

        platformReady = true;
        ComplexityAnalyzer.LOGGER.info("[PlantSim] Simulation platform secured at {}", origin);
    }

    private void collectChanges(ServerLevel level, BlockPos origin, Object2ObjectMap<BlockPos, BlockState> snapshot, ObjectSet<Block> found) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = -1; y <= AREA_HEIGHT; y++) {
            for (int x = -AREA_RADIUS; x <= AREA_RADIUS; x++) {
                for (int z = -AREA_RADIUS; z <= AREA_RADIUS; z++) {
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (pos.getY() <= origin.getY()) continue;
                    BlockState current = level.getBlockState(pos);
                    BlockState original = snapshot.get(pos);
                    if (current != original && !current.isAir()) found.add(current.getBlock());
                }
            }
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
