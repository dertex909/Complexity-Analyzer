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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.resource.sources;

import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.Animal;
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
import org.complexityanalyzer.cache.Fingerprints;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.resource.IResourceSource;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.resource.data.MobDropData;
import org.complexityanalyzer.resource.providers.MobPropertyProvider;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.apache.logging.log4j.Level.WARN;
import static org.complexityanalyzer.cache.ResourceCache.MOB_DROP;
import static org.complexityanalyzer.config.ComplexityConfig.ENABLE_CACHE;

public class MobDropSource implements IResourceSource {
    private static final int SIMULATION_COUNT = 100;
    private static final int LOGIC_VERSION = 1;
    private static final ReferenceSet<EntityType<?>> SPECIAL_KILL_ENTITIES = new ReferenceOpenHashSet<>(new EntityType<?>[]{
            EntityType.WITHER,
            EntityType.ENDER_DRAGON,
            EntityType.SHULKER
    });
    private final MobPropertyProvider mobProvider;

    private volatile Reference2ObjectMap<Item, ObjectList<MobDropData>> dropMap = Reference2ObjectMaps.emptyMap();

    public MobDropSource(MobPropertyProvider mobProvider) {
        this.mobProvider = mobProvider;
    }

    private static void writeData(FriendlyByteBuf buf, MobDropData data) {
        var mobId = GameRegistryManager.getEntityTypeId(data.sourceMob());
        buf.writeResourceLocation(mobId != null ? mobId : ResourceLocation.withDefaultNamespace("pig"));
        buf.writeDouble(data.averageYield());
        boolean hasMethod = data.killMethod() != null;
        buf.writeBoolean(hasMethod);
        if (hasMethod) buf.writeUtf(data.killMethod());
    }

    @Nullable
    private static MobDropData readData(FriendlyByteBuf buf, Item item) {
        var mob = GameRegistryManager.getEntityType(buf.readResourceLocation());
        double yield = buf.readDouble();
        String killMethod = buf.readBoolean() ? buf.readUtf() : null;
        if (mob == null) return null;
        return new MobDropData(item, mob, yield, killMethod);
    }

    @Override
    public void initialize(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            ComplexityAnalyzer.LOGGER.error("[MobDropSource] Initialize called with non-server level. Aborting.");
            return;
        }

        var server = serverLevel.getServer();
        var localDropMap = new Reference2ObjectOpenHashMap<Item, ObjectList<MobDropData>>();

        boolean cacheEnabled = ENABLE_CACHE.get();
        var cacheFile = cacheEnabled ? MOB_DROP.file(server) : null;
        long[] fingerprint = cacheFile != null ? computeFingerprint() : null;
        if (cacheFile != null) {
            int restored = MOB_DROP.load(cacheFile, fingerprint, MobDropSource::readData, localDropMap);
            if (restored >= 0) {
                ComplexityAnalyzer.LOGGER.info("[MobDropSource] Loaded {} drop entries from cache (loot simulation skipped).", restored);
                this.dropMap = localDropMap;
                return;
            }
        }

        ObjectList<EntityType<?>> entityTypes;
        try {
            if (server.isSameThread()) {
                var types = new ObjectArrayList<EntityType<?>>();
                GameRegistryManager.getAllEntityTypes().forEach(types::add);
                entityTypes = types;
            } else {
                entityTypes = CompletableFuture.supplyAsync(() -> {
                    var types = new ObjectArrayList<EntityType<?>>();
                    GameRegistryManager.getAllEntityTypes().forEach(types::add);
                    return types;
                }, server).join();
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[MobDropSource] Failed to get entity types from server thread. Aborting.", e);
            return;
        }

        processMobDrops(serverLevel, entityTypes, localDropMap);

        if (cacheFile != null) MOB_DROP.save(cacheFile, fingerprint, MobDropSource::writeData, localDropMap);

        this.dropMap = localDropMap;
    }

    private long[] computeFingerprint() {
        return new long[]{Fingerprints.fnvLong(Fingerprints.FNV_OFFSET, LOGIC_VERSION), Fingerprints.hashMods(), Fingerprints.hashAllEntities()};
    }

