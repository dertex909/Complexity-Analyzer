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

import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.export.cabin.io.CabinBackgroundService;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.channel.ChannelFutureListener.CLOSE;
import static io.netty.util.CharsetUtil.UTF_8;

public class CabinNettyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final String VIEWER_BASE = "/assets/complexityanalyzer/viewer";
    private static final Set<String> uniqueVisitors = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static volatile String cachedToken = null;

    public static long getVisitorCount() {
        return uniqueVisitors.size();
    }

    public static String getToken() {
        if (cachedToken != null) return cachedToken;

        synchronized (CabinNettyHandler.class) {
            if (cachedToken != null) return cachedToken;

            String configuredToken = ComplexityConfig.WEB_SERVER_TOKEN.get().trim();
            if (configuredToken.isEmpty()) {
                configuredToken = Base64.getUrlEncoder().withoutPadding().encodeToString(generateRandomBytes());
                ComplexityConfig.WEB_SERVER_TOKEN.set(configuredToken);
                ComplexityConfig.WEB_SERVER_TOKEN.save();
            }
            cachedToken = configuredToken;
            return cachedToken;
        }
    }

    public static void resetToken() {
        synchronized (CabinNettyHandler.class) {
            cachedToken = null;
        }
    }

    @SuppressWarnings("ConstantValue")
    public static String getUrl() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;

        int configuredPort = ComplexityConfig.WEB_SERVER_PORT.get();
        if (configuredPort > 0 && !StandaloneWebServer.isRunning()) return null;
        int port = configuredPort > 0 ? configuredPort : server.getPort();
        if (port <= 0) return null;

        String hostname = server.getLocalIp();
        if (hostname == null || hostname.isEmpty() || "0.0.0.0".equals(hostname) || "0:0:0:0:0:0:0:0".equals(hostname)) {
            hostname = "127.0.0.1";
        }

        return "http://" + hostname + ":" + port + "/" + getToken() + "/";
    }

    private static byte[] generateRandomBytes() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static String extractIp(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress addr && addr.getAddress() != null)
            return addr.getAddress().getHostAddress();
        return String.valueOf(ctx.channel().remoteAddress());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);

        if (!request.decoderResult().isSuccess()) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, false);
            return;
        }

        String uri = request.uri();
        String currentToken = getToken();
        String prefix = "/" + currentToken;

        if (!uri.startsWith(prefix)) {
            sendError(ctx, HttpResponseStatus.FORBIDDEN, false);
            return;
        }

        uniqueVisitors.add(extractIp(ctx));

        String rawPath = uri.substring(prefix.length());
        int queryIndex = rawPath.indexOf('?');
        String path = queryIndex != -1 ? rawPath.substring(0, queryIndex) : rawPath;

        if (path.isEmpty() || path.equals("/")) path = "/index.html";

        if (path.equals("/index.html")) {
            serveResource(ctx, "/index.html", "text/html; charset=UTF-8", keepAlive);
        } else if (path.equals("/api/meta")) {
            handleMeta(ctx, keepAlive);
        } else if (path.equals("/api/cabin")) {
            handleCabin(ctx, keepAlive);
        } else {
            serveResource(ctx, path, getMimeType(path), keepAlive);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            CabinWsHub.register(ctx.channel());
            var snap = CabinBackgroundService.getInstance().getSnapshot();
            if (snap != null) ctx.channel().writeAndFlush(new TextWebSocketFrame(Long.toHexString(snap.fileHash())));
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (ctx.channel().isActive()) ctx.close();
    }

    private void handleMeta(ChannelHandlerContext ctx, boolean keepAlive) {
        var snap = CabinBackgroundService.getInstance().getSnapshot();
        boolean hasCabin = snap != null;

        String serverName = "Minecraft Server";
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) serverName = server.getMotd();

        var json = new JsonObject();
        json.addProperty("hasCabin", hasCabin);
        json.addProperty("size", hasCabin ? snap.bytes().length : 0);
        json.addProperty("hash", hasCabin ? Long.toHexString(snap.fileHash()) : "0");
        json.addProperty("generatedAtMs", hasCabin ? snap.generatedAtMs() : 0);
        json.addProperty("itemCount", hasCabin ? snap.itemCount() : 0);
        json.addProperty("mobCount", hasCabin ? snap.mobCount() : 0);
        json.addProperty("recipeCount", hasCabin ? snap.recipeCount() : 0);
        json.addProperty("serverName", serverName);
        json.addProperty("viewerVersion", "1.2");
        sendResponse(ctx, json.toString(), keepAlive);
    }

    private void handleCabin(ChannelHandlerContext ctx, boolean keepAlive) {
        var snap = CabinBackgroundService.getInstance().getSnapshot();
        if (snap == null) {
            sendError(ctx, HttpResponseStatus.NOT_FOUND, keepAlive);
            return;
        }

        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(snap.bytes()));

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

        finish(ctx, response, keepAlive);
    }

    private void serveResource(ChannelHandlerContext ctx, String path, String mimeType, boolean keepAlive) {
        if (path.contains("..") || path.contains("//")) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, keepAlive);
            return;
        }

        try (var in = getClass().getResourceAsStream(VIEWER_BASE + path)) {
            if (in == null) {
                sendError(ctx, HttpResponseStatus.NOT_FOUND, keepAlive);
                return;
            }

            byte[] data = in.readAllBytes();
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(data));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, mimeType);
            finish(ctx, response, keepAlive);
        } catch (Exception e) {
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, false);
        }
    }

    private String getMimeType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (path.endsWith(".css")) return "text/css; charset=UTF-8";
        if (path.endsWith(".json")) return "application/json; charset=UTF-8";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".wasm")) return "application/wasm";
        return "application/octet-stream";
    }

    private void sendResponse(ChannelHandlerContext ctx, String content, boolean keepAlive) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer(content, UTF_8));

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

        finish(ctx, response, keepAlive);
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, boolean keepAlive) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer("Failure: " + status + "\r\n", UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        finish(ctx, response, keepAlive);
    }

    private void finish(ChannelHandlerContext ctx, FullHttpResponse response, boolean keepAlive) {
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store, no-cache, must-revalidate");
        response.headers().set(HttpHeaderNames.PRAGMA, "no-cache");
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(response);
        } else {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ctx.writeAndFlush(response).addListener(CLOSE);
        }
    }
}