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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

public abstract class AbstractHandler extends ChannelInboundHandlerAdapter implements Handler {

	private boolean processed;
	private boolean exception;
	
	private Throwable cause;

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		processed = doMessageReceived(ctx, msg);
		ReferenceCountUtil.releaseLater(msg);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		super.exceptionCaught(ctx, cause);
		this.cause = cause;
		exception = true;
	}

	public boolean isProcessed() {
		return processed;
	}
	
	public boolean hasException() {
		return exception;
	}
	
	public Throwable getExceptionCause() {
		return cause;
	}

	public abstract boolean doMessageReceived(ChannelHandlerContext ctx, Object msg) throws Exception;
}
