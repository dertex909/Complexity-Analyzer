package org.complexityanalyzer.bytecode.cache;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.util.Map;

public final class BytecodeCache {
    private final Map<String, String> classHashes;

    public BytecodeCache() {
        this.classHashes = new Object2ObjectOpenHashMap<>();
    }

    public BytecodeCache(Map<String, String> hashes) {
        this.classHashes = new Object2ObjectOpenHashMap<>(hashes);
    }

    public void put(String className, String hash) {
        classHashes.put(className, hash);
    }

    public String get(String className) {
        return classHashes.get(className);
    }

    public boolean contains(String className) {
        return classHashes.containsKey(className);
    }

    public boolean isChanged(String className, String newHash) {
        String old = classHashes.get(className);
        return old == null || !old.equals(newHash);
    }

    public int size() {
        return classHashes.size();
    }

    public Map<String, String> getAll() {
        return new Object2ObjectOpenHashMap<>(classHashes);
    }

    public void clear() {
        classHashes.clear();
    }
}