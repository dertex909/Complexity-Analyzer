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

package org.complexityanalyzer.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.fml.ModList;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.export.cabin.io.CabinBackgroundService;
import org.complexityanalyzer.network.web.CabinNettyHandler;
import org.complexityanalyzer.network.web.StandaloneWebServer;
import org.complexityanalyzer.util.FormatUtils;

import static org.complexityanalyzer.config.ComplexityConfig.WEB_SERVER_IP;

public final class WebCommand {

    private WebCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("web")
                .then(Commands.literal("url").executes(WebCommand::executeUrl)
                        .then(Commands.literal("link").executes(WebCommand::executeUrlLink)))
                .then(Commands.literal("status").executes(WebCommand::executeStatus))
                .then(Commands.literal("reload").requires(source -> source.hasPermission(2)).executes(WebCommand::executeReload));
    }

    public static int executeUrl(CommandContext<CommandSourceStack> ctx) {
        return executeUrlInternal(ctx.getSource(), false);
    }

    public static int executeUrlLink(CommandContext<CommandSourceStack> ctx) {
        return executeUrlInternal(ctx.getSource(), true);
    }

    private static int executeUrlInternal(CommandSourceStack source, boolean rawLink) {
        var output = new OutputManager(source.getServer());
        String url = CabinNettyHandler.getUrl();

        if (url == null) {
            output.sendEmptyLine(source);
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.web.active"));
            output.sendTip(source, "complexityanalyzer.command.web.lan_tip");
            output.sendEmptyLine(source);
            return 0;
        }

        String publicIp = WEB_SERVER_IP.get().trim();
        String finalUrl = url.replace("127.0.0.1", publicIp).replace("localhost", publicIp).replace("0.0.0.0", publicIp);

        output.sendEmptyLine(source);
        output.sendHeader(source, "🌐", "complexityanalyzer.command.web.header", ChatFormatting.GOLD);
        output.sendEmptyLine(source);

        var urlLabel = rawLink ? finalUrl : "complexityanalyzer.command.web.open_browser";
        output.sendLink(source, "complexityanalyzer.command.web.url_label", urlLabel, finalUrl, ChatFormatting.AQUA, "complexityanalyzer.command.web.click_to_open", finalUrl);

        if ("127.0.0.1".equals(publicIp)) {
            output.sendEmptyLine(source);
            output.sendTip(source, "complexityanalyzer.command.web.ip_tip");
        }

        output.sendEmptyLine(source);
        output.sendFooter(source);
        return 1;
    }

    public static int executeStatus(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var output = new OutputManager(source.getServer());
        var svc = CabinBackgroundService.getInstance();
        var snap = svc.getSnapshot();
        var status = svc.getStatus();
        output.sendEmptyLine(source);
        output.sendHeader(source, "📊", "complexityanalyzer.command.web.status_header", ChatFormatting.GOLD);
        output.sendEmptyLine(source);

        output.sendEntry(source, "⚙", "complexityanalyzer.command.web.status_label", status.name(), ChatFormatting.GRAY, statusColor(status));
        output.sendEntry(source, "👥", "complexityanalyzer.command.web.visitors", String.valueOf(CabinNettyHandler.getVisitorCount()), ChatFormatting.GRAY, ChatFormatting.AQUA);

        if (snap != null) {
            long ageMs = System.currentTimeMillis() - snap.generatedAtMs();
            output.sendEntry(source, "🕒", "complexityanalyzer.command.web.file_age", Component.translatable("complexityanalyzer.unit.time.ago", FormatUtils.formatDurationMs(ageMs)), ChatFormatting.GRAY, ChatFormatting.WHITE);
            output.sendEntry(source, "📂", "complexityanalyzer.command.web.items", String.valueOf(snap.itemCount()), ChatFormatting.GRAY, ChatFormatting.WHITE);
            output.sendEntry(source, "👾", "complexityanalyzer.command.web.mobs", String.valueOf(snap.mobCount()), ChatFormatting.GRAY, ChatFormatting.WHITE);
            output.sendEntry(source, "📜", "complexityanalyzer.command.web.recipes", String.valueOf(snap.recipeCount()), ChatFormatting.GRAY, ChatFormatting.WHITE);
        } else {
            output.sendTip(source, "complexityanalyzer.command.web.no_data");
        }

        var err = svc.getLastError();
        if (err != null) {
            output.sendEmptyLine(source);
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.web.last_error", err.getMessage()));
        }

        output.sendEmptyLine(source);
        output.sendFooter(source);
        return 1;
    }

    public static int executeReload(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var output = new OutputManager(source.getServer());
        var server = source.getServer();
        var engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.web.engine_not_ready", engine.getCurrentState()));
            return 0;
        }
        StandaloneWebServer.start();
        String modVersion = ModList.get().getModContainerById(ComplexityAnalyzer.MODID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");

        output.sendSuccess(source, Component.translatable("complexityanalyzer.command.web.reloading"));

        CabinBackgroundService.getInstance().regenerateAsync(server, engine, modVersion).whenComplete((snap, err) -> {
            MutableComponent done;
            if (err != null) {
                done = Component.translatable("complexityanalyzer.command.web.reload_failed", err.getMessage()).withStyle(ChatFormatting.RED);
            } else if (snap != null) {
                done = Component.translatable("complexityanalyzer.command.web.reload_success", FormatUtils.humanBytes(snap.bytes().length)).withStyle(ChatFormatting.GREEN);
            } else {
                done = Component.translatable("complexityanalyzer.command.web.reload_no_data").withStyle(ChatFormatting.YELLOW);
            }
            server.execute(() -> source.sendSuccess(() -> done, true));
        });
        return 1;
    }

    private static ChatFormatting statusColor(CabinBackgroundService.Status s) {
        return switch (s) {
            case READY -> ChatFormatting.GREEN;
            case BUILDING -> ChatFormatting.AQUA;
            case FAILED -> ChatFormatting.RED;
            default -> ChatFormatting.GRAY;
        };
    }
}