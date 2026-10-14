package cn.serversync;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.stream.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Supplier;

/** Classifies the first request without consuming Minecraft handshake bytes. */
public final class PortMultiplexer extends ByteToMessageDecoder {
    private static final byte[] GET = {'G', 'E', 'T', ' '};
    private final Supplier<SyncServer> server;
    public PortMultiplexer(Supplier<SyncServer> server) { this.server = server; }
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int available = Math.min(in.readableBytes(), GET.length);
        for (int i = 0; i < available; i++) {
            if (in.getByte(in.readerIndex() + i) != GET[i]) {
                ctx.pipeline().remove(this); // Decoder removal forwards all buffered bytes unchanged.
                return;
            }
        }
        if (available < GET.length) return;
        var pipeline = ctx.pipeline();
        // Replace only this HTTP connection's Minecraft handlers, before any MC frame decoder runs.
        for (String name : new ArrayList<>(pipeline.toMap().keySet())) {
            if (!name.equals(ctx.name()) && !name.equals("timeout")) pipeline.remove(name);
        }
        pipeline.addLast("serversync_stream", new ChunkedWriteHandler());
        pipeline.addLast("serversync_response", new Responses(server));
        pipeline.remove(this);
    }
    /** Single bounded GET request, fixed-length response, then close; no keepalive or request bodies. */
    private static final class Responses extends ByteToMessageDecoder {
        private final Supplier<SyncServer> server;
        private boolean responded;
        Responses(Supplier<SyncServer> server) { this.server = server; }
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            if (responded) { in.skipBytes(in.readableBytes()); return; }
            if (in.readableBytes() > 8192) { responded = true; in.skipBytes(in.readableBytes()); text(ctx, 431, "Request Header Fields Too Large", "Request too large"); return; }
            int end = -1;
            for (int i = in.readerIndex(); i + 3 < in.writerIndex(); i++) {
                if (in.getByte(i) == '\r' && in.getByte(i+1) == '\n' && in.getByte(i+2) == '\r' && in.getByte(i+3) == '\n') { end = i+4; break; }
            }
            if (end < 0) return;
            String header = in.toString(in.readerIndex(), end-in.readerIndex(), StandardCharsets.US_ASCII);
            in.skipBytes(in.readableBytes());
            responded = true;
            String[] request = header.substring(0, header.indexOf("\r\n")).split(" ", -1);
            SyncServer snapshot = server.get();
            if (request.length != 3 || !request[0].equals("GET") || !(request[2].equals("HTTP/1.1") || request[2].equals("HTTP/1.0"))) { text(ctx, 400, "Bad Request", "Invalid request"); return; }
            if (snapshot == null) { text(ctx, 503, "Service Unavailable", "Server starting or stopping"); return; }
            if (request[1].equals("/manifest")) { text(ctx, 200, "OK", snapshot.manifest.encode()); return; }
            if (!request[1].matches("/files/[0-9a-f]{64}")) { text(ctx, 404, "Not Found", "Not found"); return; }
            var file = snapshot.blob(request[1].substring(7));
            if (file == null) { text(ctx, 404, "Not Found", "Not found"); return; }
            // ChunkedWriteHandler applies Netty backpressure rather than loading a jar into memory.
            var stream = new ChunkedNioFile(file.toFile(), 65536);
            ctx.write(headers(ctx, 200, "OK", "application/java-archive", Files.size(file)));
            ctx.writeAndFlush(stream).addListener(ChannelFutureListener.CLOSE);
        }
        private static ByteBuf headers(ChannelHandlerContext ctx, int status, String reason, String contentType, long length) {
            return io.netty.buffer.Unpooled.copiedBuffer("HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + contentType + "\r\nContent-Length: " + length + "\r\nConnection: close\r\n\r\n", StandardCharsets.US_ASCII);
        }
        private static void text(ChannelHandlerContext ctx, int status, String reason, String text) {
            var content = io.netty.buffer.Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
            ctx.write(headers(ctx, status, reason, "text/plain; charset=utf-8", content.readableBytes()));
            ctx.writeAndFlush(content).addListener(ChannelFutureListener.CLOSE);
        }
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
    }
}
