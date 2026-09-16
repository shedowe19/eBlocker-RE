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

/**
 * Encodes an ICAP Request which takes an {@link IcapRequest} or {@link IcapChunk} to encode.
 * 
 * @author Michael Mimo Moratti (mimo@mimo.ch)
 *
 */
@ChannelHandler.Sharable
public class IcapRequestEncoder extends IcapMessageEncoder {

	public IcapRequestEncoder() {
		super();
	}
	
	@Override
	protected int encodeInitialLine(ByteBuf buffer, IcapMessage message) throws Exception {
		IcapRequest request = (IcapRequest) message;
		int index = buffer.readableBytes();
        buffer.writeBytes(request.getMethod().toString().getBytes(IcapCodecUtil.ASCII_CHARSET));
        buffer.writeByte(IcapCodecUtil.SPACE);
        buffer.writeBytes(request.getUri().getBytes(IcapCodecUtil.ASCII_CHARSET));
        buffer.writeByte(IcapCodecUtil.SPACE);
        buffer.writeBytes(request.getProtocolVersion().toString().getBytes(IcapCodecUtil.ASCII_CHARSET));
        buffer.writeBytes(IcapCodecUtil.CRLF);
        return buffer.readableBytes() - index;
	}

}
