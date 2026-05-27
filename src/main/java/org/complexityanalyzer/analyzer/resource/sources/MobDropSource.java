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

package org.complexityanalyzer.analyzer.resource.sources;

import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.data.MobDropData;
import org.complexityanalyzer.analyzer.resource.providers.DimensionRarityAnalyzer;
import org.complexityanalyzer.analyzer.resource.providers.MobPropertyProvider;
import org.complexityanalyzer.analyzer.resource.providers.MobRarityCalculator;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.mixin.LootContextAccessor;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.logging.log4j.Level.WARN;

public class MobDropSource implements IResourceSource {
    private final MobPropertyProvider mobProvider;
    private final Reference2ObjectMap<Item, ObjectList<MobDropData>> dropMap = new Reference2ObjectOpenHashMap<>();
    private static final int SIMULATION_COUNT = 500;

    private static final ReferenceSet<EntityType<?>> SPECIAL_KILL_ENTITIES = new ReferenceOpenHashSet<>(new EntityType<?>[]{
            EntityType.WITHER,
            EntityType.ENDER_DRAGON,
            EntityType.SHULKER
    });

    public MobDropSource(MobPropertyProvider mobProvider, Level level) {
        this.mobProvider = mobProvider;
        DimensionRarityAnalyzer dimensionAnalyzer = new DimensionRarityAnalyzer(level);
        MobRarityCalculator rarityCalculator = new MobRarityCalculator(dimensionAnalyzer);
        mobProvider.setRarityCalculator(rarityCalculator);
    }

    private static class LootFunctionFilter extends AbstractFilter {
        @Override
        public Result filter(LogEvent event) {
            if (event == null || event.getLevel() != WARN) return Result.NEUTRAL;
            String loggerName = event.getLoggerName();
            if (loggerName != null && loggerName.startsWith("net.minecraft.world.level.storage.loot.functions.")) {
                String message = event.getMessage().getFormattedMessage();
                if (message != null && (message.contains("Couldn't set damage") || message.contains("Couldn't smelt")
                        || message.contains("Couldn't find a compatible enchantment"))) return Result.DENY;
            }
            return Result.NEUTRAL;
        }
    }

    @Override
    public void initialize(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            ComplexityAnalyzer.LOGGER.error("[MobDropSource] Initialize called with non-server level. Aborting.");
            return;
        }

        MinecraftServer server = serverLevel.getServer();

        ObjectList<EntityType<?>> entityTypes;
        try {
            entityTypes = CompletableFuture.supplyAsync(() -> {
                var types = new ObjectArrayList<EntityType<?>>();
                GameRegistryManager.getAllEntityTypes().forEach(types::add);
                return types;
            }, server).join();
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[MobDropSource] Failed to get entity types from server thread. Aborting.", e);
            return;
        }

