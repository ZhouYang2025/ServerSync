package cn.serversync;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.nio.file.Files;
import java.util.concurrent.*;

public final class SyncScreen extends Screen {
    public static boolean bypass;
    private final Screen parent;
    private final Runnable connect;
    private final net.minecraft.client.multiplayer.resolver.ServerAddress address;
    private volatile String status = "正在检查模组，请稍候…";
    private volatile boolean restart;
    private volatile boolean busy = true;
    public SyncScreen(Screen parent, Runnable connect, net.minecraft.client.multiplayer.resolver.ServerAddress address) {
        super(Component.literal("ServerSync 模组同步")); this.parent = parent; this.connect = connect; this.address = address;
    }
    public void begin() {
        CompletableFuture.runAsync(() -> {
            try {
                if (ServerSync.PENDING_AT_BOOT || Files.exists(ServerSync.ROOT.resolve("serversync-client/pending"))) {
                    restart = true; status = "更新尚待应用或本次启动过早。请退出游戏，等待 updater.log 出现 SUCCESS 后再启动。"; return;
                }
                var endpoint = net.minecraft.client.multiplayer.resolver.ServerNameResolver.DEFAULT.resolveAddress(address)
                    .orElseThrow(() -> new java.io.IOException("无法解析服务器地址"));
                var base = SyncClient.samePort(endpoint.asInetSocketAddress());
                Manifest manifest;
                try { manifest = SyncClient.fetch(base); }
                catch (java.io.IOException unavailable) {
                    // Ordinary servers do not implement HTTP. The configuration gate still protects ServerSync servers.
                    continueConnection(); return;
                }
                if (ServerSync.loadedMatches(manifest)) {
                    continueConnection(); return;
                }
                var ownJar = net.neoforged.fml.ModList.get().getModFileById("serversync").getFile().getFilePath();
                SyncClient.stage(ServerSync.ROOT, base, manifest, ownJar, text -> status = text);
                restart = true;
                status = "更新已下载并校验。请点击退出游戏，等待数秒后重新启动。未重启不可加入服务器。";
            } catch (Exception e) { status = "同步失败，已阻止连接：" + e.getMessage(); }
            finally { busy = false; Minecraft.getInstance().execute(() -> { if (Minecraft.getInstance().screen == this) rebuildWidgets(); }); }
        });
    }
    private void continueConnection() { Minecraft.getInstance().execute(() -> { if (Minecraft.getInstance().screen == this) connect.run(); }); }
    protected void init() {
        if (!busy) addRenderableWidget(Button.builder(Component.literal(restart ? "退出游戏并应用更新" : "返回服务器列表"), b -> {
            if (restart) minecraft.stop(); else minecraft.setScreen(parent);
        }).bounds(width / 2 - 110, height - 60, 220, 20).build());
    }
    public boolean shouldCloseOnEsc() { return !busy; }
    public void onClose() { if (!busy) minecraft.setScreen(parent); }
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 35, 0xFFFFFF);
        int y = 70;
        for (var line : font.split(Component.literal(status), Math.max(100, width - 60))) { graphics.drawCenteredString(font, line, width / 2, y, 0xFFFFFF); y += 12; }
    }
}
