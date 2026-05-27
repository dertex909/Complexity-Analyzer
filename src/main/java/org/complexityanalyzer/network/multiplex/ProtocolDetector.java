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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.network.multiplex;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;

import java.util.List;

public class ProtocolDetector extends ByteToMessageDecoder {

    private static final ObjectSet<String> PRESERVED_HANDLERS = new ObjectOpenHashSet<>(new String[]{"ssl", "proxydetector", "haproxy"});

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) return;
        if (isHttp(in)) setupHttpPipeline(ctx);
        else ctx.pipeline().remove(this);
    }

    private boolean isHttp(ByteBuf in) {
        int m = in.getInt(in.readerIndex());
        return (((m - 0x20202020) | (0x5A5A5A5A - m)) & 0x80808080) == 0;
    }

    private void setupHttpPipeline(ChannelHandlerContext ctx) {
        ObjectList<String> names = new ObjectArrayList<>(ctx.pipeline().names());
        for (String name : names) {
            if (!name.equals(ctx.name()) && !PRESERVED_HANDLERS.contains(name.toLowerCase())) try {
                ctx.pipeline().remove(name);
            } catch (Throwable ignored) {
            }
        }

        ctx.pipeline().addAfter(ctx.name(), "http_codec", new HttpServerCodec());
        ctx.pipeline().addAfter("http_codec", "http_aggregator", new HttpObjectAggregator(10 * 1024 * 1024));
        ctx.pipeline().addAfter("http_aggregator", "http_chunked", new ChunkedWriteHandler());
        ctx.pipeline().addAfter("http_chunked", "cabin_handler", new CabinNettyHandler());

        ctx.pipeline().remove(this);
    }
}