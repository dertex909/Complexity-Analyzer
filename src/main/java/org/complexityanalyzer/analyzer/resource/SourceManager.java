package org.complexityanalyzer.analyzer.resource;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.sources.EmpiricalBlockSource;
import org.complexityanalyzer.analyzer.resource.sources.TheoreticalBlockSource;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SourceManager {
    private final Map<Class<? extends IResourceSource>, IResourceSource> sourcesByType = new HashMap<>();
    private final List<IResourceSource> sources;
    private final Map<Item, Optional<BaseResourceData>> cache = new ConcurrentHashMap<>();

    public SourceManager(List<IResourceSource> initialSources) {
        this.sources = new ArrayList<>(initialSources);
        initialSources.forEach(source -> sourcesByType.put(source.getClass(), source));
        sortSources();
    }

    public void removeSourcesByType(Class<? extends IResourceSource> type) {
        boolean removed = sources.removeIf(type::isInstance);
        sourcesByType.entrySet().removeIf(entry -> type.isAssignableFrom(entry.getKey()));
        if (removed) {
            clearCache();
            ComplexityAnalyzer.LOGGER.info("Removed all sources of type {}", type.getSimpleName());
        }
    }

    private void sortSources() {
        this.sources.sort(Comparator.comparingInt(IResourceSource::getPriority).reversed());
    }

    public void addSourceAndRefresh(IResourceSource newSource) {
        ComplexityAnalyzer.LOGGER.debug("Adding new resource source: {} with priority {}", newSource.getName(), newSource.getPriority());
        sources.removeIf(s -> s.getClass().equals(newSource.getClass()));
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
                .orElse(-1.0);
    }

    public Optional<BaseResourceData> analyze(Item item) {
        return cache.computeIfAbsent(item, this::performAnalysis);
    }

    private Optional<BaseResourceData> performAnalysis(Item item) {
        Stream<IResourceSource> sourceStream = sources.stream();

        boolean empiricalReady = getSourceByType(EmpiricalBlockSource.class)
                .map(EmpiricalBlockSource::isReady)
                .orElse(false);

        if (empiricalReady) {
            sourceStream = sourceStream.filter(source -> !(source instanceof TheoreticalBlockSource));
        }

        return sourceStream
                .filter(source -> source.canProvide(item))
                .map(source -> source.analyze(item))
                .flatMap(Optional::stream)
                .min(Comparator.comparingDouble(BaseResourceData::getBaseFactor));
    }

    public List<BaseResourceData> findAllSources(Item item) {
        return sources.stream()
                .filter(source -> source.canProvide(item))
                .map(source -> source.analyze(item))
                .flatMap(Optional::stream)
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
        return sources.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst();
    }
}