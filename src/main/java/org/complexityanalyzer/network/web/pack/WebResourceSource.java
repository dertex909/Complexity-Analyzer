package org.complexityanalyzer.network.web.pack;

import org.complexityanalyzer.util.FileManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

public sealed interface WebResourceSource permits WebResourceSource.PathSource, WebResourceSource.ZipFileSource {

    byte[] read(String subpath);

    final class PathSource implements WebResourceSource {
        private final Path root;

        public PathSource(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        @Override
        public byte[] read(String subpath) {
            try {
                var clean = WebPackPaths.cleanPath(subpath);
                if (clean.isEmpty()) return null;
                var target = this.root.resolve(clean).normalize();
                if (!target.startsWith(this.root) || !FileManager.isRegularFile(target)) return null;
                return Files.readAllBytes(target);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    final class ZipFileSource implements WebResourceSource {
        private final Path zipPath;

        public ZipFileSource(Path zipPath) {
            this.zipPath = zipPath.toAbsolutePath().normalize();
        }

        @Override
        public byte[] read(String subpath) {
            var clean = WebPackPaths.cleanPath(subpath);
            if (clean.isEmpty() || !FileManager.isRegularFile(this.zipPath)) return null;
            try (var zip = new ZipFile(this.zipPath.toFile())) {
                var entry = zip.getEntry(clean);
                if (entry == null || entry.isDirectory()) return null;
                try (var in = zip.getInputStream(entry)) {
                    return in.readAllBytes();
                }
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}