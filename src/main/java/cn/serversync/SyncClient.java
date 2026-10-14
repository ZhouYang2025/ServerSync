package cn.serversync;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

public final class SyncClient {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    public static URI samePort(InetSocketAddress endpoint) throws IOException {
        try { return new URI("http", null, endpoint.getHostString(), endpoint.getPort(), "/", null, null); }
        catch (URISyntaxException e) { throw new IOException(e); }
    }
    public static Manifest fetch(URI base) throws IOException, InterruptedException {
        var response = HTTP.send(HttpRequest.newBuilder(base.resolve("manifest")).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var in = response.body()) {
            if (response.statusCode() != 200) throw new IOException("Manifest HTTP " + response.statusCode());
            byte[] data = in.readNBytes(262145);
            if (data.length > 262144) throw new IOException("Manifest too large");
            return Manifest.parse(new String(data, java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    public static URI base(String value) throws IOException {
        try {
            var uri = URI.create(value.endsWith("/") ? value : value + "/");
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new IOException("Invalid trusted URL");
            return uri;
        } catch (IllegalArgumentException e) { throw new IOException("Invalid trusted URL", e); }
    }
    public static void stage(Path root, URI base, Manifest manifest, Path own, Consumer<String> status) throws Exception {
        Path state = root.resolve("serversync-client"); Files.createDirectories(state);
        if (Files.exists(state.resolve("pending"))) throw new IOException("An update is pending; exit Minecraft and wait for updater.log");
        Path stage = Files.createTempDirectory(state, "stage-");
        Files.writeString(stage.resolve("manifest.txt"), manifest.encode());
        var old = new TreeSet<String>(); var managedIds = new HashSet<String>();
        for (var entry : manifest.entries()) managedIds.add(entry.id());
        for (var jar : FilesUtil.jars(root.resolve("mods"))) {
            var ids = FilesUtil.ids(jar);
            if (!Collections.disjoint(ids, managedIds)) {
                if (ids.contains("serversync")) throw new IOException("Cannot replace sync mod");
                old.add(jar.getFileName().toString());
            }
        }
        var plan = new StringBuilder();
        for (String name : old) plan.append(name).append('\t').append(FilesUtil.hash(FilesUtil.child(root.resolve("mods"), name))).append('\n');
        Files.writeString(stage.resolve("old.txt"), plan.toString());
        for (var entry : manifest.entries()) {
            Path target = FilesUtil.child(root.resolve("mods"), entry.name());
            if (Files.exists(target) && !old.contains(entry.name())) throw new IOException("Unrelated filename collision: " + entry.name());
            status.accept("下载 " + entry.name());
            var response = HTTP.send(HttpRequest.newBuilder(base.resolve("files/" + entry.sha256())).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            Path download = FilesUtil.child(stage, entry.name());
            try (var in = response.body(); var out = Files.newOutputStream(download, StandardOpenOption.CREATE_NEW)) {
                if (response.statusCode() != 200) throw new IOException("Download HTTP " + response.statusCode());
                byte[] buf = new byte[65536]; long count = 0; long nextReport = 0; int n;
                while ((n = in.read(buf)) != -1) {
                    if ((count += n) > entry.size()) throw new IOException("Download exceeded declared size");
                    out.write(buf, 0, n);
                    if (System.nanoTime() >= nextReport) {
                        status.accept("下载 " + entry.name() + " (" + (count * 100 / entry.size()) + "%)");
                        nextReport = System.nanoTime() + 200_000_000L;
                    }
                }
                if (count != entry.size()) throw new IOException("Truncated download");
            }
            if (!FilesUtil.hash(download).equals(entry.sha256()) || !FilesUtil.ids(download).contains(entry.id()) || FilesUtil.ids(download).contains("serversync")) throw new IOException("Jar verification failed");
        }
        // The helper has no Minecraft/NeoForge dependencies and runs from a separate jar copy.
        if (!Files.isRegularFile(own)) throw new IOException("Updater requires packaged jar (not development classes)");
        Path helper = state.resolve("updater.jar"); Files.copy(own, helper, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(state.resolve("pending"), stage.getFileName().toString(), StandardOpenOption.CREATE_NEW);
        try {
            String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java").toString();
            new ProcessBuilder(java, "-cp", helper.toAbsolutePath().toString(), UpdateHelper.class.getName(), root.toAbsolutePath().toString(), Long.toString(ProcessHandle.current().pid()))
                .redirectErrorStream(true).redirectOutput(state.resolve("updater.log").toFile()).start();
        } catch (Exception ex) { Files.deleteIfExists(state.resolve("pending")); throw ex; }
    }
}
