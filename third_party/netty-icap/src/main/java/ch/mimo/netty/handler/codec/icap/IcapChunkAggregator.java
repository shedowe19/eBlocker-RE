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
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * This ICAP chunk aggregator will combine an received ICAP message with all body chunks.
 * the body is the to be found attached to the correct HTTP request or response instance
 * within the ICAP message.
 * <p>
 * In case when a Preview IcapRequest is received with an early chunk termination the preview indication
 * and header are removed entirely from the message. This is done because a preview message with an early
 * content termination is in essence nothing else than a full message.
 * <p>
 * The reader index of an HTTP content ByteBuf can be reset to 0 via a dedicated constructor in order to handle preview aggregation.
 * This is done in order to allow server implementations to handle preview messages properly. A preview message
 * is aggregated with the 100 Continue response from the client and the buffer will be therefore reset to 0
 * so that the server handler can read the entire message.
 *
 * @author Michael Mimo Moratti (mimo@mimo.ch)
 * 
 * @see IcapChunkSeparator
 *
 */
public class IcapChunkAggregator extends ChannelInboundHandlerAdapter {

	private static final InternalLogger LOG = InternalLoggerFactory.getInstance(IcapChunkAggregator.class);
	
	private static final int READER_INDEX_RESET_VALUE = 0;
	
	private long maxContentLength;
	private IcapMessageWrapper message;
	private boolean resetReaderIndex;
	
	/**
	 * Convenience method to retrieve a HTTP request,response or 
	 * an ICAP options response body from an aggregated IcapMessage. 
	 * @param message
	 * @return null or {@link ByteBuf} if a body exists.
	 */
	public static ByteBuf extractHttpBodyContentFromIcapMessage(IcapMessage message) {
		ByteBuf buffer = null;
		if(message != null) {
			if(message.getHttpRequest() != null && message.getHttpRequest().content().readableBytes() > 0) {
				buffer = message.getHttpRequest().content();
			} else if(message.getHttpResponse() != null && message.getHttpResponse().content().readableBytes() > 0) {
				buffer = message.getHttpResponse().content();
			} else if(message instanceof IcapResponse) {
				if(((IcapResponse) message).getContent().readableBytes() > 0) {
					buffer = ((IcapResponse) message).getContent();
				}
			}
		}	
		return buffer;
	}
	
	/**
	 * @param maxContentLength defines the maximum length of the body content that is allowed. 
	 * If the length is exceeded an exception is thrown.
	 */
	public IcapChunkAggregator(long maxContentLength) {
		this.maxContentLength = maxContentLength;
	}
	
