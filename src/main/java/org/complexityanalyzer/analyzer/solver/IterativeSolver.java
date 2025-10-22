package org.complexityanalyzer.analyzer.solver;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.SourceManager;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.graph.IngredientSlot;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.*;

/**
 * Улучшенный итеративный решатель с оптимизациями:
 * - Обработка только изменившихся предметов
 * - Кэширование стоимости рецептов
 * - Обнаружение циклических зависимостей
 * - Топологическая приоритизация
 * - Улучшенная числовая стабильность
 */
public class IterativeSolver {

    private final RecipeGraph graph;
    private final SourceManager sourceManager;

    // Константы
    private static final double CONVERGENCE_THRESHOLD = 1e-9;
    private static final double EPSILON = 1e-12; // Для сравнения с нулем
    private static final int CYCLE_DETECTION_DEPTH = 100;

    // Кэши для оптимизации
    private final Map<RecipeNode, RecipeCostCache> recipeCostCache;
    private final Map<Item, BaseResourceCache> baseResourceCache;

    // Метрики
    private int recipeCostCalculations = 0;
    private int cacheHits = 0;

    public IterativeSolver(RecipeGraph graph, SourceManager sourceManager) {
        this.graph = graph;
        this.sourceManager = sourceManager;
        this.recipeCostCache = new HashMap<>();
        this.baseResourceCache = new HashMap<>();
    }

    public SolverResult solve() {
        ComplexityAnalyzer.LOGGER.debug("Starting enhanced iterative solver...");
        long startTime = System.currentTimeMillis();

        // Инициализация
        Map<Item, Double> optimalComplexities = initializeComplexities();
        Map<Item, RecipeNode> optimalRecipes;
        optimalRecipes = new HashMap<>();

        // Очередь предметов для обработки (приоритетная)
        PriorityQueue<ItemUpdate> updateQueue = new PriorityQueue<>(
                Comparator.comparingDouble(ItemUpdate::getPriority)
        );

        // Отслеживание зависимостей (обратные связи)
        Map<Item, Set<Item>> dependents = buildDependencyGraph();

        // Добавляем все предметы в очередь для первой итерации
        for (Item item : graph.getCorpus()) {
            updateQueue.offer(new ItemUpdate(item, 0, optimalComplexities.get(item)));
        }

        boolean converged;
        int iterations = 0;
        int itemsProcessed = 0;

        // Основной цикл с приоритетной обработкой
        while (!updateQueue.isEmpty() && iterations < SolverConfig.MAX_ITERATIONS) {
            iterations++;

            // Обрабатываем пакет предметов на текущей итерации
            int batchSize = Math.min(updateQueue.size(), 1000);
            List<ItemUpdate> currentBatch = new ArrayList<>(batchSize);

            for (int i = 0; i < batchSize && !updateQueue.isEmpty(); i++) {
                currentBatch.add(updateQueue.poll());
            }

            boolean batchChanged = false;

            for (ItemUpdate update : currentBatch) {
                Item item = update.getItem();
                double oldComplexity = optimalComplexities.get(item);

                // Вычисляем новую сложность
                ComplexityResult result = calculateComplexityEnhanced(
                        item,
                        optimalComplexities,
                        new HashSet<>()
                );

                double newComplexity = result.complexity;

                // Проверяем на значительное изменение
                if (hasSignificantChange(oldComplexity, newComplexity)) {
                    optimalComplexities.put(item, newComplexity);

                    if (result.recipe != null) {
                        optimalRecipes.put(item, result.recipe);
                    }

                    // Инвалидируем кэш для этого предмета
                    invalidateCache(item);

                    // Добавляем зависимые предметы в очередь
                    Set<Item> deps = dependents.getOrDefault(item, Collections.emptySet());
                    for (Item dependent : deps) {
                        updateQueue.offer(new ItemUpdate(
                                dependent,
                                iterations,
                                optimalComplexities.get(dependent)
                        ));
                    }

                    batchChanged = true;
                    itemsProcessed++;
                }
            }

            // Если ничего не изменилось, возможно достигли сходимости
            if (!batchChanged && updateQueue.isEmpty()) {
                break;
            }
        }

        converged = updateQueue.isEmpty();
        long totalTime = System.currentTimeMillis() - startTime;

        // Логирование результатов
        logResults(iterations, totalTime, converged, itemsProcessed);

        // Очистка кэшей
        clearCaches();

        return new SolverResult(
                optimalComplexities,
                optimalRecipes, // <--- Передаем результат нашей работы
                iterations,
                totalTime,
                converged
        );
    }

