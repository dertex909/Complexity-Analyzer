package org.complexityanalyzer.analyzer.resource.sources;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class PassiveProductionSource implements IResourceSource {

    private final Map<Item, PassiveDropInfo> productionMap = new HashMap<>();

    private record PassiveDropInfo(EntityType<?> sourceType, double ticksPerItem, String method) {}

    @Override
    public void initialize(net.minecraft.world.level.Level level) {
        productionMap.put(Items.EGG, new PassiveDropInfo(EntityType.CHICKEN, 9000.0, "Passive (lays egg)"));

        productionMap.put(Items.WHITE_WOOL, new PassiveDropInfo(EntityType.SHEEP, 2400.0, "Shearing"));

        productionMap.put(Items.TURTLE_SCUTE, new PassiveDropInfo(EntityType.TURTLE, 24000.0, "Grows Up"));

        productionMap.put(Items.MILK_BUCKET, new PassiveDropInfo(EntityType.COW, 20.0, "Milking"));

        productionMap.put(Items.MUSHROOM_STEW, new PassiveDropInfo(EntityType.MOOSHROOM, 20.0, "Milking with Bowl"));
    }

    @Override
    public boolean canProvide(Item item) {
        return productionMap.containsKey(item);
    }

    @Override
    public Optional<BaseResourceData> analyze(Item item) {
        if (!canProvide(item)) {
            return Optional.empty();
        }

        PassiveDropInfo info = productionMap.get(item);

        final double UPKEEP_COST_PER_TICK = 0.001;
        double complexity = info.ticksPerItem() * UPKEEP_COST_PER_TICK;

        complexity += 0.1;

        String details = String.format("From %s (Avg. %d ticks, Method: %s)",
                info.sourceType().getDescription().getString(),
                (int)info.ticksPerItem(),
                info.method());

        return Optional.of(new BaseResourceData.Builder(item, this)
                .sourceType(BaseResourceData.ResourceSourceType.FARMING)
                .baseFactor(complexity)
                .details(details)
                .build());
    }

    @Override
    public BaseResourceData.ResourceSourceType getSourceType() {
        return BaseResourceData.ResourceSourceType.FARMING;
    }

    @Override
    public int getPriority() {
        return 30;
    }

    @Override
    public String getName() {
        return "PassiveProductionSource";
    }
}