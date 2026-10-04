v0.9.2 delivers major under-the-hood performance optimizations, significantly cutting down memory allocation and
world-scan overhead through smart loot modifier caching and leaner recipe graph structures. Expect faster startup
analysis passes, zero Netty buffer leaks, and a noticeably smoother profiling experience on heavy modpacks.

---

### 🔥 What's New for Modpack Developers

* **Smart Loot Modifier Cache (`LootOptimizer`)**:
  * **Adaptive Bypass**: Probes loot tables during automated scans and identifies tables unmodified by modded hooks.
    Once verified, it completely bypasses redundant `CommonHooks.modifyLoot` passes, drastically cutting CPU overhead
    during `UniversalLootSource` and mob drop generation.
  * **Configurable Sampling**: Evaluates loot modifications dynamically across an initial 30-call sample threshold
    before marking tables as safe to skip.
* **Modernized Recipe Serialization**:
  * Migrated item cache serialization in `RecipeGraphCache` to vanilla's `ItemStack.OPTIONAL_STREAM_CODEC` (bumped cache
    format to `VERSION = 5`).
  * Yields faster disk serialization, cleaner cache loads, and tighter consistency with vanilla component data.

---

### ⚡ Performance & Stability

* **Recipe Graph Memory Optimization (`RecipeGraph`)**: Replaced bloated `ConcurrentHashMap` structures with
  high-performance FastUtil collections (`Reference2ObjectOpenHashMap`, `ObjectArrayList`). This optimizes item lookups
  and substantially reduces memory overhead when indexing massive recipe registries.
* **Resource Leak Prevention via RAII (`Scope`)**: Standardized lock acquisition, thread cleanup, and Netty `ByteBuf`
  release routines across `AnalysisEngine`, cache I/O, and `ScanExecutor` using an `AutoCloseable` scope pattern,
  eliminating edge-case buffer leaks on cancelled tasks or crashed workers.
* **Context-Aware Log Silencing (`LogFilter`)**: Unified log interception into a scoped auto-closing filter. Silences
  known synthetic loot simulation noise (such as unsmeltable drops, missing enchantments, and `DISTXFORM` markers)
  without hiding real runtime errors.
* **Null-Safe Component Matching (`ItemStackIdentity`)**: Relocated identity checks to core utilities and hardened stack
  comparisons against null references, preventing unexpected NPE crashes during item parsing.
* **Internal Engine Refactoring**: Streamlined reflection scanning in `MachineReflectionScanner`, extracted dynamic
  registry argument resolution in `RecipeMetadata`, and simplified path resolution algorithms in the machine controller
  scanner.