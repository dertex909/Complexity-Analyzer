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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import org.complexityanalyzer.config.ComplexityConfig;

import java.util.List;

public class ProtocolDetector extends ByteToMessageDecoder {

    private static boolean isPreserved(String name) {
        return name.equalsIgnoreCase("ssl") || name.equalsIgnoreCase("proxydetector") || name.equalsIgnoreCase("haproxy");
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (ComplexityConfig.WEB_SERVER_PORT.get() > 0) {
            ctx.pipeline().remove(this);
            return;
        }

        if (in.readableBytes() < 4) return;
        if (isHttp(in)) {
            setupHttpPipeline(ctx);
        } else {
            ctx.pipeline().remove(this);
        }
    }

    private boolean isHttp(ByteBuf in) {
        int m = in.getInt(in.readerIndex());
        return m == 0x47455420 || m == 0x504F5354 || m == 0x48454144 || m == 0x50555420 || m == 0x4F505449 || m == 0x44454C45 || m == 0x50415443;
    }

    private void setupHttpPipeline(ChannelHandlerContext ctx) {
        var names = ctx.pipeline().names();
        for (var name : names) {
            if (!name.equals(ctx.name()) && !isPreserved(name)) try {
                ctx.pipeline().remove(name);
            } catch (Throwable ignored) {
            }
        }

        ctx.pipeline().addAfter(ctx.name(), "http_codec", new HttpServerCodec());
        ctx.pipeline().addAfter("http_codec", "http_aggregator", new HttpObjectAggregator(10 * 1024 * 1024));
        ctx.pipeline().addAfter("http_aggregator", "http_chunked", new ChunkedWriteHandler());
        String wsPath = "/" + CabinNettyHandler.getToken() + "/ws";
        ctx.pipeline().addAfter("http_chunked", "ws_protocol", new WebSocketServerProtocolHandler(wsPath, null, true));
        ctx.pipeline().addAfter("ws_protocol", "cabin_handler", new CabinNettyHandler());

        ctx.pipeline().remove(this);
    }
}