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

package org.complexityanalyzer.client.cabin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.complexityanalyzer.ComplexityAnalyzer;

import java.awt.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class CabinHttpServer {
    private static final long IDLE_SHUTDOWN_MS = 10 * 60 * 1000L;
    private static final String VIEWER_BASE = "/assets/complexityanalyzer/viewer";
    private static final CabinHttpServer INSTANCE = new CabinHttpServer();

    public static CabinHttpServer getInstance() {
        return INSTANCE;
    }

    private final AtomicReference<HttpServer> server = new AtomicReference<>();
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicReference<Integer> port = new AtomicReference<>();
    private final AtomicLong lastActivity = new AtomicLong(0);
    private ScheduledExecutorService scheduler;
    private final Object2ObjectMap<String, byte[]> resourceCache = Object2ObjectMaps.synchronize(new Object2ObjectOpenHashMap<>());
    private final SecureRandom rng = new SecureRandom();

    private CabinHttpServer() {
    }

    public synchronized String start() {
        if (server.get() != null) {
            lastActivity.set(System.currentTimeMillis());
            return buildUrl();
        }
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", new RootHandler());
            http.createContext("/api/meta", new MetaHandler());
            http.createContext("/api/cabin", new CabinHandler());
            http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            http.start();
            port.set(http.getAddress().getPort());
            token.set(Base64.getUrlEncoder().withoutPadding().encodeToString(rng.generateSeed(32)));
            server.set(http);
            lastActivity.set(System.currentTimeMillis());
            scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("Cabin-HTTP-Idle").factory());
            scheduler.scheduleAtFixedRate(this::idleCheck, 30, 30, TimeUnit.SECONDS);
            ComplexityAnalyzer.LOGGER.info("[CabinHttp] Listening on http://127.0.0.1:{}", port.get());
            return buildUrl();
        } catch (IOException e) {
            ComplexityAnalyzer.LOGGER.error("[CabinHttp] Failed to start server", e);
            return null;
        }
    }

    public synchronized void stop() {
        HttpServer http = server.getAndSet(null);
        if (http != null) http.stop(0);
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
        token.set(null);
        port.set(null);
        ComplexityAnalyzer.LOGGER.info("[CabinHttp] Stopped");
    }

    public boolean isRunning() {
        return server.get() != null;
    }

    public String getUrl() {
        return isRunning() ? buildUrl() : null;
    }

    private String buildUrl() {
        return "http://127.0.0.1:" + port.get() + "/?token=" + token.get();
    }

    private void idleCheck() {
        if (System.currentTimeMillis() - lastActivity.get() > IDLE_SHUTDOWN_MS) {
            ComplexityAnalyzer.LOGGER.info("[CabinHttp] Idle timeout, stopping");
            stop();
        }
    }

    private boolean isAuthInvalid(HttpExchange ex) {
        if (!ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
            sendStatus(ex, 403, "forbidden");
            return true;
        }
        String t = token.get();
        String p = extractToken(ex.getRequestURI().getRawQuery());
        if (t != null && t.equals(p)) {
            lastActivity.set(System.currentTimeMillis());
            return false;
        }
        sendStatus(ex, 401, "unauthorized");
        return true;
    }

    private String extractToken(String q) {
        if (q == null) return null;
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && "token".equals(kv[0])) return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
        }
        return null;
    }

    private void sendStatus(HttpExchange ex, int code, String text) {
        try {
            byte[] b = (text + "\n").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        } catch (IOException ignored) {
        }
    }

    private byte[] load(String path) {
        byte[] c = resourceCache.get(path);
        if (c != null) return c;
        try (InputStream in = CabinHttpServer.class.getResourceAsStream(VIEWER_BASE + path)) {
            if (in == null) return null;
            byte[] data = in.readAllBytes();
            resourceCache.put(path, data);
            return data;
        } catch (IOException e) {
            return null;
        }
    }

    private String mime(String p) {
        String s = p.toLowerCase();
        if (s.endsWith(".html")) return "text/html; charset=utf-8";
        if (s.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (s.endsWith(".css")) return "text/css; charset=utf-8";
        if (s.endsWith(".svg")) return "image/svg+xml";
        if (s.endsWith(".png")) return "image/png";
        if (s.endsWith(".wasm")) return "application/wasm";
        if (s.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) {
            try {
                String path = ex.getRequestURI().getPath();
                if (path.equals("/")) path = "/index.html";
                if (path.equals("/index.html") && isAuthInvalid(ex)) return;

                byte[] data = load(path);
                if (data == null) {
                    sendStatus(ex, 404, "not found");
                    return;
                }

                ex.getResponseHeaders().set("Content-Type", mime(path));
                ex.getResponseHeaders().set("Cache-Control", "no-store");
                ex.sendResponseHeaders(200, data.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(data);
                }
            } catch (Exception e) {
                sendStatus(ex, 500, "internal error");
            }
        }
    }

    private class MetaHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) {
            try {
                if (isAuthInvalid(ex)) return;
                var snap = CabinReceiver.getInstance().getCached();
                long size = 0, hash = 0, time = 0;
                int items = 0, mobs = 0, recipes = 0;
                String name = "";

                if (snap != null) {
                    size = snap.bytes().length;
                    hash = snap.fileHash();
                    time = snap.generatedAtMs();
                    items = snap.itemCount();
                    mobs = snap.mobCount();
                    recipes = snap.recipeCount();
                    name = snap.serverName();
                } else {
                    byte[] disk = ClientCabinStorage.readCabin();
                    if (disk != null) {
                        size = disk.length;
                        hash = ClientCabinStorage.readKnownHash();
                    }
                }

                String json = String.format("""
                        {"hasCabin":%b,"size":%d,"hash":"%s","generatedAtMs":%d,"itemCount":%d,"mobCount":%d,"recipeCount":%d,"serverName":"%s","viewerVersion":"1.1"}
                        """, size > 0, size, Long.toHexString(hash), time, items, mobs, recipes, name.replace("\"", "\\\""));

                byte[] b = json.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
            } catch (Exception e) {
                sendStatus(ex, 500, "internal error");
            }
        }
    }

    private class CabinHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) {
            try {
                if (isAuthInvalid(ex)) return;
                var snap = CabinReceiver.getInstance().getCached();
                byte[] data = snap != null ? snap.bytes() : ClientCabinStorage.readCabin();
                if (data == null) {
                    sendStatus(ex, 404, "not available");
                    return;
                }

                String range = ex.getRequestHeaders().getFirst("Range");
                ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
                ex.getResponseHeaders().set("Accept-Ranges", "bytes");

                if (range != null && range.startsWith("bytes=")) {
                    try {
                        String[] parts = range.substring(6).split("-", 2);
                        int start = Integer.parseInt(parts[0]);
                        int end = parts[1].isEmpty() ? data.length - 1 : Integer.parseInt(parts[1]);
                        if (start >= data.length || end < start) throw new Exception();
                        int len = Math.min(end, data.length - 1) - start + 1;
                        ex.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + (start + len - 1) + "/" + data.length);
                        ex.sendResponseHeaders(206, len);
                        try (OutputStream os = ex.getResponseBody()) {
                            os.write(data, start, len);
                        }
                    } catch (Exception e) {
                        sendStatus(ex, 416, "invalid range");
                    }
                } else {
                    ex.sendResponseHeaders(200, data.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(data);
                    }
                }
            } catch (Exception e) {
                sendStatus(ex, 500, "internal error");
            }
        }
    }

    public boolean openInBrowser() {
        String url = getUrl();
        if (url != null) try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}