        processMobDrops(serverLevel, entityTypes);
    }

    private void processMobDrops(ServerLevel serverLevel, ObjectList<EntityType<?>> entityTypes) {
        MinecraftServer server = serverLevel.getServer();

        ComplexityAnalyzer.LOGGER.debug("Initializing MobDropSource by simulating mob loot tables in parallel...");
        long startTime = System.currentTimeMillis();

        registerSpecialKillDrops();

        LootFunctionFilter filter = new LootFunctionFilter();
        Logger rootLogger = (Logger) LogManager.getRootLogger();
        filter.start();
        rootLogger.addFilter(filter);

        ObjectList<DamageSourceConfig> damageConfigs = null;

        try {
            var fakePlayerProfile = new GameProfile(UUID.randomUUID(), "[ComplexityAnalyzer]");
            var fakePlayer = new ServerPlayer(server, serverLevel, fakePlayerProfile, ClientInformation.createDefault());
            damageConfigs = createDamageSources(serverLevel, fakePlayer);

            var executor = ThreadPoolManager.getInstance().getComputePool();
            var futures = new ObjectArrayList<CompletableFuture<Void>>();
            var processedCounter = new AtomicInteger(0);
            final var finalConfigs = damageConfigs;

            for (var entityType : entityTypes) {
                if (SPECIAL_KILL_ENTITIES.contains(entityType)) continue;

                futures.add(CompletableFuture.runAsync(() -> {
                    var lootTableKey = entityType.getDefaultLootTable();
                    var lootTable = CompletableFuture.supplyAsync(() ->
                            server.reloadableRegistries().getLootTable(lootTableKey), server).join();
                    if (lootTable == LootTable.EMPTY) return;
                    if (entityType.getCategory() == MobCategory.MISC) return;

                    Entity entityInstance;
                    try {
                        entityInstance = entityType.create(serverLevel);
                    } catch (Exception e) {
                        ComplexityAnalyzer.LOGGER.debug("[MobDropSource] Failed to create entity {} for simulation: {}",
                                GameRegistryManager.getEntityTypeId(entityType), e.getMessage());
                        return;
                    }

                    if (entityInstance == null) {
                        ComplexityAnalyzer.LOGGER.debug("[MobDropSource] Creating entity {} returned null, skipping.",
                                GameRegistryManager.getEntityTypeId(entityType));
                        return;
                    }

                    var combinedDrops = new Reference2ObjectOpenHashMap<Item, DropStatistics>();
                    for (var config : finalConfigs) {
                        if (config.methodName.equals("Skeleton Arrow") && entityType != EntityType.CREEPER) continue;
                        simulateKillMethod(serverLevel, entityInstance, lootTable, config, combinedDrops);
                    }

                    for (var entry : combinedDrops.reference2ObjectEntrySet()) {
                        var stats = entry.getValue();
                        if (stats.totalDropped > 0) synchronized (dropMap) {
                            dropMap.computeIfAbsent(entry.getKey(), k -> new ObjectArrayList<>())
                                    .add(new MobDropData(entry.getKey(), entityType, stats.getAverageYield(), stats.getBestMethod()));
                        }
                    }
                    processedCounter.incrementAndGet();
                    entityInstance.discard();
                }, executor));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            int processedEntities = processedCounter.get();

            long duration = System.currentTimeMillis() - startTime;
            ComplexityAnalyzer.LOGGER.info("MobDropSource initialized. Processed {} valid entities. Found drop info for {} unique items. Time: {}ms", processedEntities, dropMap.size(), duration);

        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[MobDropSource] A critical error occurred during simulation.", e);
        } finally {
            if (damageConfigs != null) for (DamageSourceConfig config : damageConfigs) {
                if (config.attackingEntity != null) config.attackingEntity.discard();
            }

            try {
                rootLogger.get().removeFilter(filter);
                filter.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private ObjectList<DamageSourceConfig> createDamageSources(ServerLevel level, ServerPlayer player) {
        var configs = new ObjectArrayList<DamageSourceConfig>();

        configs.add(new DamageSourceConfig("Player Attack", level.damageSources().playerAttack(player), false, player, null));
        configs.add(new DamageSourceConfig("Fire", level.damageSources().onFire(), true, player, null));
        configs.add(new DamageSourceConfig("Lava", level.damageSources().lava(), true, player, null));
        configs.add(new DamageSourceConfig("Magic", level.damageSources().magic(), false, player, null));
        configs.add(new DamageSourceConfig("Fall Damage", level.damageSources().fall(), false, null, null));

        var chargedCreeper = new Creeper(EntityType.CREEPER, level);
        var creeperNBT = new CompoundTag();
        creeperNBT.putBoolean("powered", true);
        chargedCreeper.readAdditionalSaveData(creeperNBT);
        var damageTypeRegistry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        var explosionHolder = damageTypeRegistry.getHolderOrThrow(DamageTypes.EXPLOSION);
        var creeperOnlyExplosion = new DamageSource(explosionHolder, chargedCreeper, chargedCreeper);
        configs.add(new DamageSourceConfig("Charged Creeper", creeperOnlyExplosion, false,
                null, chargedCreeper));

        var skeleton = new Skeleton(EntityType.SKELETON, level);
        var arrow = new Arrow(EntityType.ARROW, level);
        arrow.setOwner(skeleton);
        configs.add(new DamageSourceConfig("Skeleton Arrow", level.damageSources().arrow(arrow, skeleton),
                false, null, skeleton));

        return configs;
    }

    private void simulateKillMethod(ServerLevel level, Entity entityInstance, LootTable lootTable,
                                    DamageSourceConfig config, Reference2ObjectMap<Item, DropStatistics> combinedDrops) {
        long baseSeed = entityInstance.getType().hashCode() ^ ((long) config.methodName.hashCode() << 16);
        for (int i = 0; i < SIMULATION_COUNT; i++) {
            try {
                var builder = new LootParams.Builder(level)
                        .withParameter(LootContextParams.THIS_ENTITY, entityInstance)
                        .withParameter(LootContextParams.ORIGIN, entityInstance.position())
                        .withParameter(LootContextParams.DAMAGE_SOURCE, config.damageSource);
                if (config.killerPlayer != null)
                    builder.withParameter(LootContextParams.LAST_DAMAGE_PLAYER, config.killerPlayer);
                if (config.attackingEntity != null) {
                    builder.withParameter(LootContextParams.ATTACKING_ENTITY, config.attackingEntity);
                    if (config.damageSource.getDirectEntity() != null) {
                        builder.withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, config.damageSource.getDirectEntity());
                    } else {
                        builder.withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, config.attackingEntity);
                    }
                }
                var lootParams = builder.create(LootContextParamSets.ENTITY);
                var context = new LootContext.Builder(lootParams).create(Optional.empty());
                var deterministicRandom = RandomSource.create(baseSeed + i);
                try {
                    ((LootContextAccessor) context).setRandom(deterministicRandom);
                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.warn("[MobDropSource] Failed to inject random into LootContext: {}", e.getMessage());
                }

                if (config.isOnFire) entityInstance.setRemainingFireTicks(100);
                ObjectArrayList<ItemStack> drops = new ObjectArrayList<>();
                lootTable.getRandomItems(context, drops::add);
                if (config.isOnFire) entityInstance.clearFire();
                for (var stack : drops) {
                    combinedDrops.computeIfAbsent(stack.getItem(), k -> new DropStatistics())
                            .addDrop(config.methodName, stack.getCount());
                }
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.error("Exception during loot simulation for {} with method {}",
                        entityInstance.getType().getDescriptionId(), config.methodName, e);
            }
        }
    }

    @Override
    public boolean canProvide(Item item) {
        synchronized (dropMap) {
            return dropMap.containsKey(item);
        }
    }

    @Override
    @Nullable
    public BaseResourceData analyze(Item item) {
        if (!canProvide(item)) return null;
        ObjectList<MobDropData> list;
        synchronized (dropMap) {
            var raw = dropMap.get(item);
            list = raw != null ? new ObjectArrayList<>(raw) : null;
        }
        if (list == null) return null;
        var best = (BaseResourceData) null;
        for (var data : list) {
            var current = calculateComplexityForDrop(item, data);
            if (current != null) if (best == null || current.getBaseFactor() < best.getBaseFactor()) best = current;
        }
        return best;
    }

    public ObjectList<MobDropData> getDropsForEntity(EntityType<?> entityType) {
        var results = new ObjectArrayList<MobDropData>();
        synchronized (dropMap) {
            for (var allDrops : dropMap.values()) {
                for (var data : allDrops) if (data.sourceMob() == entityType) results.add(data);
            }
        }
        return results;
    }

    @Nullable
    private BaseResourceData calculateComplexityForDrop(Item item, MobDropData data) {
        var victimMobType = data.sourceMob();
        var victimProps = mobProvider.getProperties(victimMobType);
        if (victimProps == null) return null;
        double victimCombatPower = victimProps.calculateCombatPower();
        double victimRarityMultiplier = mobProvider.getRarity(victimMobType);

        double specialConditionCost = 0.0;
        if ("Killed by Charged Creeper".equals(data.killMethod())) {
            var creeperProps = mobProvider.getProperties(EntityType.CREEPER);

            if (creeperProps != null) {
                double creeperCombatPower = creeperProps.calculateCombatPower();
                double creeperRarity = mobProvider.getRarity(EntityType.CREEPER);
                specialConditionCost = (creeperCombatPower * creeperRarity) * 200.0;
            } else {
                specialConditionCost = 50000.0;
            }
        }

        double baseKillComplexity = (victimCombatPower * victimRarityMultiplier) / data.averageYield();
        double finalComplexity = (baseKillComplexity + specialConditionCost) * ComplexityConfig.MOB_DIFFICULTY_SCALER.get();

        var details = String.format("From %s (Yield: %.2f/kill, Rarity: %.1fx, Method: %s)",
                victimMobType.getDescription().getString(), data.averageYield(), victimRarityMultiplier,
                data.killMethod() != null ? data.killMethod() : "Any");

        return new BaseResourceData.Builder(item, this)
                .sourceType(BaseResourceData.ResourceSourceType.MOB_DROP)
                .baseFactor(finalComplexity)
                .sourceSpecifier(victimMobType.getDescriptionId())
                .details(details).build();
    }

    @Override
    public BaseResourceData.ResourceSourceType getSourceType() {
        return BaseResourceData.ResourceSourceType.MOB_DROP;
    }

    @Override
    public int getPriority() {
        return 20;
    }

    @Override
    public String getName() {
        return "MobDropSource";
    }

    private record DamageSourceConfig(String methodName, DamageSource damageSource, boolean isOnFire,
                                      ServerPlayer killerPlayer, Entity attackingEntity) {
    }

    private static class DropStatistics {
        private final Object2IntMap<String> dropsByMethod = new Object2IntOpenHashMap<>();
        private int totalDropped = 0;

        public void addDrop(String method, int count) {
            dropsByMethod.mergeInt(method, count, Integer::sum);
            totalDropped += count;
        }

        public double getAverageYield() {
            if (SIMULATION_COUNT == 0) return 0.0;
            return (double) totalDropped / SIMULATION_COUNT;
        }

        public String getBestMethod() {
            var bestMethod = "Unknown";
            var maxCount = -1;
            for (var entry : dropsByMethod.object2IntEntrySet()) {
                if (entry.getIntValue() > maxCount) {
                    maxCount = entry.getIntValue();
                    bestMethod = entry.getKey();
                }
            }
            return bestMethod;
        }
    }

    private void registerSpecialKillDrops() {
        ComplexityAnalyzer.LOGGER.info("Registering special kill-based drops...");
        int count = 0;

        registerDrop(EntityType.ZOMBIE, Items.ZOMBIE_HEAD, 1.0, "Killed by Charged Creeper");
        count++;
        registerDrop(EntityType.SKELETON, Items.SKELETON_SKULL, 1.0, "Killed by Charged Creeper");
        count++;
        registerDrop(EntityType.CREEPER, Items.CREEPER_HEAD, 1.0, "Killed by Charged Creeper");
        count++;
        registerDrop(EntityType.PIGLIN, Items.PIGLIN_HEAD, 1.0, "Killed by Charged Creeper");
        count++;

        registerDrop(EntityType.WITHER, Items.NETHER_STAR, 1.0, "Boss Kill");
        count++;
        registerDrop(EntityType.ENDER_DRAGON, Items.DRAGON_EGG, 1.0, "Boss Kill");
        count++;
        registerDrop(EntityType.ENDER_DRAGON, Items.DRAGON_HEAD, 1.0, "End Ship Loot");
        count++;
        registerDrop(EntityType.SHULKER, Items.SHULKER_SHELL, 0.5, "End City Mob");
        count++;

        ComplexityAnalyzer.LOGGER.info("Registered {} special kill-based drop entries.", count);
    }

    private void registerDrop(EntityType<?> entityType, Item item, double averageYield, String method) {
        var dropData = new MobDropData(item, entityType, averageYield, method);
        dropMap.computeIfAbsent(item, k -> new ObjectArrayList<>()).add(dropData);
    }
}