    /**
     * Инициализация начальных значений сложности
     */
    private Map<Item, Double> initializeComplexities() {
        Map<Item, Double> complexities = new HashMap<>();

        for (Item item : graph.getCorpus()) {
            Optional<BaseResourceData> dataOpt = sourceManager.analyze(item);

            if (dataOpt.isPresent() && dataOpt.get().getSourceItems().isEmpty()) {
                // Базовый ресурс без зависимостей
                complexities.put(item, dataOpt.get().getBaseFactor());
                baseResourceCache.put(item, new BaseResourceCache(
                        dataOpt.get().getBaseFactor(),
                        true
                ));
            } else {
                // Инициализируем большим значением
                complexities.put(item, Double.POSITIVE_INFINITY);
            }
        }

        return complexities;
    }

    /**
     * Построение графа зависимостей (обратные связи)
     */
    private Map<Item, Set<Item>> buildDependencyGraph() {
        Map<Item, Set<Item>> dependents = new HashMap<>();

        for (Item item : graph.getCorpus()) {
            if (!graph.hasRecipe(item)) continue;

            for (RecipeNode recipe : graph.getRecipes(item)) {
                for (IngredientSlot slot : recipe.getIngredients()) {
                    for (Item ingredient : slot.getVariants()) {
                        dependents.computeIfAbsent(ingredient, k -> new HashSet<>())
                                .add(item);
                    }
                }
            }

            // Также учитываем базовые ресурсы с зависимостями
            Optional<BaseResourceData> dataOpt = sourceManager.analyze(item);
            if (dataOpt.isPresent()) {
                for (Item sourceItem : dataOpt.get().getSourceItems().keySet()) {
                    dependents.computeIfAbsent(sourceItem, k -> new HashSet<>())
                            .add(item);
                }
            }
        }

        return dependents;
    }

    /**
     * Улучшенный расчет сложности с обнаружением циклов
     */
    private ComplexityResult calculateComplexityEnhanced(
            Item item,
            Map<Item, Double> currentComplexities,
            Set<Item> visitedInPath
    ) {
        if (visitedInPath.size() > CYCLE_DETECTION_DEPTH) {
            ComplexityAnalyzer.LOGGER.warn("Cycle detection depth exceeded for item: {}", item);
            return new ComplexityResult(Double.POSITIVE_INFINITY, null);
        }

        if (visitedInPath.contains(item)) {
            ComplexityAnalyzer.LOGGER.debug("Circular dependency detected for item: {}", item);
            return new ComplexityResult(Double.POSITIVE_INFINITY, null);
        }

        visitedInPath.add(item);

        try {
            double sourceCost = getBaseResourceCostEnhanced(item, currentComplexities);

            ComplexityResult craftResult = getCraftingCostEnhanced(
                    item,
                    currentComplexities
            );

            if (sourceCost <= craftResult.complexity) {
                return new ComplexityResult(sourceCost, null);
            } else {
                return craftResult;
            }

        } finally {
            visitedInPath.remove(item);
        }
    }

    private ComplexityResult getCraftingCostEnhanced(
            Item item,
            Map<Item, Double> currentComplexities
    ) {
        if (!graph.hasRecipe(item)) {
            return new ComplexityResult(Double.POSITIVE_INFINITY, null);
        }

        List<RecipeNode> allRecipes = graph.getRecipes(item);

        // Фильтруем рецепты по категориям
        List<RecipeNode> recipesToConsider = filterRecipes(allRecipes);

        if (recipesToConsider.isEmpty()) {
            return new ComplexityResult(Double.POSITIVE_INFINITY, null);
        }

        // Находим рецепт с минимальной стоимостью
        double minCost = Double.POSITIVE_INFINITY;
        RecipeNode bestRecipe = null;

        for (RecipeNode recipe : recipesToConsider) {
            double cost = calculateRecipeCostEnhanced(
                    recipe,
                    currentComplexities
            );

            if (cost < minCost) {
                minCost = cost;
                bestRecipe = recipe;
            }
        }

        return new ComplexityResult(minCost, bestRecipe);
    }

