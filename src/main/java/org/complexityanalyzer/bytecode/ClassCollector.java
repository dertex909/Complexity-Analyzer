package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.*;
import net.neoforged.fml.ModList;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.ThreadPoolManager;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class ClassCollector {

    private static final String[] SKIP_PREFIXES = {
            "net/minecraft/", "net/neoforged/", "com/mojang/",
            "it/unimi/", "org/apache/", "com/google/",
            "org/slf4j/", "org/objectweb/asm/", "org/jetbrains/",
            "org/lwjgl/", "jdk/", "java/", "javax/", "sun/"
    };

    private static final String[] SKIP_SUFFIXES = {
            "/client/", "/gui/", "/render/", "/debug/",
            "/test/", "package-info", "module-info"
    };

    private ClassCollector() {
    }

    public static Map<String, byte[]> collectAll(ModList modList) {
        var allClasses = new ConcurrentHashMap<String, byte[]>();
        var total = new AtomicInteger(0);
        var failed = new AtomicInteger(0);

        var mods = new ObjectArrayList<>(modList.getMods());
        ComplexityAnalyzer.LOGGER.info("[ClassCollector] Scanning {} mods...", mods.size());

        var executor = ThreadPoolManager.getInstance().getForkJoinPool();
        try {
            executor.submit(() -> mods.parallelStream().forEach(mod -> {
                try {
                    var f = mod.getOwningFile();
                    if (f == null || f.getFile() == null) return;
                    var mc = collectFromJar(f.getFile().getFilePath());
                    total.addAndGet(mc.size());
                    allClasses.putAll(mc);
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
            })).get();
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[ClassCollector] Failed", e);
        }

        ComplexityAnalyzer.LOGGER.info("[ClassCollector] {} classes, {} failed mods", allClasses.size(), failed.get());
        return allClasses;
    }

    private static Map<String, byte[]> collectFromJar(Path jarPath) {
        var classes = new Object2ObjectOpenHashMap<String, byte[]>();
        if (!Files.exists(jarPath)) return classes;

        try (var jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var e = entries.nextElement();
                String name = e.getName();
                if (!name.endsWith(".class") || shouldSkip(name)) continue;
                byte[] bc = readBytes(jar, e);
                if (bc != null) classes.put(name.substring(0, name.length() - 6), bc);
            }
        } catch (Exception ignored) {
        }
        return classes;
    }

    static boolean shouldSkip(String name) {
        for (var p : SKIP_PREFIXES) if (name.startsWith(p)) return true;
        for (var s : SKIP_SUFFIXES) if (name.contains(s)) return true;
        return false;
    }

    private static byte[] readBytes(JarFile jar, JarEntry entry) {
        try (var in = jar.getInputStream(entry); var out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}