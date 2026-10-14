package cn.serversync;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class SyncIntegrationTest {
    @TempDir Path root;
    Path jar(Path dir, String name, String id, String content) throws IOException {
        Files.createDirectories(dir); Path p = dir.resolve(name);
        try (var out = new ZipOutputStream(Files.newOutputStream(p))) {
            out.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            out.write(("modLoader=\"javafml\"\n[[mods]] #mandatory\nmodId=\"" + id + "\" #mandatory\nversion=\"1\"\n[[dependencies." + id + "]] #optional\nmodId=\"neoforge\"\n").getBytes()); out.closeEntry();
            out.putNextEntry(new ZipEntry("content")); out.write(content.getBytes()); out.closeEntry();
        } return p;
    }
    Manifest manifest(Path p) throws IOException { return new Manifest(List.of(new Manifest.Entry("sample", p.getFileName().toString(), FilesUtil.hash(p), Files.size(p)))); }
    Path prepare(Path old, Path newer) throws IOException {
        var state = root.resolve("serversync-client"); var stage = state.resolve("stage-test"); Files.createDirectories(stage);
        Files.copy(newer, stage.resolve(newer.getFileName()));
        Files.writeString(stage.resolve("manifest.txt"), manifest(newer).encode());
        Files.writeString(stage.resolve("old.txt"), old.getFileName() + "\t" + FilesUtil.hash(old) + "\n");
        Files.writeString(state.resolve("pending"), "stage-test"); return stage;
    }
    @Test void versionUnchangedStillDetectedAndDuplicateRejected() throws Exception {
        var old = jar(root.resolve("mods"), "old.jar", "sample", "old code");
        var newer = jar(root.resolve("remote"), "new.jar", "sample", "new code");
        assertEquals(Set.of("sample"), FilesUtil.ids(old));
        assertFalse(manifest(newer).matches(root.resolve("mods")));
        Files.copy(newer, root.resolve("mods/new.jar"));
        assertFalse(manifest(newer).matches(root.resolve("mods")));
        Files.delete(old); assertTrue(manifest(newer).matches(root.resolve("mods")));
    }
    @Test void updateReplacesRenamedJarBacksUpAndPreservesClientOnly() throws Exception {
        var old = jar(root.resolve("mods"), "sample-old.jar", "sample", "old");
        var local = jar(root.resolve("mods"), "client.jar", "clientonly", "keep"); String localHash = FilesUtil.hash(local);
        var newer = jar(root.resolve("remote"), "sample-new.jar", "sample", "new");
        prepare(old, newer); UpdateHelper.apply(root);
        assertFalse(Files.exists(old)); assertEquals(localHash, FilesUtil.hash(local));
        assertTrue(manifest(newer).matches(root.resolve("mods")));
        assertFalse(Files.exists(root.resolve("serversync-client/pending")));
        try (var dirs = Files.list(root.resolve("serversync-client"))) {
            var backup = dirs.filter(p -> p.getFileName().toString().startsWith("backup-")).findFirst().orElseThrow();
            assertTrue(Files.exists(backup.resolve("sample-old.jar")));
        }
    }
    @Test void corruptDownloadDoesNotTouchInstalledJar() throws Exception {
        var old = jar(root.resolve("mods"), "old.jar", "sample", "old"); String before = FilesUtil.hash(old);
        var newer = jar(root.resolve("remote"), "new.jar", "sample", "new"); var stage = prepare(old, newer);
        Files.writeString(stage.resolve("new.jar"), "corrupt");
        assertThrows(IOException.class, () -> UpdateHelper.apply(root));
        assertEquals(before, FilesUtil.hash(old)); assertTrue(Files.exists(root.resolve("serversync-client/pending")));
    }
    @Test void changedLocalJarStopsUpdate() throws Exception {
        var old = jar(root.resolve("mods"), "old.jar", "sample", "old");
        var newer = jar(root.resolve("remote"), "new.jar", "sample", "new"); prepare(old, newer);
        Files.writeString(old, "manual edit");
        assertThrows(IOException.class, () -> UpdateHelper.apply(root)); assertEquals("manual edit", Files.readString(old));
    }
    @Test void traversalAndDuplicateManifestEntriesRejected() throws Exception {
        var p = jar(root.resolve("remote"), "new.jar", "sample", "new"); String text = manifest(p).encode();
        assertThrows(IOException.class, () -> Manifest.parse(text.replace("new.jar", "../new.jar")));
        assertThrows(IOException.class, () -> Manifest.parse(text + text.substring(13)));
        assertThrows(IOException.class, () -> Manifest.parse(text.replace("sample\t", "serversync\t")));
    }
    @Test void httpSnapshotMatchesActualJarAndSurvivesSourceChange() throws Exception {
        var p = jar(root.resolve("mods"), "sample.jar", "sample", "original");
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/serversync-server.properties"), "modIds=sample\n");
        try (var server = new SyncServer(root); var shared = new SharedPortTestServer(server)) {
            URI base = SyncClient.samePort(new InetSocketAddress("127.0.0.1", shared.port()));
            var fetched = SyncClient.fetch(base); assertEquals(server.manifest, fetched);
            Files.writeString(p, "changed source after server boot");
            byte[] bytes; try (var in = base.resolve("files/" + fetched.entries().getFirst().sha256()).toURL().openStream()) { bytes = in.readAllBytes(); }
            Path downloaded = root.resolve("download.jar"); Files.write(downloaded, bytes);
            assertEquals(fetched.entries().getFirst().sha256(), FilesUtil.hash(downloaded));
            assertThrows(IOException.class, () -> base.resolve("files/unknown").toURL().openStream());
        }
    }
    @Test void standaloneUpdaterRunsWithoutMinecraftClasspath() throws Exception {
        var old = jar(root.resolve("mods"), "same.jar", "sample", "old");
        var newer = jar(root.resolve("remote"), "same.jar", "sample", "new"); prepare(old, newer);
        String javaExe = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaExe, "-cp", System.getProperty("serversync.test.jar"), "cn.serversync.UpdateHelper", "--apply", root.toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.exitValue(), output); assertTrue(output.contains("SUCCESS"), output);
        assertTrue(manifest(newer).matches(root.resolve("mods")));
    }
    @Test void truncatedHttpDownloadDoesNotPublishPendingOrModifyMods() throws Exception {
        var old = jar(root.resolve("mods"), "old.jar", "sample", "old"); String before = FilesUtil.hash(old);
        var newer = jar(root.resolve("remote"), "new.jar", "sample", "new");
        var http = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/files/", exchange -> { try (exchange) { exchange.sendResponseHeaders(200, 3); exchange.getResponseBody().write(new byte[]{1,2,3}); } }); http.start();
        try {
            var base = SyncClient.base("http://127.0.0.1:" + http.getAddress().getPort());
            assertThrows(IOException.class, () -> SyncClient.stage(root, base, manifest(newer), newer, s -> {}));
            assertEquals(before, FilesUtil.hash(old)); assertFalse(Files.exists(root.resolve("serversync-client/pending")));
        } finally { http.stop(0); }
    }
    @Test void gameAndFragmentedHttpUseExactlyTheSameTcpPort() throws Exception {
        var p = jar(root.resolve("mods"), "sample.jar", "sample", "original");
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/serversync-server.properties"), "modIds=sample\n");
        try (var snapshot = new SyncServer(root); var server = new SharedPortTestServer(snapshot)) {
            try (var game = new Socket("127.0.0.1", server.port())) {
                game.setSoTimeout(5000);
                byte[] handshake = {0x10, 0x00, 0x01, 0x09, 0x61, 0x62, 0x63};
                game.getOutputStream().write(handshake);
                assertArrayEquals(handshake, game.getInputStream().readNBytes(handshake.length));
            }
            try (var http = new Socket("127.0.0.1", server.port())) {
                http.setSoTimeout(5000);
                http.getOutputStream().write('G'); http.getOutputStream().flush();
                http.getOutputStream().write('E'); http.getOutputStream().flush();
                http.getOutputStream().write("T /manifest HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes());
                String response = new String(http.getInputStream().readAllBytes());
                assertTrue(response.startsWith("HTTP/1.1 200"), response);
                assertTrue(response.contains(FilesUtil.hash(p)), response);
            }
            // A game packet can also begin with 'G'; a later mismatch must release the buffered prefix.
            try (var game = new Socket("127.0.0.1", server.port())) {
                game.setSoTimeout(5000); game.getOutputStream().write('G'); game.getOutputStream().flush();
                game.getOutputStream().write(new byte[]{0, 3, 4});
                assertArrayEquals(new byte[]{'G',0,3,4}, game.getInputStream().readNBytes(4));
            }
        }
    }
    @Test void samePortUrlSupportsCustomPortsAndIpv6() throws Exception {
        assertEquals("http://127.0.0.1:30123/", SyncClient.samePort(InetSocketAddress.createUnresolved("127.0.0.1",30123)).toString());
        assertEquals("http://[::1]:30123/", SyncClient.samePort(InetSocketAddress.createUnresolved("::1",30123)).toString());
    }
    @Test void monitorListReadsServerModsAndDoesNotBindAnyPort() throws Exception {
        var included = jar(root.resolve("mods"), "sample.jar", "sample", "original");
        jar(root.resolve("mods"), "serveronly.jar", "serveronly", "not distributed");
        Files.createDirectories(root.resolve("config"));
        // Obsolete port/bind values from 1.0.0 are ignored, even if the port is occupied.
        try (var occupied = new java.net.ServerSocket(0)) {
            Files.writeString(root.resolve("config/serversync-server.properties"), "modIds=sample\nport=" + occupied.getLocalPort() + "\nbind=127.0.0.1\n");
            try (var snapshot = new SyncServer(root)) {
                assertEquals(1, snapshot.manifest.entries().size());
                assertEquals(FilesUtil.hash(included), snapshot.manifest.entries().getFirst().sha256());
            }
        }
    }
    @Test void fragmentedPrefixIsBufferedThenReleasedWithoutChangingGameBytes() {
        var channel = new io.netty.channel.embedded.EmbeddedChannel(new PortMultiplexer(() -> null));
        try {
            assertFalse(channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(new byte[]{'G'})));
            assertFalse(channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(new byte[]{'E'})));
            assertTrue(channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(new byte[]{0,3})));
            io.netty.buffer.ByteBuf bytes = channel.readInbound();
            try { byte[] data = new byte[bytes.readableBytes()]; bytes.readBytes(data); assertArrayEquals(new byte[]{'G','E',0,3},data); }
            finally { bytes.release(); }
            assertNull(channel.pipeline().get(PortMultiplexer.class));
        } finally { channel.finishAndReleaseAll(); }
    }
    @Test void fragmentedHttpDoesNotReachMinecraftHandlers() {
        var gameReads = new java.util.concurrent.atomic.AtomicInteger();
        var channel = new io.netty.channel.embedded.EmbeddedChannel(new PortMultiplexer(() -> null), new io.netty.channel.ChannelInboundHandlerAdapter() {
            public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) { gameReads.incrementAndGet(); io.netty.util.ReferenceCountUtil.release(msg); }
        });
        try {
            channel.writeInbound(io.netty.buffer.Unpooled.copiedBuffer("G",java.nio.charset.StandardCharsets.US_ASCII));
            channel.writeInbound(io.netty.buffer.Unpooled.copiedBuffer("E",java.nio.charset.StandardCharsets.US_ASCII));
            channel.writeInbound(io.netty.buffer.Unpooled.copiedBuffer("T /manifest HTTP/1.1\r\nHost: localhost\r\n\r\n",java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals(0,gameReads.get());
            io.netty.buffer.ByteBuf response = channel.readOutbound();
            try { assertTrue(response.toString(java.nio.charset.StandardCharsets.US_ASCII).startsWith("HTTP/1.1 503")); }
            finally { response.release(); }
        } finally { channel.finishAndReleaseAll(); }
    }
}
