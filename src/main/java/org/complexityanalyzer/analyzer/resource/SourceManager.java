package org.complexityanalyzer.analyzer.resource;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SourceManager {
    private final Map<Class<? extends IResourceSource>, IResourceSource> sourcesByType = new HashMap<>();
    private final List<IResourceSource> sources;
    private final Map<Item, Optional<BaseResourceData>> cache = new ConcurrentHashMap<>();

    public SourceManager(List<IResourceSource> initialSources) {
        this.sources = new ArrayList<>(initialSources);
        initialSources.forEach(source -> sourcesByType.put(source.getClass(), source));
        sortSources();
    }

    private void sortSources() {
        this.sources.sort(Comparator.comparingInt(IResourceSource::getPriority).reversed());
    }

    public void addSourceAndRefresh(IResourceSource newSource) {
        ComplexityAnalyzer.LOGGER.debug("Adding new resource source: {} with priority {}", newSource.getName(), newSource.getPriority());

        IResourceSource oldSource = sourcesByType.remove(newSource.getClass());
        if (oldSource != null) {
            sources.remove(oldSource);
        }

        sources.add(newSource);
        sourcesByType.put(newSource.getClass(), newSource);

        sortSources();
        clearCache();
    }



    public void initialize(Level level) {
        for (IResourceSource source : sources) {
            try {
                source.initialize(level);
                ComplexityAnalyzer.LOGGER.debug("Initialized resource source: {}", source.getName());
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.error("Failed to initialize source: {}", source.getName(), e);
            }
        }
    }

    public double getBaseFactor(Item item) {
        return analyze(item)
                .map(BaseResourceData::getBaseFactor)
                .orElse(BaseResourceData.ResourceSourceType.UNOBTAINABLE.getBaseMultiplier());
    }

    public Optional<BaseResourceData> analyze(Item item) {
        return cache.computeIfAbsent(item, this::performAnalysis);
    }

    private Optional<BaseResourceData> performAnalysis(Item item) {
        for (IResourceSource source : sources) {
            if (source.canProvide(item)) {
                Optional<BaseResourceData> result = source.analyze(item);
                if (result.isPresent()) {
                    return result;
                }
            }
        }

        return Optional.of(createUnobtainableData(item));
    }


    private BaseResourceData createUnobtainableData(Item item) {
        return new BaseResourceData.Builder(item)
                .sourceType(BaseResourceData.ResourceSourceType.UNOBTAINABLE)
                .baseFactor(BaseResourceData.ResourceSourceType.UNOBTAINABLE.getBaseMultiplier())
                .details("No source capable of providing this item was found.")
                .build();
    }

    public List<BaseResourceData> findAllSources(Item item) {
        return sources.stream()
                .filter(source -> source.canProvide(item))
                .map(source -> source.analyze(item))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    public void clearCache() {
        cache.clear();
        ComplexityAnalyzer.LOGGER.debug("SourceManager cache cleared.");
    }

    public List<IResourceSource> getSources() {
        return Collections.unmodifiableList(sources);
    }

    public <T extends IResourceSource> Optional<T> getSourceByType(Class<T> type) {
        return Optional.ofNullable(sourcesByType.get(type))
                .map(type::cast);
    }
}