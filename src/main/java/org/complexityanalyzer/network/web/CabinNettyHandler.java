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
import io.netty.util.AsciiString;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.export.cabin.io.CabinBackgroundService;
import org.complexityanalyzer.network.web.pack.CabinResourceResolver;
import org.complexityanalyzer.network.web.ws.CabinWsHub;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.channel.ChannelFutureListener.CLOSE;
import static io.netty.util.CharsetUtil.UTF_8;
import static java.security.SecureRandom.getSeed;
import static java.util.Base64.getUrlEncoder;

public class CabinNettyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    public static final String PREFIX = "u";
    private static final String ROOT_PATH = "/";
    private static final String PREFIX_PATH = ROOT_PATH + PREFIX + ROOT_PATH;
    private static final String ROUTE_META = "/api/meta";
    private static final String ROUTE_CABIN = "/api/cabin";
    private static final String ROUTE_INDEX = "/index.html";
    private static final String SCHEME_HTTP = "http://";
    private static final String CORS_WILDCARD = "*";
    private static final char DOT = '.';
    private static final AsciiString TEXT_PLAIN_UTF8 = AsciiString.cached("text/plain; charset=UTF-8");

    private static final ConcurrentHashMap.KeySetView<String, Boolean> uniqueVisitors = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, String> MIME_TYPES = new ConcurrentHashMap<>();
    private static volatile String cachedToken = null;

    static {
        registerMimeType(".html", "text/html; charset=UTF-8");
        registerMimeType(".js", "text/javascript; charset=UTF-8");
        registerMimeType(".mjs", "text/javascript; charset=UTF-8");
        registerMimeType(".css", "text/css; charset=UTF-8");
        registerMimeType(".wasm", "application/wasm");
        registerMimeType(".json", "application/json");
        registerMimeType(".xml", "application/xml; charset=UTF-8");
        registerMimeType(".txt", "text/plain; charset=UTF-8");
        registerMimeType(".csv", "text/csv; charset=UTF-8");
        registerMimeType(".yml", "text/yaml; charset=UTF-8");
        registerMimeType(".yaml", "text/yaml; charset=UTF-8");
        registerMimeType(".ico", "image/x-icon");
        registerMimeType(".png", "image/png");
        registerMimeType(".jpg", "image/jpeg");
        registerMimeType(".jpeg", "image/jpeg");
        registerMimeType(".svg", "image/svg+xml");
        registerMimeType(".webp", "image/webp");
        registerMimeType(".gif", "image/gif");
        registerMimeType(".woff2", "font/woff2");
        registerMimeType(".woff", "font/woff");
        registerMimeType(".ttf", "font/ttf");
        registerMimeType(".otf", "font/otf");
        registerMimeType(".ogg", "audio/ogg");
        registerMimeType(".mp3", "audio/mpeg");
        registerMimeType(".wav", "audio/wav");
        registerMimeType(".gltf", "model/gltf+json");
        registerMimeType(".glb", "model/gltf-binary");
        registerMimeType(".zip", "application/zip");
    }

    public static void registerMimeType(String extension, String mimeType) {
        if (extension == null || mimeType == null) return;
        String ext = extension.toLowerCase();
        if (!ext.startsWith(".")) ext = "." + ext;
        MIME_TYPES.put(ext, mimeType);
    }

    public static long getVisitorCount() {
        return uniqueVisitors.size();
    }

    public static String getToken() {
        if (cachedToken != null) return cachedToken;

        synchronized (CabinNettyHandler.class) {
            if (cachedToken != null) return cachedToken;

            String configuredToken = ComplexityConfig.WEB_SERVER_TOKEN.get().trim();
            if (configuredToken.isEmpty()) {
                configuredToken = getUrlEncoder().withoutPadding().encodeToString(getSeed(32));
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

        return SCHEME_HTTP + hostname + ":" + port + ROOT_PATH + getToken() + ROOT_PATH;
    }

    private static String extractIp(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress addr && addr.getAddress() != null)
            return addr.getAddress().getHostAddress();
        return String.valueOf(ctx.channel().remoteAddress());
    }

    public static String getMimeType(String path) {
        if (path == null) return HttpHeaderValues.APPLICATION_OCTET_STREAM.toString();
        int dotIndex = path.lastIndexOf(DOT);
        if (dotIndex != -1) {
            String ext = path.substring(dotIndex).toLowerCase();
            String mime = MIME_TYPES.get(ext);
            if (mime != null) return mime;
        }
        return HttpHeaderValues.APPLICATION_OCTET_STREAM.toString();
    }

    private static boolean isSafePath(String path) {
        if (path == null) return false;
        return !path.contains("..")
                && !path.contains("//")
                && !path.contains("\\")
                && !path.contains(":")
                && !path.contains("\0");
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
        String prefix = ROOT_PATH + currentToken;

        if (!uri.equals(prefix) && !uri.startsWith(prefix + ROOT_PATH)) {
            sendError(ctx, HttpResponseStatus.FORBIDDEN, false);
            return;
        }

        uniqueVisitors.add(extractIp(ctx));

        String rawPath = uri.substring(prefix.length());
        int queryIndex = rawPath.indexOf('?');
        String path = queryIndex != -1 ? rawPath.substring(0, queryIndex) : rawPath;

        if (path.isEmpty() || path.equals(ROOT_PATH)) path = ROUTE_INDEX;

        if (!isSafePath(path)) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, keepAlive);
            return;
        }

        if (path.equals(ROUTE_META)) {
            handleMeta(ctx, keepAlive);
        } else if (path.equals(ROUTE_CABIN)) {
            handleCabin(ctx, keepAlive);
        } else if (path.startsWith(PREFIX_PATH)) {
            handleExternalResource(ctx, path.substring(PREFIX_PATH.length()), keepAlive);
        } else {
            serveResource(ctx, path, getMimeType(path), keepAlive);
        }
    }

    private void handleExternalResource(ChannelHandlerContext ctx, String extPath, boolean keepAlive) {
        int slash = extPath.indexOf('/');
        if (slash == -1) {
            sendError(ctx, HttpResponseStatus.NOT_FOUND, keepAlive);
            return;
        }

        String namespace = extPath.substring(0, slash);
        String subpath = extPath.substring(slash + 1);

        var data = CabinResourceResolver.resolveExternal(namespace, subpath);
        if (data == null) {
            sendError(ctx, HttpResponseStatus.NOT_FOUND, keepAlive);
            return;
        }

        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(data));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, getMimeType(subpath));
        finish(ctx, response, keepAlive);
    }

    private void serveResource(ChannelHandlerContext ctx, String path, String mimeType, boolean keepAlive) {
        try {
            var data = CabinResourceResolver.resolveInternal(path);
            if (data == null) {
                sendError(ctx, HttpResponseStatus.NOT_FOUND, keepAlive);
                return;
            }

            if (path.equals(ROUTE_INDEX)) data = CabinResourceResolver.injectIndexHtml(data, getToken());
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(data));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, mimeType);
            finish(ctx, response, keepAlive);
        } catch (Exception e) {
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, false);
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
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_OCTET_STREAM);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, CORS_WILDCARD);
        finish(ctx, response, keepAlive);
    }

    private void sendResponse(ChannelHandlerContext ctx, String content, boolean keepAlive) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer(content, UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, CORS_WILDCARD);
        finish(ctx, response, keepAlive);
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, boolean keepAlive) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer("Failure: " + status + "\r\n", UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, TEXT_PLAIN_UTF8);
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