package cn.serversync.mixin;

import cn.serversync.SyncScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public abstract class ConnectMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void serversync$connect(Screen parent, Minecraft mc, ServerAddress address, ServerData data, boolean quickPlay, net.minecraft.client.multiplayer.TransferState transfer, CallbackInfo ci) {
        if (SyncScreen.bypass) return;
        ci.cancel();
        var screen = new SyncScreen(parent, () -> {
            SyncScreen.bypass = true;
            try { ConnectScreen.startConnecting(parent, mc, address, data, quickPlay, transfer); }
            finally { SyncScreen.bypass = false; }
        }, address);
        mc.setScreen(screen);
        screen.begin();
    }
}
