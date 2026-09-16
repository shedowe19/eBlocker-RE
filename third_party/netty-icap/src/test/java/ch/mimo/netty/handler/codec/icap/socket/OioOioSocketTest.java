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
package ch.mimo.netty.handler.codec.icap.socket;

import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.oio.OioServerSocketChannel;
import io.netty.channel.socket.oio.OioSocketChannel;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executor;

public class OioOioSocketTest extends SocketTests {

	@Override
	protected Class<OioSocketChannel> newClientSocketChannelFactory() {
		return OioSocketChannel.class;
	}
	
	@Override
	protected Class<OioServerSocketChannel> newServerSocketChannelFactory() {
		return OioServerSocketChannel.class;
	}

	@Override
	protected EventLoopGroup newClientEventLoopGroup(Executor executor) {
		return new OioEventLoopGroup(1, executor);
	}

	@Override
	protected EventLoopGroup newServerEventLoopGroup(Executor executor) {
		return new OioEventLoopGroup(1, executor);
	}

	@Override
	protected Map<ChannelOption, Object> clientAdditionalChannelOptions() {
		return Collections.<ChannelOption, Object>singletonMap(ChannelOption.SO_TIMEOUT, 1);
	}
}
