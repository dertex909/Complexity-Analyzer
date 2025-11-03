package org.complexityanalyzer.cache;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.graph.RecipeGraph;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@SuppressWarnings("unused")
public class GraphCache {
    private RecipeGraph currentGraph;
    private long version;
    private final ReadWriteLock lock;

    public GraphCache() {
        this.currentGraph = null;
        this.version = 0;
        this.lock = new ReentrantReadWriteLock();
    }

    public void setGraph(RecipeGraph graph) {
        lock.writeLock().lock();
        try {
            this.currentGraph = graph;
            this.version++;
            ComplexityAnalyzer.LOGGER.debug("Graph updated to version {}", version);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public RecipeGraph getGraph() {
        lock.readLock().lock();
        try {
            return currentGraph;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isInitialized() {
        lock.readLock().lock();
        try {
            return currentGraph != null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public long getVersion() {
        lock.readLock().lock();
        try {
            return version;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getRecipeCount() {
        lock.readLock().lock();
        try {
            return currentGraph != null ? currentGraph.getTotalRecipeCount() : 0;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getItemCount() {
        lock.readLock().lock();
        try {
            return currentGraph != null ? currentGraph.getAllItems().size() : 0;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean hasRecipe(Item item) {
        lock.readLock().lock();
        try {
            return currentGraph != null && currentGraph.hasRecipe(item);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            if (currentGraph != null) {
                currentGraph.clear();
                currentGraph = null;
            }
            version = 0;
            ComplexityAnalyzer.LOGGER.debug("GraphCache cleared");
        } finally {
            lock.writeLock().unlock();
        }
    }

    public GraphStats getStats() {
        lock.readLock().lock();
        try {
            if (currentGraph == null) {
                return new GraphStats(version, 0, 0, false);
            }

            RecipeGraph.GraphStats graphStats = currentGraph.getStats();
            return new GraphStats(
                    version,
                    graphStats.itemsWithRecipes(),
                    graphStats.totalRecipes(),
                    true
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    public record GraphStats(
            long version,
            int itemCount,
            int recipeCount,
            boolean initialized
    ) {
        @Override
        public @NotNull String toString() {
            return String.format(
                    "GraphStats{version=%d, items=%d, recipes=%d, initialized=%s}",
                    version, itemCount, recipeCount, initialized
            );
        }
    }
}