# Complexity Analyzer - Command Reference

Welcome to the command reference for Complexity Analyzer. This document provides a detailed overview of all available commands, their arguments, and expected output.

All commands start with `/complexity`. Most analytical commands can be used by any player, while administrative commands require operator permissions (default: Level 2).

---

## 🔬 Analysis Commands (`/complexity analyze ...`)

This group of commands is the core of the mod, allowing you to perform a deep-dive analysis on various game elements.

### Analyze an Item
- **Command:** `/complexity analyze item <item_id>`
- **Description:** Generates a comprehensive report for a specific item. This is the main command for understanding an item's economic standing in your world.
- **Arguments:**
    - `item_id`: The resource location of the item (e.g., `minecraft:netherite_ingot`). Supports tab-completion.
- **Output:** A detailed, multi-section report including:
    - **Complexity Score & Category:** The final calculated "cost" and its human-readable rank (e.g., `Simple`, `Complex`, `Mythical`) with icons and a visual bar.
    - **Source Information:** Shows if the item is crafted or a base resource, its crafting depth, and how many other recipes use it.
    - **Alternative Sources:** Lists all other ways to obtain the item (mob drops, loot, etc.) with their estimated costs.
    - **Status:** Shows if the calculation is valid and provides a clickable link to view the full crafting tree.

### Analyze an Entity (Mob)
- **Command:** `/complexity analyze entity <entity_id>`
- **Description:** Provides a detailed combat analysis of any mob, evaluating its stats, threat level, and overall difficulty.
- **Arguments:**
    - `entity_id`: The resource location of the entity (e.g., `minecraft:warden`). Supports tab-completion.
- **Output:** A full combat profile including:
    - **Base Stats:** Health, Attack Damage, and Armor, with visual progress bars.
    - **Combat Analysis:** Calculated factors like `Survivability`, `Threat Level`, and a final `Combat Power` score.
    - **Difficulty Rating:** A final, human-readable rating (from `Trivial` to `BOSS`) with a gameplay recommendation.
    - **Notable Drops:** A list of significant items dropped by the mob, sorted by their average yield.

### Analyze a Loot Table
- **Command:** `/complexity analyze loot <loot_table_id>`
- **Description:** Inspects the contents of any loot table, showing all possible drops, their chances, and grouping them by rarity.
- **Arguments:**
    - `loot_table_id`: The resource location of the loot table (e.g., `minecraft:chests/end_city_treasure`). Supports tab-completion.
- **Output:** A report on the loot table's contents, including:
    - **Header & Statistics:** The table's name, inferred type (Chest, Entity, etc.), and a summary of drop chances.
    - **Drops by Rarity:** All items are grouped into intuitive categories (`Common`, `Uncommon`, `Rare`, `Legendary`) for easy evaluation.

---

## 🌳 Crafting Tree Command (`/complexity tree ...`)

This is one of the most powerful visualization tools in the mod.

- **Command:** `/complexity tree <item_id> [mode] [depth]`
- **Description:** Renders a full, recursive crafting tree for an item right in your chat.
- **Arguments:**
    - `item_id`: The target item to build the tree for.
    - `mode` (Optional, default: `player`):
        - `player`: **"Shopping List" view.** Shows rounded-up quantities needed for gameplay.
        - `economic`: **"Engineer's View."** Shows precise, fractional amounts for deep analysis.
    - `depth` (Optional, default: 100): The maximum depth of the tree to display.
- **Output:** A beautifully formatted, multi-part report:
    1.  **Crafting Tree:** A visual tree with icons (🔨 for crafting, ⛏ for base resources, 🔄 for cycles).
    2.  **Tree Statistics:** A summary of total nodes, unique items, crafting steps, and detected cycles.
    3.  **Base Resources List:** A final "shopping list" of all raw materials required, including a visualization of how many stacks you'll need.
    4.  **Interactive Tips:** Provides clickable links in chat to switch modes or limit depth.

---

## ⛏ Base Resource Command (`/complexity resource ...`)

Use this command to analyze how to obtain raw, non-craftable items.

- **Command:** `/complexity resource <item_id>`
- **Description:** Shows the primary, most efficient way to obtain a "base resource" (e.g., ores, mob drops).
- **Arguments:**
    - `item_id`: The base resource to analyze.
- **Output:** A focused report on the item's origin:
    - **Source Information:** The type of source (e.g., `MINING`, `MOB_DROP`) with a descriptive icon.
    - **Base Factor:** A score representing the "difficulty" of obtaining this resource, with a visual bar and a rating.
    - **Required Items:** If obtaining this resource requires other items, they will be listed.

---

## 📤 Export Commands (`/complexity export ...`)

This suite of commands is for power users who want to work with the data outside of Minecraft. Requires operator permissions.

### Exporting Items
- **`/complexity export items all`**: Exports a full JSON report of every analyzed item.
- **`/complexity export items top <count>`**: Exports the top N most complex items.
- **`/complexity export items category <name>`**: Exports all items of a specific complexity category (e.g., `Mythical`).
- **`/complexity export items single <item_id>`**: Exports a detailed JSON for one specific item.
- **`/complexity export items csv`**: Exports a summary of all items into a single CSV file.

### Exporting Mobs
- **`/complexity export mobs all [format]`**: Exports all analyzed mobs in `json` (default) or `csv`.
- **`/complexity export mobs top <count>`**: Exports the top N most powerful mobs.
- **`/complexity export mobs category <name>`**: Exports all mobs of a specific category (e.g., `monster`).
- **`/complexity export mobs single <mob_id>`**: Exports a detailed JSON for one specific mob.

---

## 🌍 Geo-Scan Commands (`/complexity geoscan ...`)

This is the control panel for the `GeoAnalysisManager`, the powerful world-scanning engine. Requires operator permissions.

- **`/complexity geoscan start [chunks] [profile] [force]`**: Schedules or force-starts a world scan.
    - `chunks` (Optional, default: 32): How many pristine chunks to find per biome.
    - `profile` (Optional, default: `lite`): Scan speed vs. server impact. Options: `lite`, `fast`, `extreme`, `atomic`.
    - `force` (Optional, default: `false`): Set to `true` to skip the countdown and start immediately.
- **`/complexity geoscan stop`**: Requests a graceful shutdown of the current scan.
- **`/complexity geoscan status`**: Shows the current status of the geo-scanner (e.g., IDLE, SCANNING, progress %).
- **`/complexity geoscan clear`**: **DANGEROUS.** Deletes all collected geo-data, forcing a full rescan on the next run. Cannot be used during an active scan.

---

## ⚙️ Administrative Commands

General-purpose commands for server management. Require operator permissions.

- **`/complexity status`**: Shows the current state of the main analysis engine.
- **`/complexity stats`**: Displays detailed statistics about the loaded data (number of items, recipes, etc.).
- **`/complexity tps`**: An advanced server performance overview (TPS, MSPT, Memory).
- **`/complexity reload`**: Forces a full reload of the analysis engine. **Warning: This will cause significant server lag.**