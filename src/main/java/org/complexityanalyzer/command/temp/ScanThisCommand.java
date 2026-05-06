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

package org.complexityanalyzer.command.temp;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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
import org.complexityanalyzer.geoscan.worldgen.VanillaChunkGeneratorService;

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

    private ScanThisCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("scanthis")
                .requires(source -> source.hasPermission(2))
                .executes(ScanThisCommand::execute);
    }

    public static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        ChunkPos chunkPos = player.chunkPosition();

        ChunkAnalyzer analyzer = new ChunkAnalyzer();
        Map<String, Integer> realCounts = extractCounts(analyzer.createSnapshot(level.getChunk(chunkPos.x, chunkPos.z)));

        VanillaChunkGeneratorService generator = new VanillaChunkGeneratorService(level);
        ChunkAccess virtualChunk = generator.generateChunkForAnalysis(chunkPos);
        Map<String, Integer> virtualCounts = virtualChunk != null
                ? extractCounts(analyzer.createSnapshot(virtualChunk))
                : Collections.emptyMap();

        ReportResult report = buildReport(realCounts, virtualCounts);
        logReportToConsole(level, chunkPos, report);
        sendSingleReportToChat(output, source, level, chunkPos, report);
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

    private static MutableComponent buildCountSummary(String label, Map<String, Integer> counts, ChatFormatting accentColor) {
        return Component.literal("  " + label + ": ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(total(counts) + " blocks | " + counts.size() + " types")
                        .withStyle(accentColor, ChatFormatting.BOLD));
    }

    private static ReportResult buildReport(Map<String, Integer> realCounts, Map<String, Integer> virtualCounts) {
        return new ReportResult("vanilla-gen", realCounts, virtualCounts, compare(realCounts, virtualCounts));
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

    private record DiffEntry(String blockId, int realCount, int virtualCount, int delta) {
    }

    private record ComparisonSummary(List<DiffEntry> differences, int absoluteDelta, int onlyInReal,
                                     int onlyInVirtual) {
    }

    private record ReportResult(String label,
                                Map<String, Integer> realCounts,
                                Map<String, Integer> virtualCounts,
                                ComparisonSummary summary) {
    }
}
