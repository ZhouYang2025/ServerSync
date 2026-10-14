package cn.serversync;

import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.fml.loading.FMLPaths;
import java.nio.file.*;
import java.util.*;

@Mod("serversync")
public final class ServerSync {
    public static final Path ROOT = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
    public static final Map<String,String> LOADED = new HashMap<>();
    public static final boolean PENDING_AT_BOOT = Files.exists(ROOT.resolve("serversync-client/pending"));
    public static volatile SyncServer server;
    public ServerSync(IEventBus bus) {
        try {
            for (var jar : FilesUtil.jars(ROOT.resolve("mods"))) LOADED.put(jar.getFileName().toString(), FilesUtil.hash(jar));
        } catch (Exception e) { throw new IllegalStateException("ServerSync initialization failed", e); }
        bus.addListener(JoinGate::register);
        bus.addListener(JoinGate::tasks);
        NeoForge.EVENT_BUS.addListener(this::start);
        NeoForge.EVENT_BUS.addListener(this::stop);
    }
    private void start(ServerStartingEvent event) {
        if (!event.getServer().isDedicatedServer()) return;
        try {
            server = new SyncServer(ROOT);
            for (var entry : server.manifest.entries()) {
                if (!entry.sha256().equals(LOADED.get(entry.name()))) {
                    server.close(); server = null;
                    throw new java.io.IOException("Server mod files changed after loading. Restart the server before publishing updates.");
                }
            }
        }
        catch (Exception e) { throw new IllegalStateException("ServerSync snapshot failed. Check monitored mod IDs in config/serversync-server.properties.", e); }
    }
    private void stop(ServerStoppedEvent event) { if (server != null) { server.close(); server = null; } }
    public static boolean loadedMatches(Manifest manifest) throws java.io.IOException {
        if (PENDING_AT_BOOT || Files.exists(ROOT.resolve("serversync-client/pending")) || !manifest.matches(ROOT.resolve("mods"))) return false;
        for (var e : manifest.entries()) {
            boolean found = false;
            for (var jar : FilesUtil.jars(ROOT.resolve("mods"))) {
                if (FilesUtil.ids(jar).contains(e.id()) && e.sha256().equals(LOADED.get(jar.getFileName().toString()))) found = true;
            }
            if (!found) return false;
        }
        return true;
    }
}
