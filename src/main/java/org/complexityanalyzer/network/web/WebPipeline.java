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

package org.complexityanalyzer.network.web;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class WebPipeline {

    private static final int MAX_CONTENT_LENGTH = 10 * 1024 * 1024;

    private WebPipeline() {
    }

    public static void setup(@NotNull ChannelPipeline pipeline, @Nullable String afterHandler) {
        String wsPath = "/" + CabinNettyHandler.getToken() + "/ws";

        var handlers = new HandlerEntry[]{
                new HandlerEntry("http_codec", new HttpServerCodec()),
                new HandlerEntry("http_aggregator", new HttpObjectAggregator(MAX_CONTENT_LENGTH)),
                new HandlerEntry("http_chunked", new ChunkedWriteHandler()),
                new HandlerEntry("ws_protocol", new WebSocketServerProtocolHandler(wsPath, null, true)),
                new HandlerEntry("cabin_handler", new CabinNettyHandler())
        };

        String cursor = afterHandler;
        for (var entry : handlers) {
            if (cursor == null) {
                pipeline.addLast(entry.name(), entry.handler());
            } else {
                pipeline.addAfter(cursor, entry.name(), entry.handler());
                cursor = entry.name();
            }
        }
    }

    record HandlerEntry(String name, ChannelHandler handler) {
    }
}