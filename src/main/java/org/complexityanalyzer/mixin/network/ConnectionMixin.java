package org.complexityanalyzer.mixin.network;

import net.minecraft.network.Connection;
import org.complexityanalyzer.network.multiplex.ConnectionAddressHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Connection.class)
public class ConnectionMixin implements ConnectionAddressHolder {
    @Unique
    private String complexity$hostname;

    @Override
    public void complexity$setHostname(String hostname) {
        this.complexity$hostname = hostname;
    }

    @Override
    public String complexity$getHostname() {
        return complexity$hostname;
    }
}