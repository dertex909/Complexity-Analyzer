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

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.complexityanalyzer.export.cabin.io.CabinBackgroundService;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.util.CharsetUtil.UTF_8;

public class CabinNettyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final String VIEWER_BASE = "/assets/complexityanalyzer/viewer";
    private static final String TOKEN = Base64.getUrlEncoder().withoutPadding().encodeToString(generateRandomBytes());
    private static final Set<String> uniqueVisitors = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static long getVisitorCount() {
        return uniqueVisitors.size();
    }

    @SuppressWarnings("ConstantValue")
    public static String getUrl() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;

        int port = server.getPort();
        if (port <= 0) return null;

        String hostname = server.getLocalIp();
        if (hostname == null || hostname.isEmpty() || "0.0.0.0".equals(hostname) || "0:0:0:0:0:0:0:0".equals(hostname)) {
            hostname = "127.0.0.1";
        }

        return "http://" + hostname + ":" + port + "/" + TOKEN + "/";
    }

    private static byte[] generateRandomBytes() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return b;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String remoteAddress = ctx.channel().remoteAddress().toString();
        if (remoteAddress.startsWith("/")) remoteAddress = remoteAddress.substring(1);
        String ip = remoteAddress.split(":")[0];
        uniqueVisitors.add(ip);

        if (!request.decoderResult().isSuccess()) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }

        String uri = request.uri();
        String prefix = "/" + TOKEN;

        if (!uri.startsWith(prefix)) {
            ctx.fireChannelRead(request.retain());
            return;
        }

        String path = uri.substring(prefix.length()).split("\\?")[0];
        if (path.isEmpty() || path.equals("/")) path = "/index.html";

        if (path.equals("/index.html")) {
            serveResource(ctx, "/index.html", "text/html; charset=UTF-8");
        } else if (path.equals("/api/meta")) {
            handleMeta(ctx);
        } else if (path.equals("/api/cabin")) {
            handleCabin(ctx);
        } else {
            serveResource(ctx, path, getMimeType(path));
        }
    }

    private void handleMeta(ChannelHandlerContext ctx) {
        var snap = CabinBackgroundService.getInstance().getSnapshot();
        boolean hasCabin = snap != null;

        String serverName = "Minecraft Server";
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) serverName = server.getMotd();

        String json = String.format(
                "{\"hasCabin\":%b,\"size\":%d,\"hash\":\"%s\",\"generatedAtMs\":%d,\"itemCount\":%d,\"mobCount\":%d,\"recipeCount\":%d,\"serverName\":\"%s\",\"viewerVersion\":\"1.2\"}",
                hasCabin,
                hasCabin ? snap.bytes().length : 0,
                hasCabin ? Long.toHexString(snap.fileHash()) : "0",
                hasCabin ? snap.generatedAtMs() : 0,
                hasCabin ? snap.itemCount() : 0,
                hasCabin ? snap.mobCount() : 0,
                hasCabin ? snap.recipeCount() : 0,
                serverName.replace("\"", "\\\"")
        );

        sendResponse(ctx, json);
    }

    private void handleCabin(ChannelHandlerContext ctx) {
        var snap = CabinBackgroundService.getInstance().getSnapshot();
        if (snap == null) {
            sendError(ctx, HttpResponseStatus.NOT_FOUND);
            return;
        }

        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(snap.bytes()));

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void serveResource(ChannelHandlerContext ctx, String path, String mimeType) {
        try (var in = getClass().getResourceAsStream(VIEWER_BASE + path)) {
            if (in == null) {
                sendError(ctx, HttpResponseStatus.NOT_FOUND);
                return;
            }

            byte[] data = in.readAllBytes();
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(data));

            response.headers().set(HttpHeaderNames.CONTENT_TYPE, mimeType);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } catch (Exception e) {
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String getMimeType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (path.endsWith(".css")) return "text/css; charset=UTF-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".wasm")) return "application/wasm";
        return "application/octet-stream";
    }

    private void sendResponse(ChannelHandlerContext ctx, String content) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer(content, UTF_8));

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer("Failure: " + status + "\r\n", UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}