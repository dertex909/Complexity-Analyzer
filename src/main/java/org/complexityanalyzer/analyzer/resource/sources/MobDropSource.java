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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.data.MobDropData;
import org.complexityanalyzer.analyzer.resource.providers.MobPropertyProvider;
import org.complexityanalyzer.analyzer.solver.SolverConfig;

import java.util.*;

public class MobDropSource implements IResourceSource {
    private final MobPropertyProvider mobProvider;
    private final Map<Item, List<MobDropData>> dropMap = new HashMap<>();
    private static final int SIMULATION_COUNT = 500;

    public MobDropSource(MobPropertyProvider mobProvider) {
        this.mobProvider = mobProvider;
    }

    @Override
    public void initialize(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        MinecraftServer server = serverLevel.getServer();

        ComplexityAnalyzer.LOGGER.debug("Initializing MobDropSource by simulating all mob loot tables...");
        long startTime = System.currentTimeMillis();
        int processedEntities = 0;

        // Создаём fake player для симуляций
        com.mojang.authlib.GameProfile fakePlayerProfile = new com.mojang.authlib.GameProfile(
                UUID.randomUUID(), "[ComplexityAnalyzer]"
        );
        net.minecraft.server.level.ServerPlayer fakePlayer = new net.minecraft.server.level.ServerPlayer(
                server, serverLevel, fakePlayerProfile,
                net.minecraft.server.level.ClientInformation.createDefault()
        );

        // ========== СОЗДАЁМ РАЗНЫЕ ТИПЫ УРОНА ==========
        List<DamageSourceConfig> damageConfigs = createDamageSources(serverLevel, fakePlayer);

        for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
            if (entityType.getCategory() == MobCategory.MISC) {
                continue;
            }

            ResourceKey<LootTable> lootTableKey = entityType.getDefaultLootTable();
            LootTable lootTable = server.reloadableRegistries().getLootTable(lootTableKey);

            if (lootTable == LootTable.EMPTY) {
                continue;
            }

            Entity entityInstance = entityType.create(serverLevel);
            if (entityInstance == null) {
                continue;
            }
            entityInstance.setPos(0, 64, 0);

            // ========== ТЕСТИРУЕМ ВСЕ ТИПЫ УРОНА ==========
            Map<Item, DropStatistics> combinedDrops = new HashMap<>();

            for (DamageSourceConfig config : damageConfigs) {
                simulateKillMethod(
                        serverLevel,
                        server,
                        entityInstance,
                        lootTable,
                        config,
                        fakePlayer,
                        combinedDrops
                );
            }

            // ========== ОБРАБАТЫВАЕМ РЕЗУЛЬТАТЫ ==========
            for (Map.Entry<Item, DropStatistics> entry : combinedDrops.entrySet()) {
                Item item = entry.getKey();
                DropStatistics stats = entry.getValue();

                if (stats.totalDropped > 0) {
                    double averageYield = stats.getAverageYield();
                    String method = stats.getBestMethod();

                    dropMap.computeIfAbsent(item, k -> new ArrayList<>())
                            .add(new MobDropData(item, entityType, averageYield, method));
                }
            }

            processedEntities++;
        }

