/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.complexityanalyzer.geoscan.task.ChunkAnalyzer;
import org.complexityanalyzer.geoscan.worldgen.UltraFastChunkGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Temporary validation command for comparing a live chunk against the virtual fast generator.
 */
public final class ScanThisCommand {
    private static final int MAX_VISIBLE_DIFFS = 8;
    private static final int MAX_VISIBLE_FEATURE_TRACES = 6;

    private ScanThisCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("scanthis")
                .requires(source -> source.hasPermission(2))
                .executes(ScanThisCommand::execute)
                .then(Commands.literal("nocache")
                        .executes(ScanThisCommand::executeNoCache))
                .then(Commands.literal("featuretrace")
                        .executes(ScanThisCommand::executeFeatureTrace))
                .then(Commands.literal("phase")
                        .then(Commands.argument("stage", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        new String[]{"terrain", "surface", "carvers", "features", "all"}, builder))
                                .executes(ScanThisCommand::executePhase)));
    }

    public static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return executeCachedFeatures(context);
    }

    private static int executeCachedFeatures(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return runSingleComparison(context, "features/cached", VirtualMode.CACHED_FEATURES);
    }

    private static int executeNoCache(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return runSingleComparison(context, "features/fresh", VirtualMode.FRESH_FEATURES);
    }

    private static int executePhase(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String stageId = StringArgumentType.getString(context, "stage");
        if ("all".equalsIgnoreCase(stageId)) {
            return runAllPhaseComparisons(context);
        }

        UltraFastChunkGenerator.DiagnosticPhase phase = UltraFastChunkGenerator.DiagnosticPhase.fromId(stageId);
        return runSingleComparison(context, phase.id() + "/fresh", VirtualMode.fromPhase(phase));
    }

    private static int executeFeatureTrace(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        ChunkPos chunkPos = player.chunkPosition();

        ChunkAnalyzer analyzer = new ChunkAnalyzer();
        Map<String, Integer> realCounts = extractCounts(analyzer.createSnapshot(level.getChunk(chunkPos.x, chunkPos.z)));
        UltraFastChunkGenerator generator = new UltraFastChunkGenerator(level);
        UltraFastChunkGenerator.FeatureTraceResult traceResult = generator.traceFeatureGeneration(chunkPos);
        Map<String, Integer> virtualCounts = traceResult.chunk() != null
                ? extractCounts(analyzer.createSnapshot(traceResult.chunk()))
                : Collections.emptyMap();

        ReportResult report = buildReport("featuretrace/fresh", realCounts, virtualCounts);
        logReportToConsole(level, chunkPos, report);
        logFeatureTraceToConsole(level, chunkPos, traceResult);
        sendFeatureTraceToChat(output, source, level, chunkPos, report, traceResult);
        return 1;
    }

    private static int runSingleComparison(CommandContext<CommandSourceStack> context,
                                           String label,
                                           VirtualMode mode) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        ChunkPos chunkPos = player.chunkPosition();

        ChunkAnalyzer analyzer = new ChunkAnalyzer();
        Map<String, Integer> realCounts = extractCounts(analyzer.createSnapshot(level.getChunk(chunkPos.x, chunkPos.z)));
        UltraFastChunkGenerator generator = new UltraFastChunkGenerator(level);
        Map<String, Integer> virtualCounts = mode.generate(generator, chunkPos, analyzer);

        ReportResult report = buildReport(label, realCounts, virtualCounts);
        logReportToConsole(level, chunkPos, report);
        sendSingleReportToChat(output, source, level, chunkPos, report);
        return 1;
    }

    private static int runAllPhaseComparisons(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        ChunkPos chunkPos = player.chunkPosition();

        ChunkAnalyzer analyzer = new ChunkAnalyzer();
        Map<String, Integer> realCounts = extractCounts(analyzer.createSnapshot(level.getChunk(chunkPos.x, chunkPos.z)));
        UltraFastChunkGenerator generator = new UltraFastChunkGenerator(level);

        List<ReportResult> reports = new ArrayList<>();
        for (UltraFastChunkGenerator.DiagnosticPhase phase : UltraFastChunkGenerator.DiagnosticPhase.values()) {
            Map<String, Integer> virtualCounts = VirtualMode.fromPhase(phase).generate(generator, chunkPos, analyzer);
            ReportResult report = buildReport(phase.id() + "/fresh", realCounts, virtualCounts);
            reports.add(report);
            logReportToConsole(level, chunkPos, report);
        }

        sendPhaseSummaryToChat(output, source, level, chunkPos, reports);
        return 1;
    }

    private static void sendSingleReportToChat(OutputManager output,
                                               CommandSourceStack source,
                                               ServerLevel level,
                                               ChunkPos chunkPos,
                                               ReportResult report) {
        ComparisonSummary summary = report.summary();

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("═══════════════════════════════════════")
                .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal("🔬 SCANTHIS — CHUNK CHECK")
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        output.sendInfo(source, Component.literal("═══════════════════════════════════════")
                .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal("  Chunk: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal("[" + chunkPos.x + ", " + chunkPos.z + "]")
                        .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Component.literal(" in ")
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(level.dimension().location().toString())
                        .withStyle(ChatFormatting.AQUA)));
        output.sendInfo(source, Component.literal("  Mode: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(report.label())
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));
        output.sendInfo(source, buildCountSummary("📦 Real world", report.realCounts(), ChatFormatting.GREEN));
        output.sendInfo(source, buildCountSummary("🧪 Virtual gen", report.virtualCounts(), ChatFormatting.AQUA));
        output.sendInfo(source, Component.literal("  Δ Compare: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(summary.differences().size() + " differing ids | abs delta " + summary.absoluteDelta())
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(" | real-only " + summary.onlyInReal() + " | virtual-only " + summary.onlyInVirtual())
                        .withStyle(ChatFormatting.DARK_GRAY)));

        if (summary.differences().isEmpty()) {
            output.sendSuccess(source, Component.literal("  ✓ Chunk histograms match exactly.")
                    .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
        } else {
            output.sendInfo(source, Component.literal("  Top diffs:")
                    .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));

            int shown = 0;
            for (DiffEntry diff : summary.differences()) {
                if (shown++ >= MAX_VISIBLE_DIFFS) {
                    break;
                }

                ChatFormatting deltaColor = diff.delta() > 0 ? ChatFormatting.AQUA : ChatFormatting.RED;
                String deltaText = diff.delta() > 0 ? "+" + diff.delta() : Integer.toString(diff.delta());

                output.sendInfo(source, Component.literal("    " + diff.blockId())
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(" | real ")
                                .withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(Integer.toString(diff.realCount()))
                                .withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(" | virtual ")
                                .withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(Integer.toString(diff.virtualCount()))
                                .withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(" | Δ ")
                                .withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(deltaText)
                                .withStyle(deltaColor, ChatFormatting.BOLD)));
            }
        }

        output.sendInfo(source, Component.literal("  Full detailed report written to console/log.")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        output.sendInfo(source, Component.literal("  Note: real chunk includes live world changes; virtual chunk is fast-gen output.")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        output.sendInfo(source, Component.literal("═══════════════════════════════════════")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void sendPhaseSummaryToChat(OutputManager output,
                                               CommandSourceStack source,
                                               ServerLevel level,
                                               ChunkPos chunkPos,
                                               List<ReportResult> reports) {
        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("═══════════════════════════════════════")
                .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal("🔬 SCANTHIS — PHASE CHECK")
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        output.sendInfo(source, Component.literal("═══════════════════════════════════════")
                .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal("  Chunk: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal("[" + chunkPos.x + ", " + chunkPos.z + "]")
                        .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Component.literal(" in ")
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(level.dimension().location().toString())
                        .withStyle(ChatFormatting.AQUA)));

        for (ReportResult report : reports) {
            ComparisonSummary summary = report.summary();
            DiffEntry topDiff = summary.topDifference();

            MutablePhaseLineBuilder line = new MutablePhaseLineBuilder(report.label(), summary);
            if (topDiff != null) {
                line.withTopDiff(topDiff);
            }

            output.sendInfo(source, line.build());
        }

        ReportResult featuresReport = reports.stream()
                .filter(report -> report.label().startsWith("features"))
                .findFirst()
                .orElse(null);
        ReportResult carversReport = reports.stream()
                .filter(report -> report.label().startsWith("carvers"))
                .findFirst()
                .orElse(null);

        output.sendInfo(source, Component.literal(
                "  Note: terrain/surface/carvers are intermediate snapshots, so large deltas there are expected against the final live chunk.")
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));
        output.sendInfo(source, Component.literal(
                "  Use phase check by watching whether the diffs collapse by features/fresh; that line is the meaningful final-fidelity signal.")
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));

        if (featuresReport != null && carversReport != null) {
            int featuresAbs = featuresReport.summary().absoluteDelta();
            int carversAbs = carversReport.summary().absoluteDelta();

            if (featuresAbs <= 128 && featuresAbs * 10 < Math.max(1, carversAbs)) {
                output.sendInfo(source, Component.literal(
                        "  Interpretation: early-phase drift is normal here; the fast generator converges well by the final features stage.")
                        .withStyle(ChatFormatting.GREEN, ChatFormatting.ITALIC));
            } else if (featuresAbs >= carversAbs / 2) {
                output.sendInfo(source, Component.literal(
                        "  Interpretation: because features/fresh stays relatively high, the remaining bug is likely in the final replay stages, not in phase presentation.")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));
            }
        }

        output.sendInfo(source, Component.literal("  Full detailed reports written to console/log.")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        output.sendInfo(source, Component.literal("═══════════════════════════════════════")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void sendFeatureTraceToChat(OutputManager output,
                                               CommandSourceStack source,
                                               ServerLevel level,
                                               ChunkPos chunkPos,
                                               ReportResult report,
                                               UltraFastChunkGenerator.FeatureTraceResult traceResult) {
        ComparisonSummary summary = report.summary();
        List<UltraFastChunkGenerator.FeatureTraceEntry> topEntries = traceResult.entries().stream()
                .filter(entry -> entry.status() == UltraFastChunkGenerator.FeatureTraceStatus.APPLIED)
                .filter(UltraFastChunkGenerator.FeatureTraceEntry::changedCenterChunk)
                .sorted(Comparator
                        .comparingInt(UltraFastChunkGenerator.FeatureTraceEntry::absoluteDelta)
                        .reversed()
                        .thenComparing(UltraFastChunkGenerator.FeatureTraceEntry::featureName))
                .limit(MAX_VISIBLE_FEATURE_TRACES)
                .toList();

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("═══════════════════════════════════════")
                .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal("🔬 SCANTHIS — FEATURE TRACE")
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        output.sendInfo(source, Component.literal("═══════════════════════════════════════")
                .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal("  Chunk: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal("[" + chunkPos.x + ", " + chunkPos.z + "]")
                        .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Component.literal(" in ")
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(level.dimension().location().toString())
                        .withStyle(ChatFormatting.AQUA)));
        output.sendInfo(source, Component.literal("  Features visited: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(Integer.toString(traceResult.entries().size()))
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                .append(Component.literal(" | changed center " + traceResult.changedEntries())
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(" | failed " + traceResult.failedEntries())
                        .withStyle(ChatFormatting.RED))
                .append(Component.literal(" | skipped " + traceResult.skippedEntries())
                        .withStyle(ChatFormatting.DARK_GRAY)));
        output.sendInfo(source, Component.literal("  Final compare: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(summary.differences().size() + " differing ids | abs delta " + summary.absoluteDelta())
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(" | real-only " + summary.onlyInReal() + " | virtual-only " + summary.onlyInVirtual())
                        .withStyle(ChatFormatting.DARK_GRAY)));

        if (topEntries.isEmpty()) {
            output.sendInfo(source, Component.literal("  No feature changed the center chunk histogram.")
                    .withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));
        } else {
            output.sendInfo(source, Component.literal("  Top mutating features:")
                    .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
            for (UltraFastChunkGenerator.FeatureTraceEntry entry : topEntries) {
                String netDeltaText = entry.netDelta() > 0 ? "+" + entry.netDelta() : Integer.toString(entry.netDelta());
                output.sendInfo(source, Component.literal("    [" + entry.originChunkX() + ", " + entry.originChunkZ() + "] step " + entry.step() + " #" + entry.featureIndex() + " ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(entry.featureName())
                                .withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(" | abs " + entry.absoluteDelta())
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                        .append(Component.literal(" | ids " + entry.changedBlockTypes())
                                .withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(" | net " + netDeltaText)
                                .withStyle(entry.netDelta() >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED)));
            }
        }

        output.sendInfo(source, Component.literal("  Full per-feature trace written to console/log.")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        output.sendInfo(source, Component.literal("  Note: trace counts only what each feature changed inside the current chunk.")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        output.sendInfo(source, Component.literal("═══════════════════════════════════════")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static MutableComponent buildCountSummary(String label, Map<String, Integer> counts, ChatFormatting accentColor) {
        return Component.literal("  " + label + ": ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(total(counts) + " blocks | " + counts.size() + " types")
                        .withStyle(accentColor, ChatFormatting.BOLD));
    }

    private static ReportResult buildReport(String label, Map<String, Integer> realCounts, Map<String, Integer> virtualCounts) {
        return new ReportResult(label, realCounts, virtualCounts, compare(realCounts, virtualCounts));
    }

    private static Map<String, Integer> extractCounts(ChunkSnapshot snapshot) {
        return snapshot != null ? snapshot.blockCounts() : Collections.emptyMap();
    }

    private static void logReportToConsole(ServerLevel level, ChunkPos chunkPos, ReportResult report) {
        StringBuilder builder = new StringBuilder();
        String ln = System.lineSeparator();

        builder.append("[SCANTHIS] ==================================================").append(ln);
        builder.append("[SCANTHIS] Chunk [").append(chunkPos.x).append(", ").append(chunkPos.z).append("] in ")
                .append(level.dimension().location()).append(ln);
        builder.append("[SCANTHIS] Mode: ").append(report.label()).append(ln);
        builder.append("[SCANTHIS] Real total=").append(total(report.realCounts()))
                .append(", types=").append(report.realCounts().size()).append(ln);
        builder.append("[SCANTHIS] Virtual total=").append(total(report.virtualCounts()))
                .append(", types=").append(report.virtualCounts().size()).append(ln);
        builder.append("[SCANTHIS] Differing ids=").append(report.summary().differences().size())
                .append(", abs delta=").append(report.summary().absoluteDelta())
                .append(", real-only=").append(report.summary().onlyInReal())
                .append(", virtual-only=").append(report.summary().onlyInVirtual()).append(ln);
        builder.append("[SCANTHIS] --- REAL COUNTS ---").append(ln);
        appendCounts(builder, report.realCounts(), ln);
        builder.append("[SCANTHIS] --- VIRTUAL COUNTS ---").append(ln);
        appendCounts(builder, report.virtualCounts(), ln);
        builder.append("[SCANTHIS] --- DIFFS (virtual - real) ---").append(ln);
        appendDiffs(builder, report.summary(), ln);
        builder.append("[SCANTHIS] ==================================================");

        ComplexityAnalyzer.LOGGER.info(builder.toString());
    }

    private static void logFeatureTraceToConsole(ServerLevel level,
                                                 ChunkPos chunkPos,
                                                 UltraFastChunkGenerator.FeatureTraceResult traceResult) {
        StringBuilder builder = new StringBuilder();
        String ln = System.lineSeparator();

        builder.append("[SCANTHIS] ==================================================").append(ln);
        builder.append("[SCANTHIS] Chunk [").append(chunkPos.x).append(", ").append(chunkPos.z).append("] in ")
                .append(level.dimension().location()).append(ln);
        builder.append("[SCANTHIS] Mode: featuretrace/fresh").append(ln);
        builder.append("[SCANTHIS] Features visited=").append(traceResult.entries().size())
                .append(", changed-center=").append(traceResult.changedEntries())
                .append(", failed=").append(traceResult.failedEntries())
                .append(", skipped=").append(traceResult.skippedEntries()).append(ln);
        builder.append("[SCANTHIS] Note: per-feature deltas below count only changes inside the center chunk.").append(ln);
        builder.append("[SCANTHIS] --- FEATURE TRACE ---").append(ln);

        if (traceResult.entries().isEmpty()) {
            builder.append("[SCANTHIS]   <no feature trace entries>").append(ln);
        } else {
            for (UltraFastChunkGenerator.FeatureTraceEntry entry : traceResult.entries()) {
                appendFeatureTraceEntry(builder, entry, ln);
            }
        }

        builder.append("[SCANTHIS] ==================================================");
        ComplexityAnalyzer.LOGGER.info(builder.toString());
    }

    private static void appendCounts(StringBuilder builder, Map<String, Integer> counts, String ln) {
        if (counts.isEmpty()) {
            builder.append("[SCANTHIS]   <empty>").append(ln);
            return;
        }

        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> builder.append("[SCANTHIS]   ")
                        .append(entry.getKey())
                        .append(" = ")
                        .append(entry.getValue())
                        .append(ln));
    }

    private static void appendDiffs(StringBuilder builder, ComparisonSummary summary, String ln) {
        if (summary.differences().isEmpty()) {
            builder.append("[SCANTHIS]   <no differences>").append(ln);
            return;
        }

        for (DiffEntry diff : summary.differences()) {
            builder.append("[SCANTHIS]   ")
                    .append(diff.blockId())
                    .append(" | real ")
                    .append(diff.realCount())
                    .append(" | virtual ")
                    .append(diff.virtualCount())
                    .append(" | delta ")
                    .append(diff.delta() > 0 ? "+" : "")
                    .append(diff.delta())
                    .append(ln);
        }
    }

    private static void appendFeatureTraceEntry(StringBuilder builder,
                                                UltraFastChunkGenerator.FeatureTraceEntry entry,
                                                String ln) {
        builder.append("[SCANTHIS]   [origin ")
                .append(entry.originChunkX())
                .append(", ")
                .append(entry.originChunkZ())
                .append(" | step ")
                .append(entry.step())
                .append(" #")
                .append(entry.featureIndex())
                .append("] ")
                .append(entry.featureName())
                .append(" | ")
                .append(entry.status().name().toLowerCase());

        if (entry.status() == UltraFastChunkGenerator.FeatureTraceStatus.APPLIED) {
            builder.append(" | changed ids ")
                    .append(entry.changedBlockTypes())
                    .append(" | abs ")
                    .append(entry.absoluteDelta())
                    .append(" | net ");
            if (entry.netDelta() > 0) {
                builder.append("+");
            }
            builder.append(entry.netDelta()).append(ln);

            if (entry.blockDeltas().isEmpty()) {
                builder.append("[SCANTHIS]      <no center-chunk delta>").append(ln);
            } else {
                for (Map.Entry<String, Integer> delta : entry.blockDeltas().entrySet()) {
                    builder.append("[SCANTHIS]      ")
                            .append(delta.getKey())
                            .append(" = ");
                    if (delta.getValue() > 0) {
                        builder.append("+");
                    }
                    builder.append(delta.getValue()).append(ln);
                }
            }
            return;
        }

        if (entry.error() != null && !entry.error().isBlank()) {
            builder.append(" | ").append(entry.error());
        }
        builder.append(ln);
    }

    private static ComparisonSummary compare(Map<String, Integer> realCounts, Map<String, Integer> virtualCounts) {
        List<DiffEntry> differences = new ArrayList<>();
        int absoluteDelta = 0;
        int onlyInReal = 0;
        int onlyInVirtual = 0;

        for (String blockId : new TreeSet<>(unionKeys(realCounts, virtualCounts))) {
            int realCount = realCounts.getOrDefault(blockId, 0);
            int virtualCount = virtualCounts.getOrDefault(blockId, 0);
            int delta = virtualCount - realCount;
            if (delta == 0) {
                continue;
            }

            if (realCount > 0 && virtualCount == 0) {
                onlyInReal++;
            } else if (realCount == 0) {
                onlyInVirtual++;
            }

            absoluteDelta += Math.abs(delta);
            differences.add(new DiffEntry(blockId, realCount, virtualCount, delta));
        }

        differences.sort(Comparator
                .comparingInt((DiffEntry diff) -> Math.abs(diff.delta()))
                .reversed()
                .thenComparing(DiffEntry::blockId));

        return new ComparisonSummary(differences, absoluteDelta, onlyInReal, onlyInVirtual);
    }

    private static int total(Map<String, Integer> counts) {
        int sum = 0;
        for (int count : counts.values()) {
            sum += count;
        }
        return sum;
    }

    private static List<String> unionKeys(Map<String, Integer> left, Map<String, Integer> right) {
        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(left.keySet());
        keys.addAll(right.keySet());
        return new ArrayList<>(keys);
    }

    private enum VirtualMode {
        CACHED_FEATURES() {
            @Override
            Map<String, Integer> generate(UltraFastChunkGenerator generator, ChunkPos chunkPos, ChunkAnalyzer analyzer) {
                List<ChunkAccess> generatedChunks = generator.generateBatch(List.of(chunkPos));
                if (generatedChunks.isEmpty()) {
                    return Collections.emptyMap();
                }
                return extractCounts(analyzer.createSnapshot(generatedChunks.getFirst()));
            }
        },
        FRESH_TERRAIN() {
            @Override
            Map<String, Integer> generate(UltraFastChunkGenerator generator, ChunkPos chunkPos, ChunkAnalyzer analyzer) {
                return extractCounts(analyzer.createSnapshot(
                        generator.generateDiagnosticChunk(chunkPos, UltraFastChunkGenerator.DiagnosticPhase.TERRAIN)));
            }
        },
        FRESH_SURFACE() {
            @Override
            Map<String, Integer> generate(UltraFastChunkGenerator generator, ChunkPos chunkPos, ChunkAnalyzer analyzer) {
                return extractCounts(analyzer.createSnapshot(
                        generator.generateDiagnosticChunk(chunkPos, UltraFastChunkGenerator.DiagnosticPhase.SURFACE)));
            }
        },
        FRESH_CARVERS() {
            @Override
            Map<String, Integer> generate(UltraFastChunkGenerator generator, ChunkPos chunkPos, ChunkAnalyzer analyzer) {
                return extractCounts(analyzer.createSnapshot(
                        generator.generateDiagnosticChunk(chunkPos, UltraFastChunkGenerator.DiagnosticPhase.CARVERS)));
            }
        },
        FRESH_FEATURES() {
            @Override
            Map<String, Integer> generate(UltraFastChunkGenerator generator, ChunkPos chunkPos, ChunkAnalyzer analyzer) {
                return extractCounts(analyzer.createSnapshot(
                        generator.generateDiagnosticChunk(chunkPos, UltraFastChunkGenerator.DiagnosticPhase.FEATURES)));
            }
        };

        VirtualMode() {
        }

        abstract Map<String, Integer> generate(UltraFastChunkGenerator generator, ChunkPos chunkPos, ChunkAnalyzer analyzer);

        static VirtualMode fromPhase(UltraFastChunkGenerator.DiagnosticPhase phase) {
            return switch (phase) {
                case TERRAIN -> FRESH_TERRAIN;
                case SURFACE -> FRESH_SURFACE;
                case CARVERS -> FRESH_CARVERS;
                case FEATURES -> FRESH_FEATURES;
            };
        }
    }

    private record DiffEntry(String blockId, int realCount, int virtualCount, int delta) {
    }

    private record ComparisonSummary(List<DiffEntry> differences, int absoluteDelta, int onlyInReal,
                                     int onlyInVirtual) {
        private DiffEntry topDifference() {
            return differences.isEmpty() ? null : differences.getFirst();
        }
    }

    private record ReportResult(String label,
                                Map<String, Integer> realCounts,
                                Map<String, Integer> virtualCounts,
                                ComparisonSummary summary) {
    }

    private static final class MutablePhaseLineBuilder {
        private final String label;
        private final ComparisonSummary summary;
        private DiffEntry topDiff;

        private MutablePhaseLineBuilder(String label, ComparisonSummary summary) {
            this.label = label;
            this.summary = summary;
        }

        private void withTopDiff(DiffEntry topDiff) {
            this.topDiff = topDiff;
        }

        private Component build() {
            MutableComponent component = Component.literal("  " + label + ": ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(summary.differences().size() + " ids | abs " + summary.absoluteDelta())
                            .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                    .append(Component.literal(" | real-only " + summary.onlyInReal() + " | virtual-only " + summary.onlyInVirtual())
                            .withStyle(ChatFormatting.DARK_GRAY));

            if (topDiff != null) {
                String deltaText = topDiff.delta() > 0 ? "+" + topDiff.delta() : Integer.toString(topDiff.delta());
                component.append(Component.literal(" | top ")
                                .withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(topDiff.blockId())
                                .withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(" " + deltaText)
                                .withStyle(topDiff.delta() > 0 ? ChatFormatting.AQUA : ChatFormatting.RED, ChatFormatting.BOLD));
            }

            return component;
        }
    }
}
