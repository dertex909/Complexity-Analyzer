## 📦 1. Adding Dependency

To use the Complexity Analyzer API in your mod project, add the Modrinth Maven repository and dependency coordinates to
your Gradle setup.

### 1.1. Add Repository (`build.gradle` or `settings.gradle`)

In your `build.gradle` `repositories` block (recommended approach using Gradle's `exclusiveContent` for faster
resolution):

```groovy
repositories {
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = "https://api.modrinth.com/maven"
            }
        }
        filter {
            includeGroup "maven.modrinth"
        }
    }
}
```

*(Or in `settings.gradle` under `dependencyResolutionManagement`):*

```groovy
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "Modrinth"
                    url = "https://api.modrinth.com/maven"
                }
            }
            filter {
                includeGroup "maven.modrinth"
            }
        }
    }
}
```

### 1.2. Add Dependency (`build.gradle`)

Add the dependency using the `maven.modrinth` group ID, your project's slug, and the target version:

```groovy
dependencies {
    // Compile against the Complexity Analyzer API via Modrinth Maven
    compileOnly "maven.modrinth:complexity-analyzer:0.7.0-alpha-1.21.1"

    // Or include at runtime
    implementation "maven.modrinth:complexity-analyzer:0.7.0-alpha-1.21.1"
}
```

> 💡 **Note:** Replace `complexity-analyzer` with your exact project slug on Modrinth, and `0.7.0-alpha-1.21.1` with the
> exact version number published on Modrinth.
### 1.3. Declare Mod Dependency (`neoforge.mods.toml`)

Ensure your `neoforge.mods.toml` declares the dependency for correct NeoForge load ordering:

```toml
[[dependencies.your_mod_id]]
    modId = "complexityanalyzer"
    type = "optional" # or "required"
    versionRange = "[0.7.0-alpha-1.21.1,)"
    ordering = "AFTER"
    side = "BOTH"
```

---

## 🔑 2. Accessing the API

The primary entry point is `ComplexityAnalyzerAPI`. It becomes available once the server starts and remains valid for the lifetime of the server.

```java
import org.complexityanalyzer.api.ComplexityAnalyzerAPI;
import java.util.Optional;

// Check if the mod is present and installed
if (ComplexityAnalyzerAPI.isAvailable()) {
    ComplexityAnalyzerAPI api = ComplexityAnalyzerAPI.get();

    // Check if the initial analysis pass has completed
    if (api.isReady()) {
        double diamondCost = api.items().getComplexity(Items.DIAMOND);
    }
}
```

> 💡 **Lifecycle Note:** Always check `api.isReady()` before querying calculated values. Prior to readiness, read queries return safe default values (e.g. `-1.0` or empty Optionals).

---

## 📖 3. Querying Data (Read Views)

The API exposes 5 read-only views for inspecting computed game data:

### 3.1. Item Complexity Query (`api.items()`)

Read computed complexity scores and difficulty categories.

```java
ComplexityQuery items = api.items();

// 1. Get raw numeric complexity score (-1.0 if uncalculable, Infinity if unobtainable)
double netheriteCost = items.getComplexity(Items.NETHERITE_INGOT);

// 2. Convenience overload for ItemStacks
double stackCost = items.getComplexity(player.getMainHandItem());

// 3. Get bucketed difficulty tier (TRIVIAL, SIMPLE, MODERATE, COMPLEX, DIFFICULT, EXPERT, MASTER, MYTHICAL, TRANSCENDENT, UNOBTAINABLE, UNCALCULABLE)
ComplexityCategory category = items.getCategory(Items.NETHERITE_INGOT);

// 4. Get full detailed snapshot
Optional<ItemComplexity> detailed = items.getDetailed(Items.NETHERITE_INGOT);
detailed.ifPresent(info -> {
    int craftingDepth = info.getDepth();               // Crafting tree depth
    int totalIngredients = info.getTotalIngredients(); // Total raw units required
    boolean hasRecipe = info.hasRecipe();              // Whether produced by a recipe
    RecipeNode bestRecipe = info.getOptimalRecipe();   // Solver-selected cheapest recipe
});

// 5. Query analyzed collection
Collection<Item> allAnalyzed = items.getAnalyzedItems();
int totalCount = items.count();
```

