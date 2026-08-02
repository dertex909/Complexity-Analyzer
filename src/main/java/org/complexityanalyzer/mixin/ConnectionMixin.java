package org.complexityanalyzer.mixin;

import io.netty.channel.ChannelPipeline;
import net.minecraft.network.BandwidthDebugMonitor;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.complexityanalyzer.network.web.ProtocolDetector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Inject(method = "configureSerialization", at = @At("HEAD"))
    private static void onConfigureSerialization(ChannelPipeline pipeline, PacketFlow flow, boolean isClient, BandwidthDebugMonitor monitor, CallbackInfo ci) {
        if (flow == PacketFlow.SERVERBOUND && !isClient) {
            pipeline.addFirst("complexity_multiplexer", new ProtocolDetector());
        }
    }
}