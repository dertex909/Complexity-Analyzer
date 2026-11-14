/*
 * Complexity Analyzer
 * Copyright (C) 2025-2026 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.util;

import org.slf4j.Marker;

import static org.slf4j.MarkerFactory.getMarker;

public final class LogMarkers {
    public static final Marker COMPLEXITY_CALCULATOR = getMarker("ComplexityCalculator");
    public static final Marker SCC_DAG_SOLVER = getMarker("SccCondensedSolver");
    public static final Marker COMPLEXITY_CACHE = getMarker("ComplexityCache");
    public static final Marker MACHINE_REGISTRY_CACHE = getMarker("MachineRegistryCache");
    public static final Marker RECIPE_GRAPH_CACHE = getMarker("RecipeGraphCache");
    public static final Marker RESOURCE_CACHE = getMarker("ResourceCache");
    public static final Marker CACHE_FINGERPRINTS = getMarker("CacheFingerprints");
    public static final Marker ANALYZE_COMMAND = getMarker("AnalyzeCommand");
    public static final Marker EXPORT_COMMAND = getMarker("ExportCommand");
    public static final Marker RESOURCE_COMMAND = getMarker("ResourceCommand");
    public static final Marker ANALYSIS_ENGINE = getMarker("AnalysisEngine");
    public static final Marker GAME_REGISTRY_MANAGER = getMarker("GameRegistryManager");
    public static final Marker THREAD_POOL_MANAGER = getMarker("ThreadPoolManager");
    public static final Marker ANALYSIS_BOOTSTRAP = getMarker("AnalysisBootstrap");
    public static final Marker COMMAND_REGISTRATION = getMarker("CommandRegistration");
    public static final Marker WEB_LIFECYCLE = getMarker("WebLifecycle");
    public static final Marker CABIN_BUILDER = getMarker("CabinBuilder");
    public static final Marker CABIN_DATA_SERVICE = getMarker("CabinDataService");
    public static final Marker GEO_HEURISTIC_ANALYZER = getMarker("GeoHeuristicAnalyzer");
    public static final Marker GEO_ANALYSIS_MANAGER = getMarker("GeoAnalysisManager");
    public static final Marker GEO_DATABASE = getMarker("GeoDatabase");
    public static final Marker GEO_DATA_REFINER = getMarker("GeoDataRefiner");
    public static final Marker GEO_SCAN_EMERGENCY = getMarker("GeoScanEmergency");
    public static final Marker GEO_SCAN_COORDINATOR = getMarker("GeoScanCoordinator");
    public static final Marker GEO_SCAN_EXECUTOR = getMarker("GeoScanExecutor");
    public static final Marker GEO_DATA_STORAGE = getMarker("GeoDataStorage");
    public static final Marker GEO_WORLD_SCANNER = getMarker("GeoWorldScanner");
    public static final Marker RECIPE_GRAPH = getMarker("RecipeGraph");
    public static final Marker RECIPE_GRAPH_LOADER = getMarker("RecipeGraphLoader");
    public static final Marker HARVEST_DEBUG_TRACE = getMarker("HarvestDebugTrace");
    public static final Marker MACHINE_REGISTRY_DEBUG = getMarker("MachineRegistryDebug");
    public static final Marker DYNAMIC_RECIPE_HARVESTER = getMarker("DynamicRecipeHarvester");
    public static final Marker REGISTRY_HARVEST_SERVICE = getMarker("RegistryHarvestService");
    public static final Marker MACHINE_REGISTRY = getMarker("MachineRegistry");
    public static final Marker WEB_VIEWER_WATCHER = getMarker("WebViewerWatcher");
    public static final Marker WEB_PACK_SCANNER = getMarker("WebPackScanner");
    public static final Marker WEB_TAB_MANIFEST = getMarker("WebTabManifest");
    public static final Marker WEB_SERVER = getMarker("WebServer");
    public static final Marker MOB_PROPERTY_PROVIDER = getMarker("MobPropertyProvider");
    public static final Marker PLANT_SIMULATOR = getMarker("PlantSimulator");
    public static final Marker RESOURCE_SOURCE_MANAGER = getMarker("ResourceSourceManager");
    public static final Marker BLOCK_DROP_SOURCE = getMarker("BlockDropSource");
    public static final Marker FARMING_SOURCE = getMarker("FarmingSource");
    public static final Marker HARDCODED_RESOURCE_SOURCE = getMarker("HardcodedResourceSource");
    public static final Marker MOB_DROP_SOURCE = getMarker("MobDropSource");
    public static final Marker UNIVERSAL_LOOT_SOURCE = getMarker("UniversalLootSource");
    public static final Marker VILLAGER_TRADE_SOURCE = getMarker("VillagerTradeSource");
    public static final Marker FILE_MANAGER = getMarker("FileManager");
    public static final Marker SERVER_LANGUAGE = getMarker("ServerLanguage");

    private LogMarkers() {
    }
}
