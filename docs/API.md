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

The primary entry point is `ComplexityAnalyzerAPI`. It becomes available once the server starts and remains valid for
the lifetime of the server. Current `API_VERSION` is `"1.0.0"`.

```java
import org.complexityanalyzer.api.ComplexityAnalyzerAPI;
import java.util.Optional;

// Check if the mod is present and installed
if (ComplexityAnalyzerAPI.isAvailable()) {
    ComplexityAnalyzerAPI api = ComplexityAnalyzerAPI.get();

    // Or safely acquire an Optional if availability is uncertain
    Optional<ComplexityAnalyzerAPI> optionalApi = ComplexityAnalyzerAPI.getOptional();

    // Check if the initial analysis pass has completed
    if (api.isReady()) {
        double diamondCost = api.items().getComplexity(Items.DIAMOND);
    }
}
```

> 💡 **Lifecycle Note:** Always check `api.isReady()` before querying calculated values. Prior to readiness, read queries return safe default values (e.g. `-1.0` or empty Optionals).

---

## 📖 3. Querying Data (Read Views)

The API exposes read-only sub-views for inspecting computed game data:

### 3.1. Item Complexity Query (`api.items()`)

Read computed complexity scores and difficulty categories.

```java
ComplexityQuery items = api.items();

// 1. Get raw numeric complexity score (-1.0 if uncalculable, Infinity if unobtainable)
double netheriteCost = items.getComplexity(Items.NETHERITE_INGOT);

// 2. Convenience overload for ItemStacks
double stackCost = items.getComplexity(player.getMainHandItem());

// 3. Check if the item has been analyzed
boolean analyzed = items.isAnalyzed(Items.NETHERITE_INGOT);

// 4. Get bucketed difficulty tier:
// ABSOLUTE (0), TRIVIAL (10), SIMPLE (100), MODERATE (1K), COMPLEX (10K),
// DIFFICULT (100K), EXPERT (1M), MASTER (10M), MYTHICAL (100M), TRANSCENDENT (1B),
// CELESTIAL (10B), ASTRAL (100B), ETERNAL (1T), PRIMORDIAL (10T), SINGULARITY (100T),
// INCONCEIVABLE (1Qa), BOUNDLESS (10Qa), UNOBTAINABLE (Infinity), UNCALCULABLE (-1)
ComplexityCategory category = items.getCategory(Items.NETHERITE_INGOT);

// 5. Get full detailed snapshot
Optional<ItemComplexity> detailed = items.getDetailed(Items.NETHERITE_INGOT);
detailed.ifPresent(info -> {
    int craftingDepth = info.getDepth();               // Crafting tree depth (0 for base items)
    int totalIngredients = info.getTotalIngredients(); // Total raw units required across the tree
    boolean hasRecipe = info.hasRecipe();              // Whether produced by at least one recipe
    boolean valid = info.isValid();                    // True if non-negative, finite, without error
    String error = info.getErrorMessage();             // Evaluation error message (if any)
    RecipeNode bestRecipe = info.getOptimalRecipe();   // Solver-selected cheapest recipe node
});

// 6. Query analyzed collection
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

// Get raw non-crafted acquisition sources (mining, loot, farming, mob drops, etc.)
List<BaseResourceData> baseSources = recipes.getBaseSources(Items.RAW_IRON);
Optional<BaseResourceData> cheapestSource = recipes.getBestBaseSource(Items.RAW_IRON);

// Total recipes in the loaded graph
int totalRecipes = recipes.totalRecipeCount();
```

---

### 3.3. Mob Combat & Rarity Model (`api.mobs()`)

Access spawn rarity and combat difficulty models computed from real attribute suppliers.

```java
MobData mobs = api.mobs();

// Get spawn-rarity multiplier (1.0 = common, higher = rarer, Infinity = never spawns naturally)
double rarity = mobs.getRarity(EntityType.ENDERMAN);

// Get derived kill-difficulty score:
// combatPower = (maxHealth * (1.0 + armor * 0.05)) * (1.0 + ln(1.0 + attackDamage))
double combatPower = mobs.getCombatPower(EntityType.WARDEN);

// Check entity classifications
boolean isBoss = mobs.isBoss(EntityType.WITHER);
boolean isMiniBoss = mobs.isMiniBoss(EntityType.ELDER_GUARDIAN);
boolean isRenewable = mobs.isRenewable(EntityType.COW);

// Get complete immutable profile
Optional<MobInfo> info = mobs.getInfo(EntityType.RAVAGER);
info.ifPresent(mob -> {
    EntityType<?> type = mob.type();
    double health = mob.maxHealth();
    double damage = mob.attackDamage();
    double armor = mob.armor();
    double power = mob.combatPower();
    double mobRarity = mob.rarity();
    MobCategory category = mob.category();
    boolean boss = mob.boss();
    boolean miniBoss = mob.miniBoss();
    boolean renewable = mob.renewable();
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

// Get scanned biomes for a dimension
Set<ResourceLocation> biomes = geo.getScannedBiomes(ResourceLocation.parse("minecraft:overworld"));

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

// Get all registered machine items capable of executing a RecipeType
List<Item> allSmelters = machines.getMachinesForRecipe(RecipeType.SMELTING);
```

---

## ⚡ 4. Contributing Data & Subscribing to Events

Mod integrations are driven by two NeoForge bus events fired on `NeoForge.EVENT_BUS`.

### 4.1. `ComplexityRegistrationEvent`

Fired on `NeoForge.EVENT_BUS` **before** every analysis build (at server start and on every
`/complexity system reload`). Use this event to register custom bosses, renewable mobs, hardcoded overrides, or custom
resource sources.

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
        // Can also register by registry string ID:
        // event.bosses().registerBoss("mymod:custom_boss", IBossRegistry.BossType.BOSS);

        // 2. Mark farmable / breedable mobs as renewable (applies renewable drop discount)
        event.renewables().markRenewable(MyEntities.MANA_SLIME.get());

        // 3. Register hardcoded acquisition overrides or transformation sources
        BaseResourceData gemTransformation = new BaseResourceData.Builder(MyItems.REFINED_GEM.get())
                .sourceType(BaseResourceData.ResourceSourceType.SPECIAL_ACTION)
                .baseFactor(5.0)
                .addSourceItem(MyItems.RAW_GEM.get(), 1.0)
                .details("Refining raw gem with custom ritual")
                .build();
        event.hardcodedSources().register(MyItems.REFINED_GEM.get(), gemTransformation);

        // Mark creative-only / debug items as unobtainable (infinite score)
        BaseResourceData unobtainableData = new BaseResourceData.Builder(MyItems.DEBUG_WAND.get())
                .sourceType(BaseResourceData.ResourceSourceType.UNOBTAINABLE)
                .baseFactor(Double.POSITIVE_INFINITY)
                .details("Creative mode only")
                .build();
        event.hardcodedSources().register(MyItems.DEBUG_WAND.get(), unobtainableData);

        // Or register a deferred world-aware scanner (executed during analysis with active Level)
        event.hardcodedSources().register((level, registry) -> {
            // Inspect level, blocks, or recipes and register dynamic sources
        });

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
    public boolean requiresServerThread() {
        return false; // Return true if initialize() requires main server thread (e.g. world block lookups)
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