package cn.serversync;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public record Manifest(List<Entry> entries) {
    public static final long MAX_JAR = 512L * 1024 * 1024;
    public record Entry(String id, String name, String sha256, long size) {}
    public Manifest { entries = List.copyOf(entries); }
    public String encode() {
        var s = new StringBuilder("serversync-1\n");
        for (var e : entries) s.append(e.id()).append('\t').append(e.name()).append('\t').append(e.sha256()).append('\t').append(e.size()).append('\n');
        return s.toString();
    }
    public static Manifest parse(String text) throws IOException {
        if (text.length() > 262144 || !text.startsWith("serversync-1\n")) throw new IOException("Invalid manifest");
        var entries = new ArrayList<Entry>(); var ids = new HashSet<String>(); var names = new HashSet<String>();
        long total = 0;
        for (String line : text.substring(13).split("\n")) {
            if (line.isBlank()) continue;
            var f = line.split("\t", -1);
            if (f.length != 4 || !f[0].matches("[a-z][a-z0-9_]{1,63}") || f[0].equals("serversync") || !f[2].matches("[0-9a-f]{64}")) throw new IOException("Invalid entry");
            FilesUtil.child(Path.of("."), f[1]);
            long size; try { size = Long.parseLong(f[3]); } catch (NumberFormatException ex) { throw new IOException(ex); }
            if (size <= 0 || size > MAX_JAR || !ids.add(f[0]) || !names.add(f[1]) || entries.size() >= 512 || (total += size) > 4L * 1024 * 1024 * 1024) throw new IOException("Manifest limits exceeded");
            entries.add(new Entry(f[0], f[1], f[2], size));
        }
        return new Manifest(entries);
    }
    public String digest() {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(encode().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }
    public boolean matches(Path mods) throws IOException {
        var jars = FilesUtil.jars(mods);
        for (var e : entries) {
            var candidates = new ArrayList<Path>();
            for (var jar : jars) if (FilesUtil.ids(jar).contains(e.id())) candidates.add(jar);
            if (candidates.size() != 1 || !FilesUtil.hash(candidates.getFirst()).equals(e.sha256())) return false;
        }
        return true;
    }
}
