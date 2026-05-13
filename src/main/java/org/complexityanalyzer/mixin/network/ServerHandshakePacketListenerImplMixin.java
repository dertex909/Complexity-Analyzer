package org.complexityanalyzer.mixin.network;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.complexityanalyzer.network.multiplex.ConnectionAddressHolder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerHandshakePacketListenerImpl.class)
public class ServerHandshakePacketListenerImplMixin {
    @Shadow
    @Final
    private Connection connection;

    @Inject(method = "handleIntention", at = @At("HEAD"))
    private void complexity$onHandleIntention(ClientIntentionPacket packet, CallbackInfo ci) {
        if (connection instanceof ConnectionAddressHolder holder) holder.complexity$setHostname(packet.hostName());
    }
}