    /**
     * Фильтрация рецептов по приоритету
     */
    private List<RecipeNode> filterRecipes(List<RecipeNode> allRecipes) {
        // Приоритет 1: PRIMARY рецепты
        List<RecipeNode> primaryRecipes = allRecipes.stream()
                .filter(r -> r.getCategory() == RecipeCategory.PRIMARY)
                .toList();

        if (!primaryRecipes.isEmpty()) {
            return primaryRecipes;
        }

        // Приоритет 2: Исключаем проблемные категории
        List<RecipeNode> filteredRecipes = allRecipes.stream()
                .filter(r -> r.getCategory() != RecipeCategory.UNPROCESSABLE
                        && r.getCategory() != RecipeCategory.RECYCLING
                        && r.getCategory() != RecipeCategory.STORAGE_COMPRESSION
                        && r.getCategory() != RecipeCategory.STORAGE_DECOMPRESSION)
                .toList();

        // Если после фильтрации пусто - возвращаем всё кроме UNPROCESSABLE
        if (filteredRecipes.isEmpty()) {
            return allRecipes.stream()
                    .filter(r -> r.getCategory() != RecipeCategory.UNPROCESSABLE)
                    .toList();
        }

        return filteredRecipes;
    }

    /**
     * Расчет стоимости рецепта с кэшированием
     */
    private double calculateRecipeCostEnhanced(
            RecipeNode recipe,
            Map<Item, Double> currentComplexities
    ) {
        recipeCostCalculations++;

        // Проверяем кэш
        RecipeCostCache cached = recipeCostCache.get(recipe);
        if (cached != null && cached.isValid(currentComplexities)) {
            cacheHits++;
            return cached.cost;
        }

        // Вычисляем стоимость ингредиентов
        double ingredientsCost = 0;
        Map<Item, Double> usedComplexities = new HashMap<>();

        for (IngredientSlot slot : recipe.getIngredients()) {
            double slotCost = getSlotCostEnhanced(
                    slot,
                    currentComplexities,
                    usedComplexities
            );

            if (Double.isInfinite(slotCost)) {
                return Double.POSITIVE_INFINITY;
            }

            ingredientsCost += slotCost * slot.getCount();
        }

        // Применяем множитель рецепта и делим на количество результата
        double totalCost = (ingredientsCost * recipe.getRecipeMultiplier())
                / recipe.getResultCount();

        // Кэшируем результат
        recipeCostCache.put(recipe, new RecipeCostCache(
                totalCost,
                usedComplexities
        ));

        return totalCost;
    }

    /**
     * Получение стоимости слота с выбором оптимального варианта
     */
    private double getSlotCostEnhanced(
            IngredientSlot slot,
            Map<Item, Double> currentComplexities,
            Map<Item, Double> usedComplexities
    ) {
        if (slot.getVariants().isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }

        double minCost = Double.POSITIVE_INFINITY;
        Item bestVariant = null;

        for (Item variant : slot.getVariants()) {
            double cost = currentComplexities.getOrDefault(variant, Double.POSITIVE_INFINITY);
            if (cost < minCost) {
                minCost = cost;
                bestVariant = variant;
            }
        }

        if (bestVariant != null) {
            usedComplexities.put(bestVariant, minCost);
        }

        return minCost;
    }

    /**
     * Улучшенный расчет стоимости базового ресурса
     */
    private double getBaseResourceCostEnhanced(
            Item item,
            Map<Item, Double> currentComplexities
    ) {
        // Проверяем кэш
        BaseResourceCache cached = baseResourceCache.get(item);
        if (cached != null && cached.isSimple) {
            return cached.cost;
        }

        // =========================================================
        // ИСПРАВЛЕНИЕ: Получаем ВСЕ доступные источники
        // =========================================================
        List<BaseResourceData> allSources = sourceManager.findAllSources(item);

        if (allSources.isEmpty()) {
            return BaseResourceData.ResourceSourceType.UNOBTAINABLE.getBaseMultiplier();
        }

        double bestCost = Double.POSITIVE_INFINITY;
        boolean foundSimpleSource = false;

        // Проходим по всем источникам и выбираем лучший доступный
        for (BaseResourceData data : allSources) {
            double sourceCost;

            // Простой базовый ресурс (без зависимостей)
            if (data.getSourceItems().isEmpty()) {
                sourceCost = data.getBaseFactor();
                foundSimpleSource = true;
            } else {
                // Базовый ресурс с зависимостями
                double dependencyCost = 0;
                boolean hasInfiniteDependency = false;

                for (Map.Entry<Item, Double> entry : data.getSourceItems().entrySet()) {
                    Item sourceItem = entry.getKey();
                    Double amount = entry.getValue();

                    if (amount == null || amount <= EPSILON) {
                        continue;
                    }

                    double itemCost = currentComplexities.getOrDefault(
                            sourceItem,
                            Double.POSITIVE_INFINITY
                    );

                    if (Double.isInfinite(itemCost)) {
                        hasInfiniteDependency = true;
                        break;
                    }

                    dependencyCost += itemCost * amount;
                }

                // Пропускаем источники с бесконечными зависимостями
                if (hasInfiniteDependency) {
                    continue;
                }

                sourceCost = data.getBaseFactor() + dependencyCost;
            }

            // Выбираем минимальную стоимость
            if (sourceCost < bestCost) {
                bestCost = sourceCost;
            }
        }

        // Кэшируем только простые источники для быстрого доступа
        if (foundSimpleSource && bestCost < Double.POSITIVE_INFINITY) {
            baseResourceCache.put(item, new BaseResourceCache(bestCost, true));
        }

        return bestCost;
    }

