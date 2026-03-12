/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
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
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
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

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.apache.logging.log4j.Level.WARN;

public class MobDropSource implements IResourceSource {
    private final MobPropertyProvider mobProvider;
    private final Map<Item, List<MobDropData>> dropMap = new HashMap<>();
    private static final int SIMULATION_COUNT = 500;
    private static final Object LOOT_LOCK = new Object();

    private static final Set<EntityType<?>> SPECIAL_KILL_ENTITIES = Set.of(
            EntityType.WITHER,
            EntityType.ENDER_DRAGON,
            EntityType.SHULKER
    );

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
                        || message.contains("Couldn't find a compatible enchantment"))) {
                    return Result.DENY;
                }
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

        List<EntityType<?>> entityTypes;
        try {
            entityTypes = CompletableFuture.supplyAsync(() -> {
                List<EntityType<?>> types = new ArrayList<>();
                BuiltInRegistries.ENTITY_TYPE.forEach(types::add);
                return types;
            }, server).join();
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[MobDropSource] Failed to get entity types from server thread. Aborting.", e);
            return;
        }

        processMobDrops(serverLevel, entityTypes);
    }

    private void processMobDrops(ServerLevel serverLevel, List<EntityType<?>> entityTypes) {
        MinecraftServer server = serverLevel.getServer();

        ComplexityAnalyzer.LOGGER.debug("Initializing MobDropSource by simulating mob loot tables...");
        long startTime = System.currentTimeMillis();
        int processedEntities = 0;

        registerSpecialKillDrops();

        LootFunctionFilter filter = new LootFunctionFilter();
        Logger rootLogger = (Logger) LogManager.getRootLogger();
        filter.start();
        rootLogger.addFilter(filter);

        List<DamageSourceConfig> damageConfigs = null;

        try {
            GameProfile fakePlayerProfile = new GameProfile(UUID.randomUUID(), "[ComplexityAnalyzer]");
            ServerPlayer fakePlayer = new ServerPlayer(server, serverLevel, fakePlayerProfile,
                    ClientInformation.createDefault());
            damageConfigs = createDamageSources(serverLevel, fakePlayer);

            for (EntityType<?> entityType : entityTypes) {
                if (SPECIAL_KILL_ENTITIES.contains(entityType)) continue;

                ResourceKey<LootTable> lootTableKey = entityType.getDefaultLootTable();
                LootTable lootTable = CompletableFuture.supplyAsync(() ->
                        server.reloadableRegistries().getLootTable(lootTableKey), server).join();
                if (lootTable == LootTable.EMPTY) continue;
                if (entityType.getCategory() == MobCategory.MISC) continue;

                Entity entityInstance;
                try {
                    entityInstance = entityType.create(serverLevel);
                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.debug("[MobDropSource] Failed to create entity {} for simulation: {}",
                            BuiltInRegistries.ENTITY_TYPE.getKey(entityType), e.getMessage());
                    continue;
                }

                if (entityInstance == null) {
                    ComplexityAnalyzer.LOGGER.debug("[MobDropSource] Creating entity {} returned null, skipping.",
                            BuiltInRegistries.ENTITY_TYPE.getKey(entityType));
                    continue;
                }

                Map<Item, DropStatistics> combinedDrops = new HashMap<>();
                for (DamageSourceConfig config : damageConfigs) {
                    if (config.methodName.equals("Skeleton Arrow") && entityType != EntityType.CREEPER) continue;
                    simulateKillMethod(serverLevel, entityInstance, lootTable, config, combinedDrops);
                }

                for (Map.Entry<Item, DropStatistics> entry : combinedDrops.entrySet()) {
                    DropStatistics stats = entry.getValue();
                    if (stats.totalDropped > 0) {
                        dropMap.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                                .add(new MobDropData(entry.getKey(), entityType, stats.getAverageYield(), stats.getBestMethod()));
                    }
                }
                processedEntities++;
                entityInstance.discard();
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[MobDropSource] A critical error occurred during simulation.", e);
        } finally {
            if (damageConfigs != null) {
                for (DamageSourceConfig config : damageConfigs) {
                    if (config.attackingEntity != null) config.attackingEntity.discard();
                }
            }

            try {
                rootLogger.get().removeFilter(filter);
                filter.stop();
            } catch (Exception ignored) {
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        ComplexityAnalyzer.LOGGER.info("MobDropSource initialized. Processed {} valid entities. Found drop info for {} unique items. Time: {}ms", processedEntities, dropMap.size(), duration);
    }

    private List<DamageSourceConfig> createDamageSources(ServerLevel level, ServerPlayer player) {
        List<DamageSourceConfig> configs = new ArrayList<>();

        configs.add(new DamageSourceConfig("Player Attack", level.damageSources().playerAttack(player), false, player, null));
        configs.add(new DamageSourceConfig("Fire", level.damageSources().onFire(), true, player, null));
        configs.add(new DamageSourceConfig("Lava", level.damageSources().lava(), true, player, null));
        configs.add(new DamageSourceConfig("Magic", level.damageSources().magic(), false, player, null));
        configs.add(new DamageSourceConfig("Fall Damage", level.damageSources().fall(), false, null, null));

        Creeper chargedCreeper = new Creeper(EntityType.CREEPER, level);
        CompoundTag creeperNBT = new CompoundTag();
        creeperNBT.putBoolean("powered", true);
        chargedCreeper.readAdditionalSaveData(creeperNBT);
        Registry<DamageType> damageTypeRegistry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        Holder<DamageType> explosionHolder = damageTypeRegistry.getHolderOrThrow(DamageTypes.EXPLOSION);
        DamageSource creeperOnlyExplosion = new DamageSource(explosionHolder, chargedCreeper, chargedCreeper);
        configs.add(new DamageSourceConfig("Charged Creeper", creeperOnlyExplosion, false,
                null, chargedCreeper));

        Skeleton skeleton = new Skeleton(EntityType.SKELETON, level);
        Arrow arrow = new Arrow(EntityType.ARROW, level);
        arrow.setOwner(skeleton);
        configs.add(new DamageSourceConfig("Skeleton Arrow", level.damageSources().arrow(arrow, skeleton),
                false, null, skeleton));

        return configs;
    }

    private void simulateKillMethod(ServerLevel level, Entity entityInstance, LootTable lootTable,
                                    DamageSourceConfig config, Map<Item, DropStatistics> combinedDrops) {
        for (int i = 0; i < SIMULATION_COUNT; i++) {
            try {
                LootParams.Builder builder = new LootParams.Builder(level)
                        .withParameter(LootContextParams.THIS_ENTITY, entityInstance)
                        .withParameter(LootContextParams.ORIGIN, entityInstance.position())
                        .withParameter(LootContextParams.DAMAGE_SOURCE, config.damageSource);
                if (config.killerPlayer != null)
                    builder.withParameter(LootContextParams.LAST_DAMAGE_PLAYER, config.killerPlayer);
                if (config.attackingEntity != null) {
                    builder.withParameter(LootContextParams.ATTACKING_ENTITY, config.attackingEntity);
                    if (config.damageSource.getDirectEntity() != null) {
                        builder.withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY,
                                config.damageSource.getDirectEntity());
                    } else {
                        builder.withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, config.attackingEntity);
                    }
                }
                if (config.isOnFire) entityInstance.setRemainingFireTicks(100);
                LootParams lootParams = builder.create(LootContextParamSets.ENTITY);
                List<ItemStack> drops;
                synchronized (LOOT_LOCK) {
                    drops = lootTable.getRandomItems(lootParams);
                }
                if (config.isOnFire) entityInstance.clearFire();
                for (ItemStack stack : drops) {
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
        return dropMap.containsKey(item);
    }

    @Override
    public Optional<BaseResourceData> analyze(Item item) {
        if (!canProvide(item)) return Optional.empty();
        return dropMap.get(item).stream()
                .map(dropData -> calculateComplexityForDrop(item, dropData))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .min(Comparator.comparingDouble(BaseResourceData::getBaseFactor));
    }

    public List<MobDropData> getDropsForEntity(EntityType<?> entityType) {
        List<MobDropData> results = new ArrayList<>();
        for (List<MobDropData> allDrops : dropMap.values()) {
            for (MobDropData data : allDrops) {
                if (data.sourceMob() == entityType) results.add(data);
            }
        }
        return results;
    }

    private Optional<BaseResourceData> calculateComplexityForDrop(Item item, MobDropData data) {
        EntityType<?> victimMobType = data.sourceMob();
        Optional<MobPropertyProvider.MobProperties> victimPropsOpt = mobProvider.getProperties(victimMobType);
        if (victimPropsOpt.isEmpty()) return Optional.empty();

        var victimProps = victimPropsOpt.get();
        double victimCombatPower = victimProps.calculateCombatPower();
        double victimRarityMultiplier = mobProvider.getRarity(victimMobType);

        double specialConditionCost = 0.0;
        if ("Killed by Charged Creeper".equals(data.killMethod())) {
            Optional<MobPropertyProvider.MobProperties> creeperPropsOpt = mobProvider.getProperties(EntityType.CREEPER);

            if (creeperPropsOpt.isPresent()) {
                var creeperProps = creeperPropsOpt.get();
                double creeperCombatPower = creeperProps.calculateCombatPower();
                double creeperRarity = mobProvider.getRarity(EntityType.CREEPER);
                specialConditionCost = (creeperCombatPower * creeperRarity) * 200.0;
            } else {
                specialConditionCost = 50000.0;
            }
        }

        double baseKillComplexity = (victimCombatPower * victimRarityMultiplier) / data.averageYield();
        double finalComplexity = (baseKillComplexity + specialConditionCost) * ComplexityConfig.MOB_DIFFICULTY_SCALER.get();

        String details = String.format("From %s (Yield: %.2f/kill, Rarity: %.1fx, Method: %s)",
                victimMobType.getDescription().getString(), data.averageYield(), victimRarityMultiplier,
                data.killMethod() != null ? data.killMethod() : "Any");

        return Optional.of(new BaseResourceData.Builder(item, this)
                .sourceType(BaseResourceData.ResourceSourceType.MOB_DROP)
                .baseFactor(finalComplexity)
                .sourceSpecifier(victimMobType.getDescription().getString())
                .details(details).build());
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
        private final Map<String, Integer> dropsByMethod = new HashMap<>();
        private int totalDropped = 0;

        public void addDrop(String method, int count) {
            dropsByMethod.merge(method, count, Integer::sum);
            totalDropped += count;
        }

        public double getAverageYield() {
            if (SIMULATION_COUNT == 0) return 0.0;
            return (double) totalDropped / SIMULATION_COUNT;
        }

        public String getBestMethod() {
            return dropsByMethod.entrySet()
                    .stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("Unknown");
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

        final double MUSIC_DISC_YIELD = 1.0 / 12.0;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_11, MUSIC_DISC_YIELD, "Killed by Skeleton");
        count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_13, MUSIC_DISC_YIELD, "Killed by Skeleton");
        count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_BLOCKS, MUSIC_DISC_YIELD, "Killed by Skeleton");
        count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_CAT, MUSIC_DISC_YIELD, "Killed by Skeleton");
        count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_CHIRP, MUSIC_DISC_YIELD, "Killed by Skeleton");
        count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_FAR, MUSIC_DISC_YIELD, "Killed by Skeleton");
        count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_MALL, MUSIC_DISC_YIELD, "Killed by Skeleton");
        count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_MELLOHI, MUSIC_DISC_YIELD, "Killed by Skeleton");
        count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_STAL, MUSIC_DISC_YIELD, "Killed by Skeleton");
        count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_STRAD, MUSIC_DISC_YIELD, "Killed by Skeleton");
        count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_WAIT, MUSIC_DISC_YIELD, "Killed by Skeleton");
        count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_WARD, MUSIC_DISC_YIELD, "Killed by Skeleton");
        count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_PIGSTEP, MUSIC_DISC_YIELD, "Bastion Loot");
        count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_OTHERSIDE, MUSIC_DISC_YIELD, "Dungeon Loot");
        count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_5, MUSIC_DISC_YIELD, "Ancient City Loot");
        count++;

        ComplexityAnalyzer.LOGGER.info("Registered {} special kill-based drop entries.", count);
    }

    private void registerDrop(EntityType<?> entityType, Item item, double averageYield, String method) {
        MobDropData dropData = new MobDropData(item, entityType, averageYield, method);
        dropMap.computeIfAbsent(item, k -> new ArrayList<>()).add(dropData);
    }
}