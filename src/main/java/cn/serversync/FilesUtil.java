package cn.serversync;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipFile;

public final class FilesUtil {
    public static String hash(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(path)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static Path child(Path dir, String name) throws IOException {
        if (!name.matches("[a-zA-Z0-9_.+ -]+\\.jar") || name.contains("..")) throw new IOException("Invalid jar filename: " + name);
        Path p = dir.resolve(name);
        if (Files.isSymbolicLink(dir) || Files.isSymbolicLink(p)) throw new IOException("Symlink not permitted: " + p);
        return p;
    }
    public static Set<String> ids(Path jar) throws IOException {
        var result = new TreeSet<String>();
        try (var zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("META-INF/neoforge.mods.toml");
            if (entry == null) entry = zip.getEntry("META-INF/mods.toml");
            if (entry == null) return result;
            String text;
            try (var in = zip.getInputStream(entry)) { text = new String(in.readNBytes(1048576), java.nio.charset.StandardCharsets.UTF_8); }
            boolean mods = false;
            for (String line : text.split("\\R")) {
                String s = line.strip();
                if (s.startsWith("[")) mods = s.matches("\\[\\[\\s*mods\\s*\\]\\]\\s*(#.*)?");
                if (mods) {
                    var m = Pattern.compile("^modId\\s*=\\s*[\"']([a-z][a-z0-9_]+)[\"'].*$").matcher(s);
                    if (m.matches()) result.add(m.group(1));
                }
            }
        }
        return result;
    }
    public static List<Path> jars(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
        }
    }
    public static Properties properties(Path path, String defaults) throws IOException {
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) Files.writeString(path, defaults);
        var p = new Properties(); try (var r = Files.newBufferedReader(path)) { p.load(r); } return p;
    }
}