        long duration = System.currentTimeMillis() - startTime;
        ComplexityAnalyzer.LOGGER.info(
                "MobDropSource initialized. Processed {} valid entities. Found drop info for {} unique items. Time: {}ms",
                processedEntities, dropMap.size(), duration
        );
    }

    /**
     * Создаёт список разных способов убийства
     */
    private List<DamageSourceConfig> createDamageSources(ServerLevel level, net.minecraft.server.level.ServerPlayer player) {
        List<DamageSourceConfig> configs = new ArrayList<>();

        // 1. Обычное убийство игроком
        configs.add(new DamageSourceConfig(
                "Player Attack",
                level.damageSources().playerAttack(player),
                false,
                false,
                player,
                null
        ));

        // 2. Огонь (для жареного мяса)
        configs.add(new DamageSourceConfig(
                "Fire",
                level.damageSources().onFire(),
                true,  // onFire = true
                false,
                player,
                null
        ));

        // 3. Взрыв ЗАРЯЖЕННОГО крипера (для голов мобов)
        Creeper chargedCreeper = EntityType.CREEPER.create(level);
        if (chargedCreeper != null) {
            // Через NBT устанавливаем powered
            CompoundTag creeperNBT = new CompoundTag();
            creeperNBT.putBoolean("powered", true);
            chargedCreeper.readAdditionalSaveData(creeperNBT);
            chargedCreeper.setPos(0, 64, 0);

            configs.add(new DamageSourceConfig(
                    "Charged Creeper",
                    level.damageSources().explosion(chargedCreeper, player),
                    false,
                    true,  // isChargedCreeper
                    player,
                    chargedCreeper  // Сохраняем ссылку
            ));
        }

        // 4. Молния
        configs.add(new DamageSourceConfig(
                "Lightning",
                level.damageSources().lightningBolt(),
                false,
                false,
                player,
                null
        ));

        // 5. Лава
        configs.add(new DamageSourceConfig(
                "Lava",
                level.damageSources().lava(),
                true,
                false,
                player,
                null
        ));

        // 6. Магия
        configs.add(new DamageSourceConfig(
                "Magic",
                level.damageSources().magic(),
                false,
                false,
                player,
                null
        ));

        // 7. Падение
        configs.add(new DamageSourceConfig(
                "Fall Damage",
                level.damageSources().fall(),
                false,
                false,
                null,
                null
        ));

        return configs;
    }

    /**
     * Симулирует убийство моба определённым способом
     */
    private void simulateKillMethod(
            ServerLevel level,
            MinecraftServer ignoredServer,
            Entity entityInstance,
            LootTable lootTable,
            DamageSourceConfig config,
            net.minecraft.server.level.ServerPlayer ignoredFakePlayer,
            Map<Item, DropStatistics> combinedDrops
    ) {
        for (int i = 0; i < SIMULATION_COUNT; i++) {
            try {
                LootParams.Builder builder = new LootParams.Builder(level)
                        .withParameter(LootContextParams.THIS_ENTITY, entityInstance)
                        .withParameter(LootContextParams.ORIGIN, entityInstance.position())
                        .withParameter(LootContextParams.DAMAGE_SOURCE, config.damageSource);

                // Добавляем killer player если есть
                if (config.killerPlayer != null) {
                    builder.withParameter(LootContextParams.LAST_DAMAGE_PLAYER, config.killerPlayer);
                }

                // ========== СПЕЦИАЛЬНЫЕ УСЛОВИЯ ==========

                // Для заряженного крипера используем сохранённую сущность
                if (config.isChargedCreeper && config.attackingEntity != null) {
                    builder.withParameter(LootContextParams.ATTACKING_ENTITY, config.attackingEntity);
                    // Дополнительно для некоторых loot tables
                    builder.withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, config.attackingEntity);
                }

                // Для огня/лавы устанавливаем флаг
                if (config.isOnFire) {
                    entityInstance.setRemainingFireTicks(100);
                }

                LootParams lootParams = builder.create(LootContextParamSets.ENTITY);
                List<ItemStack> drops = lootTable.getRandomItems(lootParams);

                // Сбрасываем огонь после симуляции
                if (config.isOnFire) {
                    entityInstance.clearFire();
                }

                // Собираем статистику
                for (ItemStack stack : drops) {
                    Item item = stack.getItem();
                    int count = stack.getCount();

                    combinedDrops.computeIfAbsent(item, k -> new DropStatistics())
                            .addDrop(config.methodName, count);
                }

            } catch (Exception e) {
                // Игнорируем ошибки (некоторые мобы могут не поддерживать определённые типы урона)
            }
        }
    }

    @Override
    public boolean canProvide(Item item) {
        return dropMap.containsKey(item);
    }

    @Override
    public Optional<BaseResourceData> analyze(Item item) {
        if (!canProvide(item)) {
            return Optional.empty();
        }

        List<MobDropData> possibleSources = dropMap.get(item);

        return possibleSources.stream()
                .map(dropData -> calculateComplexityForDrop(item, dropData))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .min(Comparator.comparingDouble(BaseResourceData::getBaseFactor));
    }

    public List<MobDropData> getDropsForEntity(EntityType<?> entityType) {
        List<MobDropData> results = new ArrayList<>();
        for (Map.Entry<Item, List<MobDropData>> entry : dropMap.entrySet()) {
            for (MobDropData data : entry.getValue()) {
                if (data.sourceMob() == entityType) {
                    results.add(data);
                }
            }
        }
        return results;
    }

    private Optional<BaseResourceData> calculateComplexityForDrop(Item item, MobDropData data) {
        EntityType<?> mobType = data.sourceMob();
        Optional<MobPropertyProvider.MobProperties> mobPropsOpt = mobProvider.getProperties(mobType);
        if (mobPropsOpt.isEmpty()) {
            return Optional.empty();
        }

        var props = mobPropsOpt.get();
        double survivability = props.maxHealth() * (1 + props.armor() / 5.0);
        double threat = 1 + Math.log1p(props.attackDamage());
        double combatPower = survivability * threat;

        double spawnRarityMultiplier = props.isBoss() ? 20.0 : 1.0;

        double finalComplexity = ((combatPower * spawnRarityMultiplier) / data.averageYield())
                * SolverConfig.MOB_DIFFICULTY_SCALER;

        String details = String.format("From %s (Yield: %.2f/kill, Method: %s)",
                mobType.getDescription().getString(),
                data.averageYield(),
                data.killMethod() != null ? data.killMethod() : "Any");

        return Optional.of(new BaseResourceData.Builder(item, this)
                .sourceType(BaseResourceData.ResourceSourceType.MOB_DROP)
                .baseFactor(finalComplexity)
                .details(details)
                .build());
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

    // ========== ВСПОМОГАТЕЛЬНЫЕ КЛАССЫ ==========

    /**
     * Конфигурация способа убийства
     */
    private record DamageSourceConfig(
            String methodName,
            DamageSource damageSource,
            boolean isOnFire,
            boolean isChargedCreeper,
            net.minecraft.server.level.ServerPlayer killerPlayer,
            Entity attackingEntity  // Для заряженного крипера
    ) {}

    /**
     * Статистика дропа предмета
     */
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
}