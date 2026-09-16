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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

public class TrickleDownstreamHandler extends ChannelOutboundHandlerAdapter {

	private long latency;
	private int chunkSize;
	
	public TrickleDownstreamHandler(long latency, int chunkSize) {
		this.latency = latency;
		this.chunkSize = chunkSize;
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		if (msg instanceof ByteBuf) {
			ByteBuf buffer = (ByteBuf)msg;
			while(buffer.readableBytes() > 0) {
				ByteBuf newBuffer = Unpooled.buffer();
				if(buffer.readableBytes() >= chunkSize) {
					newBuffer.writeBytes(buffer.readBytes(chunkSize));
				} else {
					newBuffer.writeBytes(buffer.readBytes(buffer.readableBytes()));
				}
				Thread.sleep(latency);
				ctx.writeAndFlush(newBuffer);
			}
		} else {
			ctx.writeAndFlush(msg);
		}
	}

}
