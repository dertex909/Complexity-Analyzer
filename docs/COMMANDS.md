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
│   ├── stats                                 👤  detailed data/thread stats
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
│   ├── url [link]                            👤  show web dashboard URL
│   ├── status                                👤  web backend status
│   └── reload                                🛡  rebuild web data
├── export                                    🛡  (whole group is OP-only)
│   ├── items  all | csv | top <n> | category <name> | single <item_id>
│   └── mobs   all [format] | csv | top <n> | category <name> | single <mob_id>
└── geoscan
    ├── start <profile> [chunks] [force]      🛡  schedule/force a world scan
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

- **`web url`** 👤 — prints the dashboard URL and connection tips. In singleplayer it reminds you to *Open to LAN* first.
- **`web url link`** 👤 — same, as a clickable `[Open in Browser]` link.
- **`web status`** 👤 — backend status: active/inactive, visitor count, data file age, and item/mob/recipe counts in the exported file.
- **`web reload`** 🛡 — rebuilds the web data file in the background from the current analysis.

---

## ⚙️ `/complexity system …`

Engine and server diagnostics.

- **`system status`** 👤 — engine state (READY / LOADING / ERROR…) and a data overview (items with recipes, total recipes, base resources).
- **`system stats`** 👤 — detailed statistics: items, recipes, cached items, and thread-pool figures.
- **`system tps`** 👤 — server performance: TPS, MSPT, and memory usage with colour-coded bars.
- **`system threads`** 🛡 — thread-pool internals: parallelism target, active compute threads, queued tasks.
- **`system reload`** 🛡 — forces a full re-analysis of the world. **Warning: causes significant lag** while it runs; broadcasts a warning to players.
- **`system cache info`** 👤 — reports the on-disk analysis caches (recipe graph, machine registry): whether caching is enabled in config, and for each cache whether it is present, its size and age, or "not built yet".
- **`system cache clear [<cache_id>]`** 🛡 — deletes all cached files or a specific cache by ID (e.g., `recipe_graph`, `machine_registry`, `block_break`, `universal_loot`, `farming`, `mob_drop`) so the next load rebuilds from scratch. Hint: run `system reload` afterwards to rebuild immediately.

> **About the cache:** when `enableCache` is on (config), the harvested recipe graph
> and machine registry are saved per-world under `<world>/data/complexityanalyzer/`.
> They auto-invalidate when recipes, blocks, mods or graph-affecting config change, so
> manual `cache clear` is rarely needed.

---

## 📤 `/complexity export …` 🛡

Bulk-export analysis data to files for use outside Minecraft. **The entire group requires OP.**

### Items
| Command                         | Description                                                            |
|---------------------------------|------------------------------------------------------------------------|
| `export items all`              | Full JSON report of every analyzed item.                               |
| `export items csv`              | Summary of all items as a single CSV.                                  |
| `export items top <count>`      | Top N most complex items (`count` 1–1000).                             |
| `export items category <name>`  | All items of one complexity category (e.g. `Mythical`). Tab-completes. |
| `export items single <item_id>` | Detailed JSON for one item. Tab-completes.                             |

### Mobs
| Command                       | Description                                                          |
|-------------------------------|----------------------------------------------------------------------|
| `export mobs all [format]`    | All mobs; `format` is `json` (default) or `csv`.                     |
| `export mobs csv`             | Shortcut for all mobs as CSV.                                        |
| `export mobs top <count>`     | Top N most powerful mobs (`count` 1–1000).                           |
| `export mobs category <name>` | All mobs of a vanilla `MobCategory` (e.g. `monster`). Tab-completes. |
| `export mobs single <mob_id>` | Detailed JSON for one mob. Tab-completes.                            |

Each export prints the output file path on success.

---

## 🌍 `/complexity geoscan …`

Control panel for the world-scanning engine (`GeoAnalysisManager`), used to learn where
resources actually generate.

- **`geoscan start <profile> [chunks] [force]`** 🛡
  - **`profile`** — scan speed vs. server impact: `normal`, `fast`, `aggressive`, `unlimited`. Running `start` with no
    profile lists them with their MSPT limits.
  - **`chunks`** *(optional, default 32)* — pristine chunks to find per biome.
  - **`force`** *(optional, default `false`)* — `true` skips the countdown and starts immediately (broadcasts a lag warning).
- **`geoscan stop`** 🛡 — gracefully stops the running or scheduled scan; progress is saved.
- **`geoscan status`** 👤 — current scanner state and progress (idle / scanning %, per-biome details on hover, MSPT throttle info).
- **`geoscan clear`** 🛡 — **destructive.** Deletes all collected geo-data, forcing a full rescan. Cannot run during an active scan (`stop` first).

---

### Notes
- Tab-completion is available for every item, entity and loot-table id argument.
- Anything that can lag the server or change/delete data is OP-gated (🛡); pure inspection is open to all players (👤).