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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import ch.mimo.netty.handler.codec.icap.DefaultIcapResponse;
import ch.mimo.netty.handler.codec.icap.IcapChunk;
import ch.mimo.netty.handler.codec.icap.IcapChunkTrailer;
import ch.mimo.netty.handler.codec.icap.IcapRequest;
import ch.mimo.netty.handler.codec.icap.IcapResponse;
import ch.mimo.netty.handler.codec.icap.IcapResponseStatus;
import ch.mimo.netty.handler.codec.icap.IcapVersion;

public class IcapServerHandler extends ChannelInboundHandlerAdapter {

	private boolean continueWasSent;

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if(msg instanceof IcapRequest) {
			IcapRequest request = (IcapRequest)msg;
			System.out.println(request.toString());
		} else if(msg instanceof IcapChunkTrailer) {
			System.out.println(msg.toString());
			if(!continueWasSent) {
				continueWasSent = true;
				// sending 100 continue in order to receive the rest of the message
				IcapResponse response = new DefaultIcapResponse(IcapVersion.ICAP_1_0,IcapResponseStatus.CONTINUE);
				ctx.writeAndFlush(response);
			} else {
				// sending 204 No Content response
				IcapResponse response = new DefaultIcapResponse(IcapVersion.ICAP_1_0,IcapResponseStatus.NO_CONTENT);
				ctx.writeAndFlush(response);
				ctx.close();
			}
		} else if(msg instanceof IcapChunk) {
			System.out.println(msg);
		} 
	}

}
