package org.complexityanalyzer.bytecode.graph;

import java.util.Objects;

public record MethodRef(String owner, String name, String descriptor) {

    public MethodRef {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
    }

    public String internalName() {
        return owner + "." + name + descriptor;
    }

    @Override
    public String toString() {
        return internalName();
    }
}
