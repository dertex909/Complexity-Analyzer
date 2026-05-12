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
import org.complexityanalyzer.ComplexityAnalyzer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * On-demand loopback HTTP server that serves the embedded viewer and the cached
 * .cabin file from {@link CabinReceiver} memory + {@link ClientCabinStorage} disk.
 * <p>
 * Security:
 * <ul>
 *   <li>Bound exclusively to 127.0.0.1; non-loopback peers are 403-rejected before
 *       reaching any handler.</li>
 *   <li>Every request requires a cryptographically random {@code token} query
 *       parameter; tokens are 32 random bytes encoded as base64url and rotated
 *       on each {@link #start()} call.</li>
 *   <li>Idle auto-shutdown after {@link #IDLE_SHUTDOWN_MS} of no traffic.</li>
 * </ul>
 * Performance:
 * <ul>
 *   <li>{@code GET /api/cabin} supports HTTP Range requests with multi-stream-safe
 *       partial-content responses, enabling JS-side lazy section loads.</li>
 *   <li>Viewer assets are served from the mod's resource bundle.</li>
 * </ul>
 */
public final class CabinHttpServer {

    private static final long IDLE_SHUTDOWN_MS = 10 * 60 * 1000L;
    private static final String VIEWER_RESOURCE_BASE = "/assets/complexityanalyzer/viewer";

    private static final CabinHttpServer INSTANCE = new CabinHttpServer();

    public static CabinHttpServer getInstance() {
        return INSTANCE;
    }

    private final AtomicReference<HttpServer> server = new AtomicReference<>(null);
    private final AtomicReference<String> token = new AtomicReference<>(null);
    private final AtomicReference<Integer> port = new AtomicReference<>(null);
    private final AtomicLong lastActivityMs = new AtomicLong(0);
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> idleTask;
    private final Map<String, byte[]> resourceCache = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();

    private CabinHttpServer() {
    }

    public synchronized String start() throws IOException {
        HttpServer existing = server.get();
        if (existing != null) {
            lastActivityMs.set(System.currentTimeMillis());
            return buildUrl();
        }

        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", new RootHandler());
        http.createContext("/api/meta", new MetaHandler());
        http.createContext("/api/cabin", new CabinHandler());
        http.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Cabin-HTTP");
            t.setDaemon(true);
            return t;
        }));
        http.start();
        this.port.set(http.getAddress().getPort());
        this.token.set(generateToken());
        this.server.set(http);
        this.lastActivityMs.set(System.currentTimeMillis());

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Cabin-HTTP-Idle");
            t.setDaemon(true);
            return t;
        });
        this.idleTask = scheduler.scheduleAtFixedRate(this::idleCheck, 30, 30, TimeUnit.SECONDS);

        ComplexityAnalyzer.LOGGER.info("[CabinHttp] Listening on http://127.0.0.1:{}", port.get());
        return buildUrl();
    }

    public synchronized void stop() {
        HttpServer http = server.getAndSet(null);
        if (http != null) {
            try {
                http.stop(0);
            } catch (Throwable ignored) {
            }
        }
        ScheduledFuture<?> idle = this.idleTask;
        if (idle != null) idle.cancel(false);
        ScheduledExecutorService s = this.scheduler;
        if (s != null) s.shutdownNow();
        this.scheduler = null;
        this.idleTask = null;
        this.token.set(null);
        this.port.set(null);
        ComplexityAnalyzer.LOGGER.info("[CabinHttp] Stopped");
    }

    public boolean isRunning() {
        return server.get() != null;
    }

    public String getUrl() {
        return isRunning() ? buildUrl() : null;
    }

    private String buildUrl() {
        Integer p = port.get();
        String tk = token.get();
        if (p == null || tk == null) return null;
        return "http://127.0.0.1:" + p + "/?token=" + tk;
    }

    private void idleCheck() {
        long now = System.currentTimeMillis();
        long last = lastActivityMs.get();
        if (now - last > IDLE_SHUTDOWN_MS) {
            ComplexityAnalyzer.LOGGER.info("[CabinHttp] Idle timeout, stopping");
            stop();
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        rng.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean isAuthInvalid(HttpExchange ex) {
        if (!ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
            try {
                sendStatus(ex, 403, "forbidden");
            } catch (IOException ignored) {
            }
            return true;
        }
        String query = ex.getRequestURI().getRawQuery();
        String expected = token.get();
        if (expected == null) {
            try {
                sendStatus(ex, 503, "server stopped");
            } catch (IOException ignored) {
            }
            return true;
        }
        String provided = extractTokenParam(query);
        if (areStringsEqual(expected, provided)) {
            lastActivityMs.set(System.currentTimeMillis());
            return false;
        }
        try {
            sendStatus(ex, 401, "bad token");
        } catch (IOException ignored) {
        }
        return true;
    }

    private static boolean areStringsEqual(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    private static String extractTokenParam(String query) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String k = pair.substring(0, eq);
            String v = pair.substring(eq + 1);
            if (k.equals("token")) return java.net.URLDecoder.decode(v, StandardCharsets.UTF_8);
        }
        return null;
    }

    private byte[] loadResource(String relativePath) throws IOException {
        byte[] cached = resourceCache.get(relativePath);
        if (cached != null) return cached;
        String resource = VIEWER_RESOURCE_BASE + relativePath;
        try (InputStream in = CabinHttpServer.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            byte[] data = out.toByteArray();
            resourceCache.put(relativePath, data);
            return data;
        }
    }

    private static String contentTypeFor(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".wasm")) return "application/wasm";
        if (lower.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private static void sendStatus(HttpExchange ex, int status, String text) throws IOException {
        byte[] body = (text + "\n").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) {
            try {
                if (!ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
                    sendStatus(ex, 403, "forbidden");
                    return;
                }
                String path = ex.getRequestURI().getPath();
                if (path.equals("/") || path.equals("/index.html")) path = "/index.html";

                String tokenParam = extractTokenParam(ex.getRequestURI().getRawQuery());
                String expected = token.get();
                if (path.equals("/index.html") && !areStringsEqual(expected, tokenParam)) {
                    sendStatus(ex, 401, "bad token");
                    return;
                }

                byte[] data = loadResource(path);
                if (data == null) {
                    sendStatus(ex, 404, "not found");
                    return;
                }
                lastActivityMs.set(System.currentTimeMillis());
                ex.getResponseHeaders().add("Content-Type", contentTypeFor(path));
                ex.getResponseHeaders().add("Cache-Control", "no-store");
                ex.sendResponseHeaders(200, data.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(data);
                }
            } catch (Throwable t) {
                ComplexityAnalyzer.LOGGER.warn("[CabinHttp] root handler error", t);
                try {
                    sendStatus(ex, 500, "internal error");
                } catch (IOException ignored) {
                }
            }
        }
    }

    private final class MetaHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) {
            try {
                if (isAuthInvalid(ex)) return;
                CabinReceiver.CachedSnapshot snap = CabinReceiver.getInstance().getCached();
                byte[] disk;
                long size = 0L;
                long hash = 0L;
                long generatedAt = 0L;
                int items = 0;
                int mobs = 0;
                int recipes = 0;
                String name = "";
                if (snap != null) {
                    size = snap.bytes().length;
                    hash = snap.fileHash();
                    generatedAt = snap.generatedAtMs();
                    items = snap.itemCount();
                    mobs = snap.mobCount();
                    recipes = snap.recipeCount();
                    name = snap.serverName();
                } else {
                    disk = ClientCabinStorage.readCabin();
                    if (disk != null) {
                        size = disk.length;
                        hash = ClientCabinStorage.readKnownHash();
                    }
                }
                String json = "{"
                        + "\"hasCabin\":" + (size > 0)
                        + ",\"size\":" + size
                        + ",\"hash\":\"" + Long.toHexString(hash) + "\""
                        + ",\"generatedAtMs\":" + generatedAt
                        + ",\"itemCount\":" + items
                        + ",\"mobCount\":" + mobs
                        + ",\"recipeCount\":" + recipes
                        + ",\"serverName\":" + jsonStr(name)
                        + ",\"viewerVersion\":\"1.1\""
                        + "}";
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                ex.getResponseHeaders().add("Cache-Control", "no-store");
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            } catch (Throwable t) {
                ComplexityAnalyzer.LOGGER.warn("[CabinHttp] meta handler error", t);
                try {
                    sendStatus(ex, 500, "internal error");
                } catch (IOException ignored) {
                }
            }
        }
    }

    private final class CabinHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) {
            try {
                if (isAuthInvalid(ex)) return;
                byte[] data;
                CabinReceiver.CachedSnapshot snap = CabinReceiver.getInstance().getCached();
                if (snap != null) {
                    data = snap.bytes();
                } else {
                    data = ClientCabinStorage.readCabin();
                }
                if (data == null) {
                    sendStatus(ex, 404, "cabin not available");
                    return;
                }

                String rangeHeader = ex.getRequestHeaders().getFirst("Range");
                ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
                ex.getResponseHeaders().add("Accept-Ranges", "bytes");
                ex.getResponseHeaders().add("Cache-Control", "no-store");

                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    long[] range = parseRange(rangeHeader, data.length);
                    if (range == null) {
                        ex.getResponseHeaders().add("Content-Range", "bytes */" + data.length);
                        sendStatus(ex, 416, "range not satisfiable");
                        return;
                    }
                    long start = range[0];
                    long end = range[1];
                    long len = end - start + 1;
                    ex.getResponseHeaders().add("Content-Range",
                            "bytes " + start + "-" + end + "/" + data.length);
                    ex.sendResponseHeaders(206, len);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(data, (int) start, (int) len);
                    }
                } else {
                    ex.sendResponseHeaders(200, data.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(data);
                    }
                }
            } catch (Throwable t) {
                ComplexityAnalyzer.LOGGER.warn("[CabinHttp] cabin handler error", t);
                try {
                    sendStatus(ex, 500, "internal error");
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static long[] parseRange(String header, long size) {
        String rangePart = header.substring("bytes=".length()).trim();
        int comma = rangePart.indexOf(',');
        if (comma >= 0) rangePart = rangePart.substring(0, comma);
        int dash = rangePart.indexOf('-');
        if (dash < 0) return null;
        String s = rangePart.substring(0, dash).trim();
        String e = rangePart.substring(dash + 1).trim();
        long start;
        long end;
        try {
            if (s.isEmpty()) {
                long suffix = Long.parseLong(e);
                if (suffix <= 0) return null;
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(s);
                end = e.isEmpty() ? size - 1 : Long.parseLong(e);
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        if (start < 0 || end < start || start >= size) return null;
        if (end >= size) end = size - 1;
        return new long[]{start, end};
    }

    private static String jsonStr(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    public boolean openInBrowser() {
        String url = getUrl();
        if (url == null) return false;
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                    desktop.browse(java.net.URI.create(url));
                    return true;
                }
            }
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[CabinHttp] Failed to launch browser: {}", t.getMessage());
        }
        return false;
    }
}
