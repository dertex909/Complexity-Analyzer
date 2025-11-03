package org.complexityanalyzer;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.event.DatapackSyncHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(ComplexityAnalyzer.MODID)
public class ComplexityAnalyzer {
    public static final String MODID = "complexityanalyzer";
    public static final String MOD_NAME = "Complexity Analyzer";
    public static final String VERSION = "0.1.0-alpha";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    public ComplexityAnalyzer(IEventBus ignoredModEventBus, ModContainer modContainer) {
        LOGGER.info("=== {} v{} ===", MOD_NAME, VERSION);
        modContainer.registerConfig(ModConfig.Type.COMMON, ComplexityConfig.SPEC);

        NeoForge.EVENT_BUS.register(DatapackSyncHandler.class);

        LOGGER.info("{} initialized successfully.", MOD_NAME);
    }
}