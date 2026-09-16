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
package ch.mimo.netty.handler.codec.icap;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * Separates a received ICAP message and body that is attached to either the HTTP request or response.
 * 
 * In other words. This handler allows to create a combined ICAP message containing HTTP request/response and
 * the corresponding body as ByteBuf include in one of the HTTP relevant instances.
 * 
 * This separator cannot handle trailing headers at HTTP request or response bodies. If you have to
 * send trailing headers then consider not using this separator but handling the message body by yourself.
 * 
 * @author Michael Mimo Moratti (mimo@mimo.ch)
 *
 */
@ChannelHandler.Sharable
public class IcapChunkSeparator extends ChannelOutboundHandlerAdapter {

	private static final InternalLogger LOG = InternalLoggerFactory.getInstance(IcapChunkSeparator.class);
	
	private int chunkSize;
	
	/**
	 * @param chunkSize defines the normal chunk size that is to be produced while separating.
	 */
	public IcapChunkSeparator(int chunkSize) {
		this.chunkSize = chunkSize;
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		if(msg instanceof IcapMessage) {
			LOG.debug("Separation of message [" + msg.getClass().getName() + "] ");
			IcapMessage message = (IcapMessage)msg;
			ByteBuf content = setBodyTypeAndGetContent(message);
			if (content != null) {
				content.retain();
			}
			ctx.write(message);
			if(content != null) {
				boolean isPreview = message.isPreviewMessage();
				boolean isEarlyTerminated = false;
				Integer useOriginalBody = getUseOriginalBody(message);
				if(isPreview) {
					isEarlyTerminated = content.readableBytes() < message.getPreviewAmount();
				}
				boolean appendTrailer = content.readableBytes() > 0 || useOriginalBody != null || isPreview;
				while(content.readableBytes() > 0) {
					IcapChunk chunk;
					if(content.readableBytes() > chunkSize) {
						chunk = new DefaultIcapChunk(content.readRetainedSlice(chunkSize));
					} else {
						chunk = new DefaultIcapChunk(content.readRetainedSlice(content.readableBytes()));
					}
					chunk.setPreviewChunk(isPreview);
					chunk.setEarlyTermination(isEarlyTerminated);
					ctx.write(chunk);
				}
				if (appendTrailer) {
					IcapChunkTrailer trailer = new DefaultIcapChunkTrailer();
					trailer.setPreviewChunk(isPreview);
					trailer.setEarlyTermination(isEarlyTerminated);
					trailer.setUseOriginalBody(useOriginalBody);
					ctx.write(trailer);
				}
				content.release();
			}
			ctx.flush();
		} else {
			ctx.writeAndFlush(msg);
		}
	}
    
	private ByteBuf setBodyTypeAndGetContent(IcapMessage message) {
		if(message instanceof IcapResponse && ((IcapResponse)message).getContent() != null) {
			IcapResponse response = (IcapResponse)message;
			ByteBuf content = response.getContent();
			if(content != null) {
				message.setBody(IcapMessageElementEnum.OPTBODY);
			}
			return content;
		}

		boolean useOriginalBody = message instanceof IcapResponse && ((IcapResponse)message).getUseOriginalBody() != null;
		if(message.getHttpResponse() != null && message.getHttpResponse().content() != null && (message.getHttpResponse().content().readableBytes() > 0 || useOriginalBody || message.isPreviewMessage())) {
			message.setBody(IcapMessageElementEnum.RESBODY);
			return message.getHttpResponse().content();
		}

		if(message.getHttpRequest() != null && message.getHttpRequest().content() != null && (message.getHttpRequest().content().readableBytes() > 0 || useOriginalBody || message.isPreviewMessage())) {
			message.setBody(IcapMessageElementEnum.REQBODY);
			return message.getHttpRequest().content();
		}

		return null;
	}

	private Integer getUseOriginalBody(IcapMessage message) {
		if (!(message instanceof IcapResponse)) {
			return null;
		}

		return ((IcapResponse) message).getUseOriginalBody();
	}
}
