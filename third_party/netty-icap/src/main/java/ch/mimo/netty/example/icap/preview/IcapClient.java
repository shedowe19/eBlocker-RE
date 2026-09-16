/*******************************************************************************
 * Copyright 2012 Michael Mimo Moratti
 * Modifications Copyright (c) 2018 eBlocker GmbH
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package ch.mimo.netty.example.icap.preview;

import java.net.InetSocketAddress;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;

import ch.mimo.netty.handler.codec.icap.DefaultIcapChunk;
import ch.mimo.netty.handler.codec.icap.DefaultIcapChunkTrailer;
import ch.mimo.netty.handler.codec.icap.DefaultIcapRequest;
import ch.mimo.netty.handler.codec.icap.IcapChunk;
import ch.mimo.netty.handler.codec.icap.IcapChunkTrailer;
import ch.mimo.netty.handler.codec.icap.IcapHeaders;
import ch.mimo.netty.handler.codec.icap.IcapMessageElementEnum;
import ch.mimo.netty.handler.codec.icap.IcapMethod;
import ch.mimo.netty.handler.codec.icap.IcapRequest;
import ch.mimo.netty.handler.codec.icap.IcapVersion;

/**
 * Preview capable ICAP client that send a REQMOD request with a HTTP POST request and body as preview
 * to a server and prints the answer, waits for a 10 continue and sends the rest.
 * 
 * @author Michael Mimo Moratti (mimo@mimo.ch)
 *
 */
public class IcapClient {
    public static void main(String[] args) {
        int port = 8099;
        String host = "localhost";

        EventLoopGroup group = new NioEventLoopGroup();

        // Configure the client.
        Bootstrap bootstrap = new Bootstrap();
        ChannelFuture future = bootstrap
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new IcapClientChannelPipeline())
            .connect(new InetSocketAddress(host, port));

        // Wait until the connection attempt succeeds or fails.
        Channel channel = future.awaitUninterruptibly().channel();
        if (!future.isSuccess()) {
            future.cause().printStackTrace();
            return;
        }

        // Prepare the ICAP request.
        IcapRequest request = new DefaultIcapRequest(IcapVersion.ICAP_1_0,IcapMethod.REQMOD,"/simple","localhost");
        request.setBody(IcapMessageElementEnum.REQBODY);
        request.addHeader(IcapHeaders.Names.PREVIEW, "50");
        FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/some/servers/uri");
        httpRequest.headers().add(HttpHeaders.Names.HOST, host);
        httpRequest.headers().add(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
        httpRequest.headers().add(HttpHeaders.Names.ACCEPT_ENCODING, HttpHeaders.Values.GZIP);
        request.setHttpRequest(httpRequest);

        IcapChunk previewChunk = new DefaultIcapChunk(Unpooled.wrappedBuffer("It is common not to understand why something happe".getBytes()));
        previewChunk.setPreviewChunk(true);
        IcapChunkTrailer previewTrailer = new DefaultIcapChunkTrailer(true, false);

        // Send the ICAP request.
        channel.write(request);
        channel.write(previewChunk);
        channel.write(previewTrailer);
        channel.flush();

        // Wait for the server to close the connection.
        channel.closeFuture().awaitUninterruptibly();

        // Shut down executor threads to exit.
        group.shutdownGracefully();
    }
}
