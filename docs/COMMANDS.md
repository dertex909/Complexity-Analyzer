# Complexity Analyzer — Command Reference

Every command is a sub-command of `/complexity`. There is no bare `/complexity`
action — always pick a sub-command below.

## Permissions

| Badge               | Meaning                                                                           |
|---------------------|-----------------------------------------------------------------------------------|
| 👤 **Player**       | Usable by anyone. Read-only analysis/inspection.                                  |
| 🛡 **OP (level 2)** | Requires operator permission. Changes state, writes files, or can lag the server. |

All command output is **translated to each player's own client language** server-side
(52 locales shipped). Numbers, item names and icons stay as-is.

---

## Command Index

```
/complexity
├── system                                    (engine & server diagnostics)
│   ├── status                                👤  engine state + data overview
│   ├── stats                                 👤  detailed data stats
│   ├── tps                                   👤  TPS / MSPT / memory
│   ├── threads                               🛡  thread-pool statistics
│   ├── reload                                🛡  full re-analysis (laggy)
│   └── cache
│       ├── info                              👤  on-disk cache report
│       └── clear [<cache_id>]                🛡  delete all or specific cache files
├── analyze
│   ├── item   <item_id>                      👤  full item complexity report
│   ├── entity <entity_id>                    👤  mob combat/difficulty report
│   └── loot   <loot_table_id>                👤  loot-table breakdown
├── resource <item_id>                        👤  base-resource origin report
├── web
│   ├── url [link]                            👤  show web dashboard URL (button or raw text)
│   ├── status                                👤  web backend status
│   └── reload                                🛡  rebuild web data
├── export                                    🛡  (whole group is OP-only)
│   ├── items <format>
│   │   ├── all                               🛡  export all items
│   │   ├── category <category_name>          🛡  export items in a category
│   │   ├── top <count>                       🛡  export top N items
│   │   └── single <item_id>                  🛡  export single item
│   └── mobs <format>
│       ├── all                               🛡  export all mobs
│       ├── category <category_name>          🛡  export mobs in a category
│       ├── top <count>                       🛡  export top N mobs
│       └── single <mob_id>                   🛡  export single mob
└── geoscan
    ├── start [<profile>] [chunks] [force]    🛡  show profiles or schedule/force a world scan
    ├── stop                                  🛡  stop the running scan
    ├── status                                👤  scan progress
    └── clear                                 🛡  wipe collected geo-data
```

---

## 🔬 `/complexity analyze …` 👤

Deep-dive analysis of game elements. All three sub-commands are available to any player.

### `analyze item <item_id>`
Full complexity report for an item — the core command of the mod.
- **`item_id`** — item resource location, e.g. `minecraft:netherite_ingot`. Tab-completes.
- **Output:**
  - **Complexity score & category** — final cost and human rank (`Trivial` → `Transcendent`, plus `Unobtainable`/`Uncalculable`) with icon and a visual bar.
  - **Source information** — crafted vs. base resource, crafting depth, and how many recipes use it.
  - **Alternative sources** — other ways to obtain it (mob drop, loot, etc.) with estimated cost.

### `analyze entity <entity_id>`
Combat/difficulty analysis of a mob.
- **`entity_id`** — entity resource location, e.g. `minecraft:warden`. Tab-completes.
- **Output:** base stats (health/attack/armor with bars), combat analysis (survivability, threat, combat power), a difficulty rating (`Trivial` → `BOSS`) with a recommendation, and notable drops by average yield.

### `analyze loot <loot_table_id>`
Inspect any loot table.
- **`loot_table_id`** — loot-table resource location, e.g. `minecraft:chests/end_city_treasure`. Tab-completes.
- **Output:** table name and inferred type (Chest, Entity, Fishing, Block, Archaeology…), drop-chance statistics, and all drops grouped by rarity (`Common` → `Legendary`).

---

## ⛏ `/complexity resource <item_id>` 👤

Shows the primary, most efficient way to obtain a base (non-craftable) resource.
- **`item_id`** — the base resource. Tab-completes.
- **Output:** source type with icon (e.g. Ore/Mining, Mob Drop, Farming), a "base factor" difficulty score with a bar and rating, any required source items, and a clickable link to the full analysis.

---

## 🌐 `/complexity web …`

Controls the in-game web dashboard (served over your open-to-LAN / server port).

- **`web url`** 👤 — prints the dashboard URL formatted as an interactive `[Open in Browser]` button. In singleplayer it
  reminds you to *Open to LAN* first if not running.
- **`web url link`** 👤 — prints the clickable raw IP/domain URL string directly in the chat instead of localized button
  text.
