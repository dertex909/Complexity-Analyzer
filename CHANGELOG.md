This update strips out heavy world-simulation overhead and boundary tracing during scans, preventing tick drops and
memory bloat on heavy modpacks. Under the hood, we overhauled Netty pipeline handling, sanitized entity simulation
lifecycles, and cleaned up legacy configuration options.

---

### 🔥 What's New for Server Admins

* **[Deprecated] Legacy Detection Config**:
    * `DETECTION_SAMPLE_SIZE` is now marked as deprecated in `ComplexityConfig` and is scheduled for complete removal in
      a future release.
* **Less Chat Spam During Scans**:
    * Geoscan countdown announcements now only broadcast at key intervals (every 15 seconds and during the final 10
      seconds) instead of cluttering chat.
* **Strict Locale Enforcement (`JvmDictatorEvents`)**:
    * Automatically enforces `Locale.ROOT` formatting during server tick post-events to prevent number formatting and
      float parsing errors caused by non-English system locales.

---

### ⚡ Performance & Stability

* **Zero-Update Plant Simulation (`PlantSimulator`)**: Switched ground validity checks to modify chunk sections directly
  in memory instead of calling `level.setBlock()`, completely preventing block update cascades, neighbor physics, and
  server lag spikes during plant calculations.
* **Reference-Based Cache Optimization (`PlantSimulator`)**: Migrated plant and ground caches to FastUtil's
  `Reference2ObjectOpenHashMap` for identity-based block lookups, introducing sentinel cache values (`EMPTY_SIMULATION`,
  `NO_GROUND`, `NO_AGE`) to eliminate redundant map lookups.
* **Streamlined Biome Discovery (`FastBiomeFinder`)**: Stripped out slow boundary-refining iterations
  (`refineToCenter`), returning immediate coordinate matches and saving massive amounts of CPU time during terrain
  reconnaissance.
* **Reduced Memory Footprint in Geoscans (`ChunkSnapshot`)**: Replaced string-based block tracking with direct
  `ResourceLocation` keys in chunk snapshots, eliminating redundant `ConcurrentHashMap` block ID caches in
  `HeuristicAnalyzer` and `ChunkAnalyzer`.
* **Safe Entity Drop Simulation (`MobDropSource`)**: Enforced simulation execution on the server thread with scoped
  lifecycle disposal (`entityInstance::discard`), preventing memory leaks and entity desync crashes.
* **Unified Web Pipeline Architecture (`WebPipeline`)**: Consolidated Netty HTTP and WebSocket handler configurations
  into a shared pipeline builder across both integrated and standalone web server channels.
* **Zero-Allocation Singletons**: Swapped out temporary 1-element `ObjectArrayList` allocations in `CsvExportFormat` and
  drop collectors with `ObjectLists.singleton()` and `ObjectLists.emptyList()`.
* **Stripped Dead Code & Unused Localizations**: Removed the legacy `ScanNotifier` class in favor of direct SLF4J logger
  output and cleaned up thousands of obsolete translation keys across all language files.