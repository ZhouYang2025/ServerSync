package cn.serversync;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ConfigurationTask;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.*;
import net.neoforged.neoforge.network.handling.*;
import net.neoforged.neoforge.network.registration.HandlerThread;
import java.util.function.Consumer;

public final class JoinGate {
    public static final ConfigurationTask.Type TASK = new ConfigurationTask.Type(ResourceLocation.fromNamespaceAndPath("serversync", "verify"));
    public record Challenge(String text) implements CustomPacketPayload {
        static final Type<Challenge> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("serversync", "manifest"));
        static final StreamCodec<ByteBuf, Challenge> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(262144), Challenge::text, Challenge::new);
        public Type<Challenge> type() { return TYPE; }
    }
    public record Ack(String digest) implements CustomPacketPayload {
        static final Type<Ack> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("serversync", "ack"));
        static final StreamCodec<ByteBuf, Ack> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(64), Ack::digest, Ack::new);
        public Type<Ack> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional().executesOn(HandlerThread.NETWORK);
        registrar.configurationToClient(Challenge.TYPE, Challenge.CODEC, (payload, context) -> {
            try {
                var manifest = Manifest.parse(payload.text());
                if (!ServerSync.loadedMatches(manifest)) { context.disconnect(Component.literal("模组不一致或尚未重启。请重新连接以同步服务端模组；更新后必须退出并重启游戏。")); return; }
                context.reply(new Ack(manifest.digest()));
            } catch (Exception e) { context.disconnect(Component.literal("ServerSync 校验失败：" + e.getMessage())); }
        });
        registrar.configurationToServer(Ack.TYPE, Ack.CODEC, (payload, context) -> {
            if (ServerSync.server == null || !ServerSync.server.manifest.digest().equals(payload.digest())) { context.disconnect(Component.literal("ServerSync: 模组校验不一致，拒绝加入。")); return; }
            context.enqueueWork(() -> context.finishCurrentTask(TASK));
        });
    }
    public static void tasks(RegisterConfigurationTasksEvent event) {
        if (ServerSync.server == null) return;
        if (!event.getListener().hasChannel(Challenge.TYPE) || !event.getListener().hasChannel(Ack.TYPE)) {
            event.getListener().disconnect(Component.literal("此服务器需要 ServerSync。请先安装同步模组。"));
            return;
        }
        event.register(new ICustomConfigurationTask() {
            public void run(Consumer<CustomPacketPayload> sender) { sender.accept(new Challenge(ServerSync.server.manifest.encode())); }
            public ConfigurationTask.Type type() { return TASK; }
        });
    }
}