---

### 3.2. Recipe Graph & Raw Sources (`api.recipes()`)

Inspect the harvested recipe graph and raw acquisition sources.

```java
RecipeData recipes = api.recipes();

// Check if an item has crafting recipes
boolean craftable = recipes.hasRecipe(Items.PISTON);

// Get all recipes producing an item
List<RecipeNode> allPistonRecipes = recipes.getRecipes(Items.PISTON);

// Get the solver-selected optimal recipe for an item
Optional<RecipeNode> bestRecipe = recipes.getBestRecipe(Items.PISTON);

// Reverse lookups: find what recipes use an item as an ingredient
int usageCount = recipes.getUsageCount(Items.IRON_INGOT);
Set<Item> itemsCraftedWithIron = recipes.getItemsUsing(Items.IRON_INGOT);

// Get raw non-crafted acquisition sources (mining, loot, farming, etc.)
List<BaseResourceData> baseSources = recipes.getBaseSources(Items.RAW_IRON);
Optional<BaseResourceData> cheapestSource = recipes.getBestBaseSource(Items.RAW_IRON);
```

---

### 3.3. Mob Combat & Rarity Model (`api.mobs()`)

Access spawn rarity and combat difficulty models computed from real attribute suppliers.

```java
MobData mobs = api.mobs();

// Get spawn-rarity multiplier (1.0 = common, higher = rarer, Infinity = never spawns naturally)
double rarity = mobs.getRarity(EntityType.ENDERMAN);

// Get kill-difficulty score: sqrt(maxHealth * max(1, attackDamage)) * (1 + armor * 0.05)
double combatPower = mobs.getCombatPower(EntityType.WARDEN);

// Check entity classifications
boolean isBoss = mobs.isBoss(EntityType.WITHER);
boolean isMiniBoss = mobs.isMiniBoss(EntityType.ELDER_GUARDIAN);
boolean isRenewable = mobs.isRenewable(EntityType.COW);

// Get complete immutable profile
Optional<MobInfo> info = mobs.getInfo(EntityType.RAVAGER);
info.ifPresent(mob -> {
    double health = mob.maxHealth();
    double damage = mob.attackDamage();
    double armor = mob.armor();
    MobCategory category = mob.category();
});
```

---

### 3.4. World Geo-Scan Data (`api.geo()`)

Query natural block abundance across scanned biomes and dimensions.

```java
GeoData geo = api.geo();

if (geo.isScanned()) {
    // Get scanned dimensions
    Set<ResourceLocation> dims = geo.getScannedDimensions(); // e.g. "minecraft:overworld"

    // Get block share fraction (0.0 to 1.0) in a specific biome
    OptionalDouble diamondShare = geo.getBlockShare(
        ResourceLocation.parse("minecraft:overworld"),
        ResourceLocation.parse("minecraft:deep_dark"),
        Blocks.DIAMOND_ORE
    );

    // Get rarest-biome share (highest occurrence rate found anywhere in the world)
    OptionalDouble bestShare = geo.getBestBlockShare(Blocks.DIAMOND_ORE);
}
```

---

### 3.5. Machine Registry (`api.machines()`)

Query machine-to-recipe-type associations used for machine taxes.

```java
MachineData machines = api.machines();

// Get representative machine item for a RecipeType
Optional<Item> furnace = machines.getMachineForRecipe(RecipeType.SMELTING);

// Get all registered machine items for a RecipeType
List<Item> allSmelters = machines.getMachinesForRecipe(RecipeType.SMELTING);
```

---

## ⚡ 4. Contributing Data & Subscribing to Events

Mod integrations are driven by two NeoForge bus events fired on `NeoForge.EVENT_BUS`.

### 4.1. `ComplexityRegistrationEvent`

Fired **before** every analysis build (server start and server reload). Use this event to register custom bosses, renewable mobs, hardcoded overrides, or custom resource sources.

