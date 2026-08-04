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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.config.ComplexityConfig;

import java.net.InetSocketAddress;

public final class StandaloneWebServer {

    private static EventLoopGroup bossGroup;
    private static EventLoopGroup workerGroup;
    private static Channel serverChannel;

    private StandaloneWebServer() {
    }

    public static synchronized void start() {
        stop();

        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        int port = ComplexityConfig.WEB_SERVER_PORT.get();
        if (port <= 0) return;

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(2);

        try {
            var b = new ServerBootstrap();
            b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class).childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    String wsPath = "/" + CabinNettyHandler.getToken() + "/ws";
                    ch.pipeline().addLast("http_codec", new HttpServerCodec());
                    ch.pipeline().addLast("http_aggregator", new HttpObjectAggregator(10 * 1024 * 1024));
                    ch.pipeline().addLast("http_chunked", new ChunkedWriteHandler());
                    ch.pipeline().addLast("ws_protocol", new WebSocketServerProtocolHandler(wsPath, null, true));
                    ch.pipeline().addLast("cabin_handler", new CabinNettyHandler());
                }
            });

            serverChannel = b.bind(new InetSocketAddress(port)).sync().channel();
            ComplexityAnalyzer.LOGGER.info("Standalone web dashboard server started on port {}", port);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("Failed to bind standalone web server to port {}: {}", port, e.getMessage());
            stop();
        }
    }

    public static synchronized void stop() {
        if (serverChannel != null) {
            try {
                serverChannel.close().sync();
            } catch (Exception ignored) {
            }
            serverChannel = null;
        }

        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
    }

    public static boolean isRunning() {
        return serverChannel != null && serverChannel.isActive();
    }
}