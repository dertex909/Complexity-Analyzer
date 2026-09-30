A massive update featuring dozens of new resource sources, an upgraded web dashboard, and serious performance
optimizations so your server doesn't choke during calculations.

---

### 🔥 What's New for Players & Modpack Creators

* **Tons of New Resource Sources**:
  The mod now recognizes way more vanilla mechanics and accurately factors them into item complexity:
    * **Passive Mobs & Farming**: Milking cows and mooshrooms, shearing sheep, chicken/turtle/sniffer eggs, armadillo
      scutes, goat horns, honey harvesting, and froglights.
    * **Unique Drops**: Morning gifts from tamed cats, sneezing pandas (slimeballs), sniffer digging, and Hero of the
      Village raid rewards.
    * **Trial Chambers**: Trial spawners, breaking decorated pots, ominous keys, and vaults.
    * **World Interactions**: Concrete powder hardening in water, coral drying out, copper oxidation over time,
      stripping wood with axes, tilling soil with hoes, and anvil degradation.
    * **Farming & Flora**: Duplicating tall flowers with bonemeal, spreading grass/flowers across biomes, and azalea
      growth on moss blocks.
    * **Special Kill Conditions**: The engine now understands when an item only drops under specific circumstances
      (charged creeper explosions, skeleton-shot music discs) and calculates the cost accordingly.

* **Upgraded Web Dashboard**:
    * **Recipe Search**: Instantly find any recipe by item name, ID, or machine directly from the web interface.
    * **Virtual Scrolling**: Recipe tabs with thousands of entries now scroll smoothly without lagging or freezing your
      browser.
    * **Mobile Support**: The UI is now properly adapted for phones and tablets without overlapping system navigation
      bars.
    * **[Experimental] Addon Support (Web Packs)**: Laid the groundwork for dashboard extensibility, allowing other mods
      and modpacks to inject custom tabs. This system is currently in early testing — full documentation and
      easy-to-follow guides on how to make your own tabs will drop in upcoming updates.

* **Lag-Free Exporting**:
    * `/complexity export ...` commands now run completely in the background — no more server freezing or tick drops
      while saving massive JSON or CSV files.

---

### ⚡ Performance & Stability

* **Faster Biome Scanning (`/complexity geoscan`)**: Overhauled the biome search algorithm across both underground and
  high-altitude areas. It finds targets faster, more accurately, and with less server impact.
* **Faster Startup**: Stripped out legacy heavy code-analysis routines. Recipe calculation boots up faster and uses
  significantly less RAM.
* **Crash Fixes**: Resolved edge-case crashes where third-party mods could bring down the server during background chunk
  analysis.
* **Localization Updates**: Fixed translation formatting and command tooltips across supported languages.