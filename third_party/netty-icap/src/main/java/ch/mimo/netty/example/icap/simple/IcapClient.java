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
package ch.mimo.netty.example.icap.simple;

import java.net.InetSocketAddress;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.oio.OioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;

import ch.mimo.netty.handler.codec.icap.DefaultIcapRequest;
import ch.mimo.netty.handler.codec.icap.IcapMethod;
import ch.mimo.netty.handler.codec.icap.IcapRequest;
import ch.mimo.netty.handler.codec.icap.IcapVersion;

/**
 * Simple ICAP client that send a REQMOD request with a HTTP POST request and body
 * to a server and prints the answer.
 * 
 * @author Michael Mimo Moratti (mimo@mimo.ch)
 *
 */
public class IcapClient {

	public static void main(String[] args) {
		int port = 8099;
		String host = "localhost";

		EventLoopGroup group = new OioEventLoopGroup();

		// Configure the client.
		Bootstrap bootstrap = new Bootstrap();
		ChannelFuture connectFuture = bootstrap
			.group(group)
			.channel(OioSocketChannel.class)
			.handler(new IcapClientChannelPipeline())
			.connect(new InetSocketAddress(host,port));

		// Wait until the connection attempt succeeds or fails.
		connectFuture.syncUninterruptibly();
		if (!connectFuture.isSuccess()) {
			connectFuture.cause().printStackTrace();
			return;
		}

		// Prepare the ICAP request.
		IcapRequest request = new DefaultIcapRequest(IcapVersion.ICAP_1_0,IcapMethod.REQMOD,"/simple","localhost");
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST,"/some/servers/uri");
		httpRequest.headers().add(HttpHeaders.Names.HOST,host);
		httpRequest.headers().add(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
		httpRequest.headers().add(HttpHeaders.Names.ACCEPT_ENCODING, HttpHeaders.Values.GZIP);
		httpRequest.replace(Unpooled.wrappedBuffer("This is the message body that contains all the necessary data to answer the ultimate question...".getBytes()));
		request.setHttpRequest(httpRequest);

		// Send the ICAP request.
		Channel channel = connectFuture.channel();
		ChannelFuture requestFuture = channel.writeAndFlush(request);

		// Wait for the server to close the connection.
		requestFuture.channel().closeFuture().syncUninterruptibly();
		group.shutdownGracefully();
	}
}
