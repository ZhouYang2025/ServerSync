package cn.serversync;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class SyncServer implements AutoCloseable {
    public final Manifest manifest;
    private final Map<String,Path> blobs;
    public SyncServer(Path root) throws IOException {
        var config = FilesUtil.properties(root.resolve("config/serversync-server.properties"), "# Monitor these mod IDs in the server mods directory. Restart server after editing.\nmodIds=create_mobile_packages\n");
        var entries = new ArrayList<Manifest.Entry>(); var blobs = new HashMap<String,Path>();
        var cache = root.resolve("serversync-server/blobs"); Files.createDirectories(cache);
        for (var id : config.getProperty("modIds", "").split(",")) {
            id = id.strip(); if (id.isEmpty()) continue;
            if (id.equals("serversync")) throw new IOException("ServerSync itself must be updated manually");
            var candidates = new ArrayList<Path>();
            for (var jar : FilesUtil.jars(root.resolve("mods"))) if (FilesUtil.ids(jar).contains(id)) candidates.add(jar);
            if (candidates.size() != 1) throw new IOException("Expected one installed jar for " + id + ", found " + candidates.size());
            var source = candidates.getFirst(); String hash = FilesUtil.hash(source);
            var blob = cache.resolve(hash + ".jar");
            Files.copy(source, blob, StandardCopyOption.REPLACE_EXISTING);
            if (!FilesUtil.hash(blob).equals(hash)) throw new IOException("Jar changed while snapshotting");
            entries.add(new Manifest.Entry(id, source.getFileName().toString(), hash, Files.size(blob))); blobs.put(hash, blob);
        }
        entries.sort(Comparator.comparing(Manifest.Entry::id));
        // Also validates filenames, duplicate bundled jars and resource limits.
        manifest = Manifest.parse(new Manifest(entries).encode());
        this.blobs = Map.copyOf(blobs);
    }
    public Path blob(String hash) { return blobs.get(hash); }
    public void close() { /* Minecraft owns the shared listener and its lifetime. */ }
}
