package cn.serversync;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Standalone Java helper: safe to execute without Minecraft's classpath. */
public final class UpdateHelper {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--apply")) {
            apply(Path.of(args[1]).toAbsolutePath().normalize());
            System.out.println("SUCCESS. You can start Minecraft now."); return;
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        long pid = Long.parseLong(args[1]);
        System.out.println("Waiting for Minecraft process " + pid + " to exit...");
        var process = ProcessHandle.of(pid);
        if (process.isPresent()) process.get().onExit().join();
        try { apply(root); System.out.println("SUCCESS. You can start Minecraft now."); }
        catch (Exception ex) { ex.printStackTrace(); System.exit(1); }
    }
    public static void apply(Path root) throws IOException {
        Path state = root.resolve("serversync-client"), mods = root.resolve("mods");
        try (var channel = FileChannel.open(state.resolve("update.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.lock()) {
            String stageName = Files.readString(state.resolve("pending")).strip();
            if (!stageName.matches("stage-[a-zA-Z0-9-]+")) throw new IOException("Invalid stage directory");
            Path stage = state.resolve(stageName);
            if (Files.isSymbolicLink(state) || Files.isSymbolicLink(stage) || Files.isSymbolicLink(mods)) throw new IOException("Symlink update directory");
            var manifest = Manifest.parse(Files.readString(stage.resolve("manifest.txt")));
            var old = new LinkedHashMap<String,String>();
            for (String line : Files.readAllLines(stage.resolve("old.txt"))) {
                var f = line.split("\t");
                if (f.length != 2 || !f[1].matches("[0-9a-f]{64}")) throw new IOException("Invalid old file plan");
                Path path = FilesUtil.child(mods, f[0]);
                if (!FilesUtil.hash(path).equals(f[1])) throw new IOException("Local mods changed during update: " + f[0]);
                old.put(f[0], f[1]);
            }
            for (var e : manifest.entries()) {
                var file = FilesUtil.child(stage, e.name());
                if (Files.size(file) != e.size() || !FilesUtil.hash(file).equals(e.sha256()) || !FilesUtil.ids(file).contains(e.id()) || FilesUtil.ids(file).contains("serversync")) throw new IOException("Staged verification failed");
                if (Files.exists(FilesUtil.child(mods, e.name())) && !old.containsKey(e.name())) throw new IOException("Destination collision");
            }
            Path backup = Files.createTempDirectory(state, "backup-");
            Files.writeString(backup.resolve("manifest.txt"), manifest.encode());
            var moved = new ArrayList<String>(); var installed = new ArrayList<String>();
            try {
                for (String name : old.keySet()) { Files.move(FilesUtil.child(mods, name), FilesUtil.child(backup, name)); moved.add(name); }
                for (var e : manifest.entries()) { Files.copy(FilesUtil.child(stage, e.name()), FilesUtil.child(mods, e.name())); installed.add(e.name()); }
                if (!manifest.matches(mods)) throw new IOException("Post-update verification failed");
                Files.delete(state.resolve("pending"));
            } catch (IOException ex) {
                for (String name : installed) try { Files.deleteIfExists(FilesUtil.child(mods, name)); } catch (IOException rollback) { ex.addSuppressed(rollback); }
                for (String name : moved) try { Files.move(FilesUtil.child(backup, name), FilesUtil.child(mods, name)); } catch (IOException rollback) { ex.addSuppressed(rollback); }
                throw ex;
            }
        }
    }
}