	/**
	 * Constructor that allows to change the preview body update behavior to
	 * reset the HTTP message body channel reader index when receiving the rest
	 * of the body after a 100 Continue.
	 * 
	 * @param maxContentLength defines the maximum length of the body content that is allowed. 
	 * @param resetReaderIndex defines if the HTTP message reader index should be reset after adding more data to it.
	 */
	public IcapChunkAggregator(long maxContentLength, boolean resetReaderIndex) {
		this(maxContentLength);
		this.resetReaderIndex = resetReaderIndex;
	}

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    	if(msg instanceof IcapMessage) {
    		LOG.debug("Aggregation of message [" + msg.getClass().getName() + "] ");
    		IcapMessage currentMessage = (IcapMessage)msg;
    		message = new IcapMessageWrapper(ctx.alloc(), currentMessage);
    		if(!message.hasBody()) {
                message.getIcapMessage().removeHeader(IcapHeaders.Names.PREVIEW);
                ctx.fireChannelRead(message.getIcapMessage());
    			message = null;
    			return;
    		}
    	} else if(msg instanceof IcapChunkTrailer) {
    		LOG.debug("Aggregation of chunk trailer [" + msg.getClass().getName() + "] ");
    		if(message == null) {
    			ctx.fireChannelRead(msg);
    		} else {
    			IcapChunkTrailer trailer = (IcapChunkTrailer)msg;
                if (trailer.isEarlyTerminated()) {
                    LOG.debug("chunk trailer is early terminated, removing PREVIEW header");
                    message.getIcapMessage().removeHeader(IcapHeaders.Names.PREVIEW);
                }
                if (trailer.trailingHeaders().size() > 0) {
                    for (String name : trailer.trailingHeaders().names()) {
                        message.addHeader(name, trailer.trailingHeaders().get(name));
                    }
                }
                if (message.getIcapMessage() instanceof IcapResponse) {
					((IcapResponse)message.getIcapMessage()).setUseOriginalBody(trailer.getUseOriginalBody());
				}
                ctx.fireChannelRead(message.getIcapMessage());
    		}
    	} else if(msg instanceof IcapChunk) {
    		LOG.debug("Aggregation of chunk [" + msg.getClass().getName() + "] ");
    		IcapChunk chunk = (IcapChunk)msg;
    		if(message == null) {
    			ctx.fireChannelRead(msg);
    		} else if(chunk.isLast()) {
    			if(chunk.isEarlyTerminated()) {
                    LOG.debug("chunk is early terminated, removing PREVIEW header");
                    message.getIcapMessage().removeHeader(IcapHeaders.Names.PREVIEW);
    			}
                ctx.fireChannelRead(message.getIcapMessage());
    			message = null;
    		} else {
				try {
					ByteBuf chunkBuffer = chunk.content();
					ByteBuf content = message.getContent();
					if (content.readableBytes() > maxContentLength - chunkBuffer.readableBytes()) {
						content.release();
						throw new TooLongFrameException(
							"ICAP content length exceeded [" + maxContentLength + "] bytes");
    			} else {
    				content.writeBytes(chunkBuffer);
    				if(resetReaderIndex) {
    					content.readerIndex(READER_INDEX_RESET_VALUE);
    				}
    			}
				} finally {
					chunk.content().release();
				}
    		}
    	} else {
    		ctx.fireChannelRead(msg);
    	}
    }
    
    private final class IcapMessageWrapper {
    	
    	private IcapMessage message;
    	private FullHttpMessage relevantHttpMessage;
    	private IcapResponse icapResponse;
    	private boolean messageWithBody;

    	public IcapMessageWrapper(ByteBufAllocator allocator, IcapMessage message) {
    		this.message = message;
    		if(message.getBodyType() != null) {
	    		if(message.getBodyType().equals(IcapMessageElementEnum.REQBODY)) {
					FullHttpRequest newRequest = message.getHttpRequest().replace(allocator.buffer());
					message.getHttpRequest().release();
					relevantHttpMessage = newRequest;
					message.setHttpRequest(newRequest);
	    			messageWithBody = true;
	    		} else if(message.getBodyType().equals(IcapMessageElementEnum.RESBODY)) {
					FullHttpResponse newResponse =  message.getHttpResponse().replace(allocator.buffer());
					message.getHttpResponse().release();
					relevantHttpMessage = newResponse;
					message.setHttpResponse(newResponse);
	    			messageWithBody = true;
	    		} else if(message instanceof IcapResponse && message.getBodyType().equals(IcapMessageElementEnum.OPTBODY)) {
	    			icapResponse = (IcapResponse)message;
	    			messageWithBody = true;
					if (icapResponse.getContent() == null || icapResponse.getContent().readableBytes() <= 0) {
						icapResponse.setContent(allocator.buffer());
					}
				}
			}
		}
    	
    	public boolean hasBody() {
    		return messageWithBody;
    	}
    	
    	public IcapMessage getIcapMessage() {
    		return message;
    	}
    	
    	public void addHeader(String name, String value) {
    		if(messageWithBody) {
    			relevantHttpMessage.headers().add(name,value);
    		} else {
    			throw new IcapDecodingError("A message without body cannot carry trailing headers.");
    		}
    	}
    	
    	public ByteBuf getContent() {
    		if(messageWithBody) {
    			if(relevantHttpMessage != null) {
    				return relevantHttpMessage.content();
    			} else if(icapResponse != null) {
    				return icapResponse.getContent();
    			}
    		}
    		throw new IcapDecodingError("Message stated that there is a body but nothing found in message.");
    	}
    }
}
