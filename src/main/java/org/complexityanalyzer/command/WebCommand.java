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
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.export.cabin.io.CabinBackgroundService;
import org.complexityanalyzer.network.web.CabinNettyHandler;
import org.complexityanalyzer.network.web.WebManager;
import org.complexityanalyzer.util.FormatUtils;

import java.util.concurrent.CompletionException;

import static net.minecraft.ChatFormatting.*;
import static org.complexityanalyzer.config.ComplexityConfig.WEB_SERVER_IP;

public final class WebCommand {

    private WebCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("web")
                .then(Commands.literal("url").executes(ctx -> executeUrl(ctx.getSource(), false))
                        .then(Commands.literal("link").executes(ctx -> executeUrl(ctx.getSource(), true))))
                .then(Commands.literal("status").executes(WebCommand::executeStatus))
                .then(Commands.literal("reload").requires(source -> source.hasPermission(2)).executes(WebCommand::executeReload));
    }

    private static int executeUrl(CommandSourceStack source, boolean rawLink) {
        var output = new OutputManager(source.getServer());
        var url = CabinNettyHandler.getUrl();

        if (url == null) {
            output.sendEmptyLine(source);
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.web.active"));
            output.sendTip(source, "complexityanalyzer.command.web.lan_tip");
            output.sendEmptyLine(source);
            return 0;
        }

        var publicIp = WEB_SERVER_IP.get().trim();
        var finalUrl = url.replace("127.0.0.1", publicIp).replace("localhost", publicIp).replace("0.0.0.0", publicIp);

        output.sendEmptyLine(source);
        output.sendHeader(source, "🌐", "complexityanalyzer.command.web.header", GOLD);
        output.sendEmptyLine(source);

        var urlLabel = rawLink ? finalUrl : "complexityanalyzer.command.web.open_browser";
        output.sendLink(source, "complexityanalyzer.command.web.url_label", urlLabel, finalUrl, AQUA, "complexityanalyzer.command.web.click_to_open", finalUrl);

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
        output.sendHeader(source, "📊", "complexityanalyzer.command.web.status_header", GOLD);
        output.sendEmptyLine(source);
        output.sendEntry(source, "⚙", "complexityanalyzer.command.web.status_label", status.name(), GRAY, statusColor(status));
        output.sendEntry(source, "👥", "complexityanalyzer.command.web.visitors", CabinNettyHandler.getVisitorCount(), GRAY, AQUA);

        if (snap != null) {
            var ageSeconds = FormatUtils.formatDurationSeconds(Math.max(0L, (System.currentTimeMillis() - snap.generatedAtMs()) / 1000L));
            output.sendEntry(source, "🕒", "complexityanalyzer.command.web.file_age", Component.translatable("complexityanalyzer.unit.time.ago", ageSeconds), GRAY, WHITE);
            output.sendEntry(source, "📂", "complexityanalyzer.command.web.items", snap.itemCount(), GRAY, WHITE);
            output.sendEntry(source, "👾", "complexityanalyzer.command.web.mobs", snap.mobCount(), GRAY, WHITE);
            output.sendEntry(source, "📜", "complexityanalyzer.command.web.recipes", snap.recipeCount(), GRAY, WHITE);
        } else {
            output.sendTip(source, "complexityanalyzer.command.web.no_data");
        }

        var err = svc.getLastError();
        if (err != null) {
            output.sendEmptyLine(source);
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.web.last_error", extractErrorMessage(err)));
        }

        output.sendEmptyLine(source);
        output.sendFooter(source);
        return 1;
    }

    public static int executeReload(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var server = source.getServer();
        var output = new OutputManager(server);
        var engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.web.engine_not_ready", engine.getCurrentState().toString()));
            return 0;
        }

        output.sendSuccess(source, Component.translatable("complexityanalyzer.command.web.reloading"));

        WebManager.reload(server, engine).whenComplete((snap, err) -> server.execute(() -> {
            if (err != null) {
                output.sendFailure(source, Component.translatable("complexityanalyzer.command.web.reload_failed", extractErrorMessage(err)));
                return;
            }

            if (snap != null) {
                output.sendSuccess(source, Component.translatable("complexityanalyzer.command.web.reload_success", FormatUtils.humanBytes(snap.bytes().length)).withStyle(GREEN));
            } else {
                output.sendSuccess(source, Component.translatable("complexityanalyzer.command.web.reload_no_data").withStyle(YELLOW));
            }
        }));

        return 1;
    }

    private static String extractErrorMessage(Throwable err) {
        var cause = (err instanceof CompletionException && err.getCause() != null) ? err.getCause() : err;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    private static ChatFormatting statusColor(CabinBackgroundService.Status status) {
        return switch (status) {
            case READY -> GREEN;
            case BUILDING -> AQUA;
            case FAILED -> RED;
            case IDLE -> GRAY;
        };
    }
}