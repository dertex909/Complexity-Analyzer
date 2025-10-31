package org.complexityanalyzer.analyzer.resource.sources;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
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
import org.complexityanalyzer.analyzer.resource.providers.MobPropertyProvider;
import org.complexityanalyzer.config.ComplexityConfig;

import java.util.*;

public class MobDropSource implements IResourceSource {
    private final MobPropertyProvider mobProvider;
    private final Map<Item, List<MobDropData>> dropMap = new HashMap<>();
    private static final int SIMULATION_COUNT = 500;

    private static final Set<EntityType<?>> SPECIAL_KILL_ENTITIES = Set.of(
            EntityType.WITHER,
            EntityType.ENDER_DRAGON
    );

    public MobDropSource(MobPropertyProvider mobProvider) {
        this.mobProvider = mobProvider;
    }

    private static class LootFunctionFilter extends AbstractFilter {
        @Override
        public Result filter(LogEvent event) {
            if (event == null || event.getLevel() != org.apache.logging.log4j.Level.WARN) return Result.NEUTRAL;
            String loggerName = event.getLoggerName();
            if (loggerName != null && loggerName.startsWith("net.minecraft.world.level.storage.loot.functions.")) {
                String message = event.getMessage().getFormattedMessage();
                if (message != null && (message.contains("Couldn't set damage") || message.contains("Couldn't smelt") || message.contains("Couldn't find a compatible enchantment"))) {
                    return Result.DENY;
                }
            }
            return Result.NEUTRAL;
        }
    }

    @Override
    public void initialize(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        MinecraftServer server = serverLevel.getServer();
        if (!server.isSameThread()) {
            server.executeBlocking(() -> initialize(level));
            return;
        }

        ComplexityAnalyzer.LOGGER.debug("Initializing MobDropSource by simulating mob loot tables...");
        long startTime = System.currentTimeMillis();
        int processedEntities = 0;

        registerSpecialKillDrops();

        LootFunctionFilter filter = new LootFunctionFilter();
        Logger rootLogger = (Logger) LogManager.getRootLogger();
        filter.start();
        rootLogger.addFilter(filter);

        try {
            com.mojang.authlib.GameProfile fakePlayerProfile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), "[ComplexityAnalyzer]");
            net.minecraft.server.level.ServerPlayer fakePlayer = new net.minecraft.server.level.ServerPlayer(server, serverLevel, fakePlayerProfile, net.minecraft.server.level.ClientInformation.createDefault());
            List<DamageSourceConfig> damageConfigs = createDamageSources(serverLevel, fakePlayer);

            for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
                if (entityType.getCategory() == MobCategory.MISC || SPECIAL_KILL_ENTITIES.contains(entityType)) {
                    continue;
                }

                ResourceKey<LootTable> lootTableKey = entityType.getDefaultLootTable();
                LootTable lootTable = server.reloadableRegistries().getLootTable(lootTableKey);
                if (lootTable == LootTable.EMPTY) continue;