    private void processMobDrops(ServerLevel serverLevel, ObjectList<EntityType<?>> entityTypes, Reference2ObjectMap<Item, ObjectList<MobDropData>> targetMap) {
        var server = serverLevel.getServer();

        ComplexityAnalyzer.LOGGER.debug("Initializing MobDropSource by simulating mob loot tables on the server thread...");
        long startTime = System.currentTimeMillis();

        registerSpecialKillDrops(targetMap);

        LootFunctionFilter filter = new LootFunctionFilter();
        Logger rootLogger = (Logger) LogManager.getRootLogger();
        filter.start();
        rootLogger.addFilter(filter);

        try {
            Runnable simulationRunnable = () -> {
                ObjectList<DamageSourceConfig> damageConfigs = null;
                try {
                    var fakePlayerProfile = new GameProfile(UUID.randomUUID(), "[ComplexityAnalyzer]");
                    var fakePlayer = new ServerPlayer(server, serverLevel, fakePlayerProfile, ClientInformation.createDefault());
                    damageConfigs = createDamageSources(serverLevel, fakePlayer);

                    int processedEntities = 0;
                    final var finalConfigs = damageConfigs;

                    for (var entityType : entityTypes) {
                        if (SPECIAL_KILL_ENTITIES.contains(entityType)) continue;

                        var lootTableKey = entityType.getDefaultLootTable();
                        var lootTable = server.reloadableRegistries().getLootTable(lootTableKey);
                        if (lootTable == LootTable.EMPTY) continue;
                        if (entityType.getCategory() == MobCategory.MISC) continue;

                        Entity entityInstance;
                        try {
                            entityInstance = entityType.create(serverLevel);
                            if (entityInstance != null) {
                                var spawnPos = serverLevel.getSharedSpawnPos();
                                entityInstance.setPos(spawnPos.getX() + 0.5, spawnPos.getY() + 0.5, spawnPos.getZ() + 0.5);
                            }
                        } catch (Exception e) {
                            ComplexityAnalyzer.LOGGER.debug("[MobDropSource] Failed to create entity {} for simulation: {}",
                                    GameRegistryManager.getEntityTypeId(entityType), e.getMessage());
                            continue;
                        }

                        if (entityInstance == null) {
                            ComplexityAnalyzer.LOGGER.debug("[MobDropSource] Creating entity {} returned null, skipping.",
                                    GameRegistryManager.getEntityTypeId(entityType));
                            continue;
                        }

                        var v = new Victim(entityType, entityInstance, lootTable);
                        mergeDrops(entityType, sampleVictim(serverLevel, v, finalConfigs), targetMap);
                        processedEntities++;

                        if (entityInstance instanceof Animal) mobProvider.markRenewable(entityType);
                        entityInstance.discard();
                    }

                    long duration = System.currentTimeMillis() - startTime;
                    ComplexityAnalyzer.LOGGER.info("MobDropSource initialized. Processed {} valid entities. Found drop info for {} unique items. Time: {}ms", processedEntities, targetMap.size(), duration);

                } finally {
                    if (damageConfigs != null) for (DamageSourceConfig config : damageConfigs) {
                        if (config.attackingEntity != null) config.attackingEntity.discard();
                    }
                }
            };

            if (server.isSameThread()) {
                simulationRunnable.run();
            } else {
                CompletableFuture.runAsync(simulationRunnable, server).join();
            }

        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[MobDropSource] A critical error occurred during simulation.", e);
        } finally {
            try {
                rootLogger.get().removeFilter(filter);
                filter.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private ObjectList<DamageSourceConfig> createDamageSources(ServerLevel level, ServerPlayer player) {
        var configs = new ObjectArrayList<DamageSourceConfig>();

        var spawnPos = level.getSharedSpawnPos();
        double sx = spawnPos.getX() + 0.5;
        double sy = spawnPos.getY() + 0.5;
        double sz = spawnPos.getZ() + 0.5;

        player.setPos(sx, sy, sz);

        configs.add(new DamageSourceConfig("Player Attack", level.damageSources().playerAttack(player), false, player, null));
        configs.add(new DamageSourceConfig("Fire", level.damageSources().onFire(), true, player, null));
        configs.add(new DamageSourceConfig("Lava", level.damageSources().lava(), true, player, null));
        configs.add(new DamageSourceConfig("Magic", level.damageSources().magic(), false, player, null));
        configs.add(new DamageSourceConfig("Fall Damage", level.damageSources().fall(), false, null, null));

        var chargedCreeper = new Creeper(EntityType.CREEPER, level);
        chargedCreeper.setPos(sx, sy, sz);
        var creeperNBT = new CompoundTag();
        creeperNBT.putBoolean("powered", true);
        chargedCreeper.readAdditionalSaveData(creeperNBT);
        var damageTypeRegistry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        var explosionHolder = damageTypeRegistry.getHolderOrThrow(DamageTypes.EXPLOSION);
        var creeperOnlyExplosion = new DamageSource(explosionHolder, chargedCreeper, chargedCreeper);
        configs.add(new DamageSourceConfig("Charged Creeper", creeperOnlyExplosion, false,
                null, chargedCreeper));

        var skeleton = new Skeleton(EntityType.SKELETON, level);
        skeleton.setPos(sx, sy, sz);
        var arrow = new Arrow(EntityType.ARROW, level);
        arrow.setPos(sx, sy, sz);
        arrow.setOwner(skeleton);
        configs.add(new DamageSourceConfig("Skeleton Arrow", level.damageSources().arrow(arrow, skeleton),
                false, null, skeleton));

        return configs;
    }

    private void simulateKillMethod(ServerLevel level, Entity entityInstance, LootTable lootTable,
                                    DamageSourceConfig config, Reference2ObjectMap<Item, DropStatistics> combinedDrops) {
        long baseSeed = GameRegistryManager.getEntityTypeId(entityInstance.getType()).toString().hashCode() ^ ((long) config.methodName.hashCode() << 16);
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
                var context = new LootContext.Builder(lootParams)
                        .withOptionalRandomSource(RandomSource.create(baseSeed + i)).create(Optional.empty());

                var drops = new ObjectArrayList<ItemStack>();
                if (config.isOnFire) entityInstance.setRemainingFireTicks(100);
                try {
                    lootTable.getRandomItems(context, drops::add);
                } finally {
                    if (config.isOnFire) entityInstance.clearFire();
                }
                for (var stack : drops) {
                    combinedDrops.computeIfAbsent(stack.getItem(), k -> new DropStatistics()).addDrop(config.methodName, stack.getCount());
                }
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.error("Exception during loot simulation for {} with method {}",
                        entityInstance.getType().getDescriptionId(), config.methodName, e);
            }
        }
    }

    private Reference2ObjectMap<Item, DropStatistics> sampleVictim(ServerLevel level, Victim v, ObjectList<DamageSourceConfig> configs) {
        var combinedDrops = new Reference2ObjectOpenHashMap<Item, DropStatistics>();
        for (var config : configs) {
            if (config.methodName.equals("Skeleton Arrow") && v.type() != EntityType.CREEPER) continue;
            simulateKillMethod(level, v.entity(), v.lootTable(), config, combinedDrops);
        }
        return combinedDrops;
    }

    private void mergeDrops(EntityType<?> type, Reference2ObjectMap<Item, DropStatistics> combinedDrops, Reference2ObjectMap<Item, ObjectList<MobDropData>> targetMap) {
        for (var entry : combinedDrops.reference2ObjectEntrySet()) {
            var stats = entry.getValue();
            if (stats.totalDropped > 0) targetMap.computeIfAbsent(entry.getKey(), k -> new ObjectArrayList<>())
                    .add(new MobDropData(entry.getKey(), type, stats.getAverageYield(), stats.getBestMethod()));
        }
    }

    @Override
    public boolean canProvide(Item item) {
        return dropMap.containsKey(item);
    }

    @Override
    @Nullable
    public BaseResourceData analyze(Item item) {
        var list = dropMap.get(item);
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
        var currentMap = this.dropMap;
        for (var allDrops : currentMap.values()) {
            for (var data : allDrops) if (data.sourceMob() == entityType) results.add(data);
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
                specialConditionCost = creeperProps.calculateCombatPower() * mobProvider.getRarity(EntityType.CREEPER);
            }
        }

        boolean renewable = mobProvider.isRenewable(victimMobType);
        double effectiveRarity = renewable ? 1.0 : victimRarityMultiplier;

        double baseKillComplexity = (victimCombatPower * effectiveRarity) / data.averageYield();
        double finalComplexity = (baseKillComplexity + specialConditionCost) * ComplexityConfig.MOB_DIFFICULTY_SCALER.get();

        var details = String.format("From %s (Yield: %.2f/kill, Rarity: %.1fx%s, Method: %s)",
                victimMobType.getDescription().getString(), data.averageYield(), victimRarityMultiplier,
                renewable ? ", renewable" : "", data.killMethod() != null ? data.killMethod() : "Any");

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
    public boolean requiresServerThread() {
        return true;
    }

    @Override
    public int getPriority() {
        return 20;
    }

    @Override
    public String getName() {
        return "MobDropSource";
    }

    private void registerSpecialKillDrops(Reference2ObjectMap<Item, ObjectList<MobDropData>> targetMap) {
        ComplexityAnalyzer.LOGGER.info("Registering special kill-based drops...");
        int count = 0;

        registerDrop(targetMap, EntityType.ZOMBIE, Items.ZOMBIE_HEAD, 1.0, "Killed by Charged Creeper");
        count++;
        registerDrop(targetMap, EntityType.SKELETON, Items.SKELETON_SKULL, 1.0, "Killed by Charged Creeper");
        count++;
        registerDrop(targetMap, EntityType.CREEPER, Items.CREEPER_HEAD, 1.0, "Killed by Charged Creeper");
        count++;
        registerDrop(targetMap, EntityType.PIGLIN, Items.PIGLIN_HEAD, 1.0, "Killed by Charged Creeper");
        count++;

        registerDrop(targetMap, EntityType.WITHER, Items.NETHER_STAR, 1.0, "Boss Kill");
        count++;
        registerDrop(targetMap, EntityType.ENDER_DRAGON, Items.DRAGON_EGG, 1.0, "Boss Kill");
        count++;
        registerDrop(targetMap, EntityType.ENDER_DRAGON, Items.DRAGON_HEAD, 1.0, "End Ship Loot");
        count++;
        registerDrop(targetMap, EntityType.SHULKER, Items.SHULKER_SHELL, 0.5, "End City Mob");
        count++;

        ComplexityAnalyzer.LOGGER.info("Registered {} special kill-based drop entries.", count);
    }

    private void registerDrop(Reference2ObjectMap<Item, ObjectList<MobDropData>> targetMap, EntityType<?> entityType, Item item, double averageYield, String method) {
        var dropData = new MobDropData(item, entityType, averageYield, method);
        targetMap.computeIfAbsent(item, k -> new ObjectArrayList<>()).add(dropData);
    }

    private static class LootFunctionFilter extends AbstractFilter {
        @Override
        public Result filter(LogEvent event) {
            if (event == null) return Result.NEUTRAL;

            var msg = event.getMessage();
            if (msg != null) {
                String message = msg.getFormattedMessage();
                if (message != null && message.contains("Failed to apply component patch")
                        && message.contains("was larger than maximum")) return Result.DENY;
            }

            if (event.getLevel() != WARN) return Result.NEUTRAL;

            String loggerName = event.getLoggerName();
            if (loggerName != null && loggerName.startsWith("net.minecraft.world.level.storage.loot.functions.")) {
                String message = msg != null ? msg.getFormattedMessage() : null;
                if (message != null && (message.contains("Couldn't set damage") || message.contains("Couldn't smelt")
                        || message.contains("Couldn't find a compatible enchantment"))) return Result.DENY;
            }
            return Result.NEUTRAL;
        }
    }

    private record Victim(EntityType<?> type, Entity entity, LootTable lootTable) {
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
}