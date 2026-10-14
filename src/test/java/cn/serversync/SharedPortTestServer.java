package cn.serversync;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.net.InetSocketAddress;

/** One real TCP listener for HTTP distribution and a recognizable game-protocol stub. */
final class SharedPortTestServer implements AutoCloseable {
    private final NioEventLoopGroup group = new NioEventLoopGroup(1);
    private final Channel channel;
    SharedPortTestServer(SyncServer snapshot) throws InterruptedException {
        channel = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                protected void initChannel(SocketChannel socket) {
                    socket.pipeline().addLast("timeout", new ReadTimeoutHandler(10));
                    socket.pipeline().addLast("serversync_sniffer", new PortMultiplexer(() -> snapshot));
                    socket.pipeline().addLast("game_stub", new SimpleChannelInboundHandler<ByteBuf>() {
                        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf bytes) {
                            ctx.writeAndFlush(bytes.retainedDuplicate()); // Tests expect untouched game bytes.
                        }
                    });
                }
            }).bind("127.0.0.1", 0).sync().channel();
    }
    int port() { return ((InetSocketAddress)channel.localAddress()).getPort(); }
    public void close() { channel.close().syncUninterruptibly(); group.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly(); }
}
