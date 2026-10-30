This update introduces a built-in interactive documentation tab and loaded mods explorer to the web dashboard, backed by
an ahead-of-time Global Loot Modifier indexer that eliminates simulation lag during scans.

---

### 🔥 What's New for Modpack Creators & Developers

* **Built-in Interactive Web Pack Documentation**:
    * **Live Developer Guide**: Added a dedicated "Documentation" tab to the viewer explaining everything needed to
      build custom UI tabs—covering pack directory structures, `.tab.json` manifests, Shadow DOM styling, and the ES
      module lifecycle (`mount`/`unmount`).
    * **Self-Referential Example Pack**: The documentation itself is bundled and runs as a standalone web pack
      (`complexity-documentation-or-example.zip`) featuring markdown parsing and syntax highlighting (`highlight.js`),
      downloadable directly from the UI as a plug-and-play starter template.
    * **Dynamic Page Architecture**: Demonstrates multi-page tab manifests (`documentation.tab.json`), sub-page
      switching, module-relative asset linking, and custom `.md` MIME resolution out of the box.
* **Interactive Mods Overview & Content Navigation**:
    * **Loaded Mods Explorer**: The Overview tab now features a comprehensive breakdown of all loaded mods, their exact
      versions, and total counts for indexed items, mobs, fluids, machines, and recognized recipes.
    * **Instant Filter Deep-links**: Clicking any count badge (such as items, fluids, or machines) instantly jumps to
      that tab with the table pre-filtered to that specific mod.
    * **Content-Only Toggle**: Quick filter button to hide background libraries and display only mods contributing
      actual gameplay content.
* **Automatic Modrinth Update Checker**:
    * The dashboard now queries Modrinth in the background on startup, checking if a newer build of Complexity Analyzer
      is available for your Minecraft version and highlighting available updates directly in the header.
* **Web Pack Custom MIME Types & Collision Merging**:
    * **Custom MIME Types**: Mod developers can now declare custom `mimeTypes` mappings directly inside `tab.json`
      manifests to serve arbitrary web assets without server errors.
    * **Namespace Merging**: Web packs sharing the same namespace are now combined via `CompositeSource` instead of
      colliding and triggering 404s.
    * **Safe Zip Handling**: Pack archives are now streamed in memory using `ZipInputStream` with a bounded 8 MB memory
      cache, preventing Windows file-lock issues during hot reloads.
* **Multi-Route Villager Trade Solving**:
    * `VillagerTradeSource` now implements `IMultiSourceProvider`, allowing the complexity solver to evaluate every
      available trade level and profession variant instead of picking a single path upfront.
    * Unlocked previously skipped trade types (such as enchanted items and exploration maps) via isolated probe
      execution.
    * UI trade cards now provide full technical breakdowns, displaying profession, level, internal index, and exact
      trade ingredient ratios.
* **Industrial UI Design Refresh**:
    * Flattened borders and eliminated rounded corners across panels, cards, modal overlays, and graph nodes for higher
      information density and cleaner contrast.
    * Added one-click clipboard copying for error stack traces in custom web tabs.

---

### ⚡ Performance & Stability

* **Ahead-of-Time Loot Modifier Indexing (`LootOptimizer`)**: Replaced expensive runtime loot table diffing and
  thread-local snapshotting with an ahead-of-time condition parser (`LootModifierManagerMixin`). Loot table restrictions
  are now indexed on startup/reload, allowing untargeted tables to bypass simulation hooks instantly and drastically
  lowering GC churn.
* **Non-Blocking Web Asset Serving (`CabinNettyHandler`)**: Offloaded external resource resolution and disk reads to the
  compute thread pool, keeping Netty event loop threads completely free during heavy asset requests.
* **Netty ByteBuf Leak Fix**: Added active-channel verification prior to flushing HTTP responses, ensuring allocated
  buffers are safely released if a browser connection drops mid-flight.
* **Bytecode & Class Reflection Caching (`MachineAsmScanner`, `MachineControllerGraphScanner`)**: Implemented concurrent
  bytecode and class lookup caches with sentinel fallbacks, eliminating repeated disk access and expensive
  `Class.forName` reflection loops during machine discovery.
* **Zero-Allocation Registry Traversals**: Replaced `.holders().toList()` allocations across hardcoded interaction
  modules (`BucketInteractions`, `AxeStripping`, `CoralModule`, `CopperOxidation`, `ToolInteractions`) with direct
  registry iteration.
* **Re-entrant Simulation Isolation (`ProbeScope`)**: Upgraded probe isolation from a binary flag to a depth-tracked
  counter, preventing nested simulation passes from prematurely disabling fake-player protection.
* **Debounced WebDev Watcher (`ViewerWatcher`)**: Re-engineered development file watching with a 250ms quiet period and
  proper directory lifecycle handling (`ENTRY_CREATE`/`ENTRY_DELETE`), avoiding reload storms and locked files during
  rapid code editing.
* **Farming Recursion Guard (`FarmingSource`)**: Fixed a circular calculation bug by ignoring self-referential crop
  drops (planting an item to harvest the identical item) and normalized output yield precision.