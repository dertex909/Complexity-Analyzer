package org.complexityanalyzer.analyzer.resource.sources;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.data.MobDropData;
import org.complexityanalyzer.analyzer.resource.providers.MobPropertyProvider;
import org.complexityanalyzer.analyzer.solver.SolverConfig;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.*;

public class MobDropSource implements IResourceSource {
    private final MobPropertyProvider mobProvider;
    private final Map<Item, List<MobDropData>> dropMap = new HashMap<>();
    private static final int SIMULATION_COUNT = 1000;

    public MobDropSource(MobPropertyProvider mobProvider) {
        this.mobProvider = mobProvider;
    }

    @Override
    public void initialize(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        MinecraftServer server = serverLevel.getServer();

        ComplexityAnalyzer.LOGGER.debug("Initializing MobDropSource by simulating all mob loot tables...");
        int processedEntities = 0;

        com.mojang.authlib.GameProfile fakePlayerProfile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), "[ComplexityAnalyzer]");
        net.minecraft.server.level.ServerPlayer fakePlayer = new net.minecraft.server.level.ServerPlayer(server, serverLevel, fakePlayerProfile, net.minecraft.server.level.ClientInformation.createDefault());

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

            Map<Item, Integer> totalDrops = new HashMap<>();
            for (int i = 0; i < SIMULATION_COUNT; i++) {

                LootParams.Builder lootParamsBuilder = new LootParams.Builder(serverLevel)
                        .withParameter(LootContextParams.THIS_ENTITY, entityInstance)
                        .withParameter(LootContextParams.ORIGIN, entityInstance.position())
                        .withParameter(LootContextParams.DAMAGE_SOURCE, serverLevel.damageSources().playerAttack(fakePlayer))
                        .withParameter(LootContextParams.LAST_DAMAGE_PLAYER, fakePlayer);


                List<ItemStack> drops = lootTable.getRandomItems(lootParamsBuilder.create(LootContextParamSets.ENTITY));
                for (ItemStack stack : drops) {
                    totalDrops.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }

            for (Map.Entry<Item, Integer> entry : totalDrops.entrySet()) {
                Item item = entry.getKey();
                double averageYield = (double) entry.getValue() / SIMULATION_COUNT;

                if (averageYield > 0) {
                    dropMap.computeIfAbsent(item, k -> new ArrayList<>())
                            .add(new MobDropData(item, entityType, averageYield));
                }
            }
            processedEntities++;
        }
        ComplexityAnalyzer.LOGGER.info("MobDropSource initialized. Processed {} valid entities. Found drop info for {} unique items.", processedEntities, dropMap.size());
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

        double finalComplexity = ((combatPower * spawnRarityMultiplier) / data.averageYield()) * SolverConfig.MOB_DIFFICULTY_SCALER;

        String details = String.format("From %s (Yield: %.2f/kill)",
                mobType.getDescription().getString(), data.averageYield());

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
    public int getPriority() { return 20; }

    @Override
    public String getName() { return "MobDropSource"; }
}