                Entity entityInstance = entityType.create(serverLevel);
                if (entityInstance == null) continue;
                entityInstance.setPos(0, 64, 0);

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
            }
        } finally {
            try {
                rootLogger.get().removeFilter(filter);
                filter.stop();
            } catch (Exception ignored) {}
        }

        long duration = System.currentTimeMillis() - startTime;
        ComplexityAnalyzer.LOGGER.info("MobDropSource initialized. Processed {} valid entities. Found drop info for {} unique items. Time: {}ms", processedEntities, dropMap.size(), duration);
    }

    private List<DamageSourceConfig> createDamageSources(ServerLevel level, net.minecraft.server.level.ServerPlayer player) {
        List<DamageSourceConfig> configs = new ArrayList<>();
        configs.add(new DamageSourceConfig("Player Attack", level.damageSources().playerAttack(player), false, player, null));
        configs.add(new DamageSourceConfig("Fire", level.damageSources().onFire(), true, player, null));
        configs.add(new DamageSourceConfig("Lava", level.damageSources().lava(), true, player, null));
        configs.add(new DamageSourceConfig("Magic", level.damageSources().magic(), false, player, null));
        configs.add(new DamageSourceConfig("Fall Damage", level.damageSources().fall(), false, null, null));
        Creeper chargedCreeper = EntityType.CREEPER.create(level);
        if (chargedCreeper != null) {
            CompoundTag creeperNBT = new CompoundTag();
            creeperNBT.putBoolean("powered", true);
            chargedCreeper.readAdditionalSaveData(creeperNBT);
            chargedCreeper.setPos(0, 64, 0);
            configs.add(new DamageSourceConfig("Charged Creeper", level.damageSources().explosion(chargedCreeper, player), false, player, chargedCreeper));
        }
        Skeleton skeleton = EntityType.SKELETON.create(level);
        if (skeleton != null) {
            Arrow arrow = EntityType.ARROW.create(level);
            if (arrow != null) {
                arrow.setOwner(skeleton);
                configs.add(new DamageSourceConfig("Skeleton Arrow", level.damageSources().arrow(arrow, skeleton), false, null, skeleton));
            }
        }
        return configs;
    }

    private static final Set<EntityType<?>> DEBUG_MOBS = Set.of(
            EntityType.CREEPER,
            EntityType.ZOMBIE,
            EntityType.SKELETON,
            EntityType.SHULKER,
            EntityType.PIGLIN
    );

