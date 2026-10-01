Complexity Analyzer v0.9.1 introduces under-the-hood scan stability improvements, centralized thread management, and
reflection caching to eliminate background task stalls during world analysis.

---

### 🔥 What's New for Server Admins

* **Adaptive Biome Scanning**: Reconnaissance scans now actively track search stagnation and automatically abandon
  unreachable biomes after repeated failed relocations, preventing world scanning tasks from getting stuck in infinite
  loops.

---

### ⚡ Performance & Stability

* **Thread Pool Management (`ThreadPoolManager`)**: Switched scan executors and `DataRefiner` from unconstrained virtual
  thread executors to centralized compute and ForkJoin thread pools, preventing thread bloat and stabilizing CPU
  scheduling.
* **Recipe Reflection Caching (`MachineTypeUnwrapper`)**: Cached zero-argument recipe getter lookups using `ClassValue`,
  significantly reducing reflection overhead when discovering machine recipe types.
* **Scan Memory Optimization (`ScanSession`)**: Migrated attempted chunk coordinate tracking to primitive fastutil sets,
  cutting RAM usage and GC churn during large-area scans.
* **Localization Cleanup (`ServerLanguage`)**: Simplified server-side translation resolution to use vanilla translatable
  fallbacks, reducing unnecessary string formatting allocations.
* **Internal Refactoring**: Cleaned up unused recipe inspector hooks in `StandardRecipeMethods` and removed redundant
  session counters.