- **`web status`** 👤 — backend status: active/inactive, visitor count, data snapshot age, and item/mob/recipe counts in
  the exported file.
- **`web reload`** 🛡 — rebuilds the web data snapshot in the background from the current engine state.

---

## ⚙️ `/complexity system …`

Engine and server diagnostics.

- **`system status`** 👤 — engine state (`READY`, `ANALYZING`, `FAILED`, `IDLE`) and data overview (items with recipes,
  total recipes, base resources).
- **`system stats`** 👤 — detailed statistics: analyzed items count, total recipes count, and registered base resources.
- **`system tps`** 👤 — server performance: TPS, MSPT, and JVM heap memory usage with colour-coded bars.
- **`system threads`** 🛡 — thread-pool internals: parallelism target, active compute threads, and queued background
  tasks.
- **`system reload`** 🛡 — forces a full background re-analysis of the world. **Warning: causes significant lag** while
  it runs; broadcasts a warning to players.
- **`system cache info`** 👤 — reports on-disk analysis caches: whether caching is enabled in config, and for each cache
  whether it is present, its size and age, or "not built yet".
- **`system cache clear [<cache_id>]`** 🛡 — deletes all cached files or a specific cache by ID (e.g. `recipe_graph`,
  `machine_registry`, `block_break`, `universal_loot`, `farming`, `mob_drop`). Hint: run `/complexity system reload`
  afterwards to rebuild immediately.

> **About the cache:** when `enableCache` is on (config), the harvested recipe graph
> and machine registry are saved per-world under `<world>/data/complexityanalyzer/`.
> They auto-invalidate when recipes, blocks, mods or graph-affecting config change, so
> manual `cache clear` is rarely needed.

---

## 📤 `/complexity export …` 🛡

Bulk-export analysis data to files for use outside Minecraft. **The entire group requires OP (level 2).**

All export tasks run asynchronously in background compute threads and write to the server's working directory.

### Items

Format is **mandatory** as the first argument after `items` (e.g. `json`, `csv` — tab-completes based on registered
exporters).

| Command                                          | Description                                                            |
|--------------------------------------------------|------------------------------------------------------------------------|
| `export items <format> all`                      | Full report of every analyzed item.                                    |
| `export items <format> category <category_name>` | All items of one complexity category (e.g. `Mythical`). Tab-completes. |
| `export items <format> top <count>`              | Top N most complex items (`count` between 1 and 1000).                 |
| `export items <format> single <item_id>`         | Detailed report for a single item. Tab-completes.                      |

### Mobs

Format is **mandatory** as the first argument after `mobs` (e.g. `json`, `csv` — tab-completes based on registered
exporters).

| Command                                         | Description                                                          |
|-------------------------------------------------|----------------------------------------------------------------------|
| `export mobs <format> all`                      | Full report of all recognized entities/mobs.                         |
| `export mobs <format> category <category_name>` | All mobs of a vanilla `MobCategory` (e.g. `monster`). Tab-completes. |
| `export mobs <format> top <count>`              | Top N most dangerous mobs (`count` between 1 and 1000).              |
| `export mobs <format> single <mob_id>`          | Detailed report for a single mob. Tab-completes.                     |

Each export announces its completion in admin logs and prints the saved file path directly to the sender.

---

## 🌍 `/complexity geoscan …`

Control panel for the world-scanning engine (`GeoAnalysisManager`), used to learn where
resources actually generate.

- **`geoscan start`** 🛡 — running `start` with no arguments prints the profile selection menu with MSPT limits and
  clickable start links.
- **`geoscan start <profile> [chunks] [force]`** 🛡
  - **`profile`** — scan profile name: `normal`, `fast`, `aggressive`, `unlimited`. Tab-completes.
  - **`chunks`** *(optional, default 32)* — pristine chunks to find per biome (1 to 2147483647).
  - **`force`** *(optional, default `false`)* — `true` skips the countdown and starts immediately (broadcasts severe lag
    warning for heavy profiles).
- **`geoscan stop`** 🛡 — gracefully stops the running or scheduled scan; progress is saved.
- **`geoscan status`** 👤 — current scanner state and progress (speed, chunks scanned, MSPT throttle indicator, and an
  interactive hover tooltip with per-biome progress).
- **`geoscan clear`** 🛡 — **destructive.** Deletes all collected geo-data from the database. Cannot run while a scan is
  active (`stop` first).

---

### Notes

- Tab-completion is available for every item, entity, loot-table, cache id, and export format argument.
- Anything that can lag the server, write files, or delete data is OP-gated (🛡); inspection commands are available to
  all players (👤).