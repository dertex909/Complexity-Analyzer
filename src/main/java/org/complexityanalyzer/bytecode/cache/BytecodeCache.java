package org.complexityanalyzer.bytecode.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BytecodeCache {
    private final ConcurrentHashMap<String, String> classHashes;

    public BytecodeCache() {
        this.classHashes = new ConcurrentHashMap<>();
    }

    public BytecodeCache(Map<String, String> hashes) {
        this.classHashes = new ConcurrentHashMap<>(hashes);
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
        return new ConcurrentHashMap<>(classHashes);
    }

    public void clear() {
        classHashes.clear();
    }
}