> ⚠️ **Important:** Registrations are rebuilt from scratch on each build pass. Always register inside the event handler rather than caching state across builds.

```java
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.complexityanalyzer.api.event.ComplexityRegistrationEvent;
import org.complexityanalyzer.api.IBossRegistry;
import org.complexityanalyzer.resource.data.BaseResourceData;

@EventBusSubscriber(modid = "your_mod_id")
public class ComplexityIntegration {

    @SubscribeEvent
    public static void onRegisterComplexityData(ComplexityRegistrationEvent event) {
        
        // 1. Tag custom mobs as bosses or mini-bosses (affects drop rarity weighting)
        event.bosses().registerBoss(MyEntities.DRAGON_LORD.get(), IBossRegistry.BossType.BOSS);
        event.bosses().registerBoss(MyEntities.MINI_GOLEM.get(), IBossRegistry.BossType.MINI_BOSS);

        // 2. Mark farmable / breedable mobs as renewable (applies renewable drop discount)
        event.renewables().markRenewable(MyEntities.MANA_SLIME.get());

        // 3. Register hardcoded acquisition overrides or custom transformations
        event.hardcodedSources().registerTransformation(
            MyItems.REFINED_GEM.get(),   // Output
            MyItems.RAW_GEM.get(),       // Input
            null,                        // Tool wear map (null if none)
            5.0,                         // Base additional cost
            "Refining raw gem"           // Description
        );

        // Mark creative-only / debug items as unobtainable (infinite score)
        event.hardcodedSources().registerUnobtainable(
            MyItems.DEBUG_WAND.get(),
            "Creative mode only"
        );

        // 4. Register a fully custom resource source provider
        event.resourceSources().register(new CustomGatheringSource());
    }
}
```

---

### 4.2. Implementing a Custom `IResourceSource`

To define custom raw acquisition mechanics (e.g. quest rewards, custom mining machines, specialized gathering professions), implement `IResourceSource`:

```java
import org.complexityanalyzer.resource.IResourceSource;
import org.complexityanalyzer.resource.data.BaseResourceData;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public class CustomGatheringSource implements IResourceSource {

    @Override
    public void initialize(Level level) {
        // One-time heavy setup during analysis pass (runs asynchronously unless requiresServerThread() is true)
    }

    @Override
    public boolean canProvide(Item item) {
        return item == MyItems.MAGIC_DUST.get();
    }

    @Override
    @Nullable
    public BaseResourceData analyze(Item item) {
        if (item != MyItems.MAGIC_DUST.get()) return null;

        return new BaseResourceData.Builder(item, this)
            .sourceType(BaseResourceData.ResourceSourceType.SPECIAL_ACTION)
            .baseFactor(15.0) // Base acquisition cost
            .sourceSpecifier("Magic Gathering")
            .details("Gathered via custom magic ritual")
            .build();
    }

    @Override
    public BaseResourceData.ResourceSourceType getSourceType() {
        return BaseResourceData.ResourceSourceType.SPECIAL_ACTION;
    }

    @Override
    public int getPriority() {
        return 50; // Higher priority wins tie-breaks for identical costs
    }

    @Override
    public String getName() {
        return "CustomGatheringSource";
    }
}
```

---

### 4.3. `ComplexityAnalysisCompleteEvent`

Fired on `NeoForge.EVENT_BUS` immediately after an analysis pass reaches the `READY` state and all queries return fresh data.

```java
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.complexityanalyzer.api.event.ComplexityAnalysisCompleteEvent;
import org.complexityanalyzer.api.ComplexityAnalyzerAPI;

@EventBusSubscriber(modid = "your_mod_id")
public class ComplexityEventListener {

    @SubscribeEvent
    public static void onAnalysisComplete(ComplexityAnalysisCompleteEvent event) {
        ComplexityAnalyzerAPI api = event.api();
        boolean isReload = event.isReload(); // true if triggered by /complexity system reload

        // Use this to populate internal caches or refresh dependent systems
        double netheriteCost = api.items().getComplexity(Items.NETHERITE_INGOT);
    }
}
```