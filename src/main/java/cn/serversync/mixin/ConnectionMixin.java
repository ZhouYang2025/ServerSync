package cn.serversync.mixin;

import cn.serversync.PortMultiplexer;
import cn.serversync.ServerSync;

import io.netty.channel.ChannelPipeline;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(method = "configureSerialization", at = @At("RETURN"))
    private static void serversync$sharedPort(ChannelPipeline pipeline, PacketFlow flow, boolean memory, BandwidthDebugMonitor monitor, CallbackInfo ci) {
        if (flow == PacketFlow.SERVERBOUND && !memory) {
            pipeline.addFirst("serversync_sniffer", new PortMultiplexer(() -> ServerSync.server));
        }
    }
}
