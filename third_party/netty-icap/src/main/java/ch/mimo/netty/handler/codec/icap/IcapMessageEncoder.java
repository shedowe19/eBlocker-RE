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

import java.io.UnsupportedEncodingException;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * Main ICAP message encoder. This encoder is based on {@link io.netty.handler.codec.MessageToByteEncoder}
 * 
 * @author Michael Mimo Moratti (mimo@mimo.ch)
 *
 * @see IcapRequestEncoder
 * @see IcapResponseEncoder
 */
public abstract class IcapMessageEncoder extends MessageToByteEncoder<Object> {
	
	private final InternalLogger LOG;
	
	public IcapMessageEncoder() {
		LOG = InternalLoggerFactory.getInstance(getClass());
	}

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
        LOG.debug("Encoding [" + msg.getClass().getName() + "]");
		if(msg instanceof IcapMessage) {
			IcapMessage message = (IcapMessage)msg;
			encodeInitialLine(out, message);
			encodeHeaders(out, message);

			Encapsulated encapsulated = new Encapsulated();
            int index = 0;

			ByteBuf httpMessagesBuffer = null;
			try {
				httpMessagesBuffer = ctx.alloc().buffer();
				encodeHttpRequestHeader(httpMessagesBuffer, message.getHttpRequest());
				if(httpMessagesBuffer.readableBytes() > 0) {
					encapsulated.addEntry(IcapMessageElementEnum.REQHDR, index);
					index += httpMessagesBuffer.readableBytes();
				}

				encodeHttpResponseHeader(httpMessagesBuffer, message.getHttpResponse());
				if(httpMessagesBuffer.readableBytes() > index) {
					encapsulated.addEntry(IcapMessageElementEnum.RESHDR, index);
					index += httpMessagesBuffer.readableBytes() - index;
				}

				if(message.getBodyType() != null) {
					encapsulated.addEntry(message.getBodyType(), index);
				} else {
					encapsulated.addEntry(IcapMessageElementEnum.NULLBODY, index);
				}

				encapsulated.encode(out);
				out.writeBytes(httpMessagesBuffer);
			} finally {
				if (httpMessagesBuffer != null) {
				    httpMessagesBuffer.release();
                }
			}
		} else if(msg instanceof IcapChunk) {
			IcapChunk chunk = (IcapChunk)msg;
			if(chunk.isLast()) {
				if(chunk.isEarlyTerminated()) {
					out.writeBytes(IcapCodecUtil.NATIVE_IEOF_SEQUENCE);
					out.writeBytes(IcapCodecUtil.CRLF);
					out.writeBytes(IcapCodecUtil.CRLF);
				} else if(msg instanceof IcapChunkTrailer) {
					out.writeByte((byte) '0');
					if (((IcapChunkTrailer)msg).getUseOriginalBody() != null) {
						out.writeBytes(";use-original-body=".getBytes(IcapCodecUtil.ASCII_CHARSET));
						out.writeBytes(Integer.toString(((IcapChunkTrailer)msg).getUseOriginalBody()).getBytes(IcapCodecUtil.ASCII_CHARSET));
					}
					out.writeBytes(IcapCodecUtil.CRLF);
					encodeTrailingHeaders(out,(IcapChunkTrailer)msg);
					out.writeBytes(IcapCodecUtil.CRLF);
				} else {
					out.writeByte((byte) '0');
					out.writeBytes(IcapCodecUtil.CRLF);
					out.writeBytes(IcapCodecUtil.CRLF);
				}
			} else {
				ByteBuf chunkBuffer = chunk.content();
				int contentLength = chunkBuffer.readableBytes();
				out.writeBytes(Integer.toHexString(contentLength).getBytes(IcapCodecUtil.ASCII_CHARSET));
				out.writeBytes(IcapCodecUtil.CRLF);
				out.writeBytes(chunkBuffer);
				out.writeBytes(IcapCodecUtil.CRLF);
			}
		}
	}

	protected abstract int encodeInitialLine(ByteBuf buffer, IcapMessage message)  throws Exception;
	
	private void encodeHttpRequestHeader(ByteBuf buffer, HttpRequest httpRequest) throws UnsupportedEncodingException {
		if(httpRequest != null) {
			buffer.writeBytes(httpRequest.getMethod().toString().getBytes(IcapCodecUtil.ASCII_CHARSET));
			buffer.writeByte(IcapCodecUtil.SPACE);
			buffer.writeBytes(httpRequest.getUri().getBytes(IcapCodecUtil.ASCII_CHARSET));
			buffer.writeByte(IcapCodecUtil.SPACE);
			buffer.writeBytes(httpRequest.getProtocolVersion().toString().getBytes(IcapCodecUtil.ASCII_CHARSET));
			buffer.writeBytes(IcapCodecUtil.CRLF);
            for (Map.Entry<String, String> h: httpRequest.headers()) {
                encodeHeader(buffer, h.getKey(), h.getValue());
            }
			buffer.writeBytes(IcapCodecUtil.CRLF);
		}
	}
	
	private void encodeHttpResponseHeader(ByteBuf buffer, HttpResponse httpResponse) throws UnsupportedEncodingException {
		if(httpResponse != null) {
			buffer.writeBytes(httpResponse.getProtocolVersion().toString().getBytes(IcapCodecUtil.ASCII_CHARSET));
			buffer.writeByte(IcapCodecUtil.SPACE);
			buffer.writeBytes(httpResponse.getStatus().toString().getBytes(IcapCodecUtil.ASCII_CHARSET));
			buffer.writeBytes(IcapCodecUtil.CRLF);
            for (Map.Entry<String, String> h: httpResponse.headers()) {
                encodeHeader(buffer, h.getKey(), h.getValue());
            }
			buffer.writeBytes(IcapCodecUtil.CRLF);
		}
	}
	
    private int encodeTrailingHeaders(ByteBuf buffer, IcapChunkTrailer chunkTrailer) {
    	int index = buffer.readableBytes();
        for (Map.Entry<String, String> h: chunkTrailer.trailingHeaders()) {
            encodeHeader(buffer, h.getKey(), h.getValue());
        }
        return buffer.readableBytes() - index;
    }
	
    private int encodeHeaders(ByteBuf buffer, IcapMessage message) {
    	int index = buffer.readableBytes();
        for (Map.Entry<String, String> h: message.getHeaders()) {
            encodeHeader(buffer, h.getKey(), h.getValue());
        }
        return buffer.readableBytes() - index;
    }
    
    private void encodeHeader(ByteBuf buf, String header, String value) {
		buf.writeBytes(header.getBytes(IcapCodecUtil.ASCII_CHARSET));
		buf.writeByte(IcapCodecUtil.COLON);
		buf.writeByte(IcapCodecUtil.SPACE);
		buf.writeBytes(value.getBytes(IcapCodecUtil.ASCII_CHARSET));
		buf.writeBytes(IcapCodecUtil.CRLF);
    }
}