    /**
     * Проверка на значительное изменение
     */
    private boolean hasSignificantChange(double oldValue, double newValue) {
        if (Double.isInfinite(oldValue) && Double.isInfinite(newValue)) {
            return false;
        }

        if (Double.isInfinite(oldValue) || Double.isInfinite(newValue)) {
            return true;
        }

        double delta = Math.abs(oldValue - newValue);
        double relative = oldValue > EPSILON ? delta / oldValue : delta;

        return delta > CONVERGENCE_THRESHOLD && relative > CONVERGENCE_THRESHOLD;
    }

    /**
     * Инвалидация кэша для предмета
     */
    private void invalidateCache(Item item) {
        baseResourceCache.remove(item);

        // Инвалидируем кэш рецептов, которые используют этот предмет
        recipeCostCache.entrySet().removeIf(entry ->
                entry.getValue().dependsOn(item)
        );
    }

    /**
     * Очистка всех кэшей
     */
    private void clearCaches() {
        recipeCostCache.clear();
        baseResourceCache.clear();
    }

    /**
     * Логирование результатов
     */
    private void logResults(int iterations, long totalTime, boolean converged, int itemsProcessed) {
        if (!converged) {
            ComplexityAnalyzer.LOGGER.warn(
                    "Enhanced solver did NOT converge after {} iterations. " +
                            "Results may be approximate.",
                    SolverConfig.MAX_ITERATIONS
            );
        }

        ComplexityAnalyzer.LOGGER.debug(
                "Enhanced solver finished: {}ms, {} iterations, {} items processed",
                totalTime, iterations, itemsProcessed
        );

        if (recipeCostCalculations > 0) {
            double hitRate = 100.0 * cacheHits / recipeCostCalculations;
            ComplexityAnalyzer.LOGGER.debug(
                    "Cache stats: {} recipe calculations, {} cache hits ({} hit rate)",
                    recipeCostCalculations,
                    cacheHits,
                    String.format("%.2f%%", hitRate)
            );
        }
    }

    // ==================== Вспомогательные классы ====================

    /**
     * Обновление предмета в очереди
     */
    private static class ItemUpdate {
        private final Item item;
        private final int iteration;
        private final double currentComplexity;

        public ItemUpdate(Item item, int iteration, double currentComplexity) {
            this.item = item;
            this.iteration = iteration;
            this.currentComplexity = currentComplexity;
        }

        public Item getItem() {
            return item;
        }

        public double getPriority() {
            // Приоритет: сначала простые (с меньшей сложностью), потом по итерации
            return Double.isInfinite(currentComplexity)
                    ? Double.MAX_VALUE
                    : currentComplexity * 0.001 + iteration;
        }
    }

    /**
     * Результат вычисления сложности
     */
    private static class ComplexityResult {
        final double complexity;
        final RecipeNode recipe;

        ComplexityResult(double complexity, RecipeNode recipe) {
            this.complexity = complexity;
            this.recipe = recipe;
        }
    }

    /**
     * Кэш стоимости рецепта
     */
    private static class RecipeCostCache {
        final double cost;
        final Map<Item, Double> dependencies;

        RecipeCostCache(double cost, Map<Item, Double> dependencies) {
            this.cost = cost;
            this.dependencies = new HashMap<>(dependencies);
        }

        boolean isValid(Map<Item, Double> currentComplexities) {
            // Проверяем, что зависимости не изменились
            for (Map.Entry<Item, Double> entry : dependencies.entrySet()) {
                Double currentCost = currentComplexities.get(entry.getKey());
                if (currentCost == null ||
                        Math.abs(currentCost - entry.getValue()) > CONVERGENCE_THRESHOLD) {
                    return false;
                }
            }
            return true;
        }

        boolean dependsOn(Item item) {
            return dependencies.containsKey(item);
        }
    }

    /**
     * Кэш базового ресурса
     */
    private static class BaseResourceCache {
        final double cost;
        final boolean isSimple;

        BaseResourceCache(double cost, boolean isSimple) {
            this.cost = cost;
            this.isSimple = isSimple;
        }
    }
}