// MobDropSource.java

    private void simulateKillMethod(ServerLevel level, Entity entityInstance, LootTable lootTable, DamageSourceConfig config, Map<Item, DropStatistics> combinedDrops) {
        for (int i = 0; i < SIMULATION_COUNT; i++) {
            try {
                LootParams.Builder builder = new LootParams.Builder(level)
                        .withParameter(LootContextParams.THIS_ENTITY, entityInstance)
                        .withParameter(LootContextParams.ORIGIN, entityInstance.position())
                        .withParameter(LootContextParams.DAMAGE_SOURCE, config.damageSource);

                if (config.killerPlayer != null) builder.withParameter(LootContextParams.LAST_DAMAGE_PLAYER, config.killerPlayer);
                if (config.attackingEntity != null) {
                    builder.withParameter(LootContextParams.ATTACKING_ENTITY, config.attackingEntity);
                    if (config.damageSource.getDirectEntity() != null) builder.withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, config.damageSource.getDirectEntity());
                    else builder.withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, config.attackingEntity);
                }

                if (config.isOnFire) entityInstance.setRemainingFireTicks(100);
                LootParams lootParams = builder.create(LootContextParamSets.ENTITY);
                List<ItemStack> drops = lootTable.getRandomItems(lootParams);
                if (config.isOnFire) entityInstance.clearFire();

                // Сначала обрабатываем дроп для всех, как и раньше
                for (ItemStack stack : drops) {
                    combinedDrops.computeIfAbsent(stack.getItem(), k -> new DropStatistics()).addDrop(config.methodName, stack.getCount());
                }

                // А теперь логируем ТОЛЬКО для интересующих нас мобов
                if (DEBUG_MOBS.contains(entityInstance.getType())) {
                    String entityName = entityInstance.getType().getDescription().getString();
                    if (drops.isEmpty()) {
                        ComplexityAnalyzer.LOGGER.info("[DEBUG-DROP] {} killed by '{}' dropped NOTHING", entityName, config.methodName);
                    } else {
                        for (ItemStack stack : drops) {
                            ComplexityAnalyzer.LOGGER.info("[DEBUG-DROP] {} killed by '{}' dropped {}x {}",
                                    entityName, config.methodName, stack.getCount(), stack.getItem().getDescription().getString());
                        }
                    }
                }

            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.error("Exception during loot simulation for {} with method {}",
                        entityInstance.getType().getDescriptionId(), config.methodName, e);
            }
        }
    }

    @Override
    public boolean canProvide(Item item) { return dropMap.containsKey(item); }

    @Override
    public Optional<BaseResourceData> analyze(Item item) {
        if (!canProvide(item)) return Optional.empty();
        List<MobDropData> possibleSources = dropMap.get(item);
        return possibleSources.stream()
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
        EntityType<?> mobType = data.sourceMob();
        Optional<MobPropertyProvider.MobProperties> mobPropsOpt = mobProvider.getProperties(mobType);
        if (mobPropsOpt.isEmpty()) return Optional.empty();
        var props = mobPropsOpt.get();
        double survivability = props.maxHealth() * (1 + props.armor() / 5.0);
        double threat = 1 + Math.log1p(props.attackDamage());
        double combatPower = survivability * threat;
        double spawnRarityMultiplier = props.isBoss() ? ComplexityConfig.BOSS_RARITY_MULTIPLIER.get() : 1.0;
        double finalComplexity = ((combatPower * spawnRarityMultiplier) / data.averageYield()) * ComplexityConfig.MOB_DIFFICULTY_SCALER.get();
        String details = String.format("From %s (Yield: %.2f/kill, Method: %s)", mobType.getDescription().getString(), data.averageYield(), data.killMethod() != null ? data.killMethod() : "Any");
        return Optional.of(new BaseResourceData.Builder(item, this).sourceType(BaseResourceData.ResourceSourceType.MOB_DROP).baseFactor(finalComplexity).details(details).build());
    }

    @Override
    public BaseResourceData.ResourceSourceType getSourceType() { return BaseResourceData.ResourceSourceType.MOB_DROP; }

    @Override
    public int getPriority() { return 20; }

    @Override
    public String getName() { return "MobDropSource"; }

    private record DamageSourceConfig(String methodName, DamageSource damageSource, boolean isOnFire, net.minecraft.server.level.ServerPlayer killerPlayer, Entity attackingEntity) {}

    private static class DropStatistics {
        private final Map<String, Integer> dropsByMethod = new HashMap<>();
        private int totalDropped = 0;

        public void addDrop(String method, int count) {
            dropsByMethod.merge(method, count, Integer::sum);
            totalDropped += count;
        }

        public double getAverageYield() {
            return (double) totalDropped / SIMULATION_COUNT;
        }

        public String getBestMethod() {
            return dropsByMethod.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("Unknown");
        }
    }

    private void registerSpecialKillDrops() {
        ComplexityAnalyzer.LOGGER.info("Registering special kill-based drops...");
        int count = 0;

        registerDrop(EntityType.WITHER, Items.NETHER_STAR, 1.0, "Boss Kill"); count++;
        registerDrop(EntityType.ENDER_DRAGON, Items.DRAGON_EGG, 1.0, "Boss Kill"); count++;
        registerDrop(EntityType.ENDER_DRAGON, Items.DRAGON_HEAD, 1.0, "End Ship Loot"); count++;


        final double MUSIC_DISC_YIELD = 0.083;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_11, MUSIC_DISC_YIELD, "Killed by Skeleton"); count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_13, MUSIC_DISC_YIELD, "Killed by Skeleton"); count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_BLOCKS, MUSIC_DISC_YIELD, "Killed by Skeleton"); count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_CAT, MUSIC_DISC_YIELD, "Killed by Skeleton"); count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_CHIRP, MUSIC_DISC_YIELD, "Killed by Skeleton"); count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_FAR, MUSIC_DISC_YIELD, "Killed by Skeleton"); count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_MALL, MUSIC_DISC_YIELD, "Killed by Skeleton"); count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_MELLOHI, MUSIC_DISC_YIELD, "Killed by Skeleton"); count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_STAL, MUSIC_DISC_YIELD, "Killed by Skeleton"); count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_STRAD, MUSIC_DISC_YIELD, "Killed by Skeleton"); count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_WAIT, MUSIC_DISC_YIELD, "Killed by Skeleton"); count++;
        registerDrop(EntityType.CREEPER, Items.MUSIC_DISC_WARD, MUSIC_DISC_YIELD, "Killed by Skeleton"); count++;

        ComplexityAnalyzer.LOGGER.info("Registered {} special kill-based drop entries.", count);
    }

    private void registerDrop(EntityType<?> entityType, Item item, double averageYield, String method) {
        MobDropData dropData = new MobDropData(item, entityType, averageYield, method);
        dropMap.computeIfAbsent(item, k -> new ArrayList<>()).add(dropData);
    }
}