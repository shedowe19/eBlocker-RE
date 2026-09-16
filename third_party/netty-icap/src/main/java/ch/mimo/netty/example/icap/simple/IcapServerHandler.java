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

import java.nio.charset.Charset;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaders;

import ch.mimo.netty.handler.codec.icap.DefaultIcapResponse;
import ch.mimo.netty.handler.codec.icap.IcapHeaders;
import ch.mimo.netty.handler.codec.icap.IcapMessageElementEnum;
import ch.mimo.netty.handler.codec.icap.IcapMethod;
import ch.mimo.netty.handler.codec.icap.IcapRequest;
import ch.mimo.netty.handler.codec.icap.IcapResponse;
import ch.mimo.netty.handler.codec.icap.IcapResponseStatus;
import ch.mimo.netty.handler.codec.icap.IcapVersion;

public class IcapServerHandler extends ChannelInboundHandlerAdapter {

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		IcapRequest request = (IcapRequest) msg;
		
		System.out.println(request.toString());
		
		IcapResponse response = new DefaultIcapResponse(IcapVersion.ICAP_1_0,IcapResponseStatus.OK);
		IcapMessageElementEnum bodyType = request.getBodyType();
		if(bodyType == null) {
			bodyType = IcapMessageElementEnum.NULLBODY;
		}
		
		if(!request.getMethod().equals(IcapMethod.RESPMOD) && request.getHttpRequest() != null) {
			request.getHttpRequest().headers().add(HttpHeaders.Names.VIA,"icap://my.icap.server");
			response.setHttpRequest(request.getHttpRequest());
		}
		if(request.getHttpResponse() != null) {
			request.getHttpResponse().headers().add(HttpHeaders.Names.VIA,"icap://my.icap.server");
			response.setHttpResponse(request.getHttpResponse());
		}
		response.addHeader(IcapHeaders.Names.ISTAG,"SimpleServer-version-1.0");
		
		ByteBuf buffer = null;
		switch (bodyType) {
		case NULLBODY:
			// No body in request
			break;
		case REQBODY:
			// http request body in request
			buffer = request.getHttpRequest().content();
			break;
		case RESBODY:
			// http response body in request
			buffer = request.getHttpResponse().content();
			break;
		default:
			// cannot reach here.
			break;
		}
		
		/*
		 * There is also a convenience method that extracts a body from any http message.
		 * @See IcapChunkAggregator#extractHttpBodyContentFromIcapMessage(IcapMessage message).
		 */
		
		if(buffer != null) {
			System.out.println(buffer.toString(Charset.defaultCharset()));
		}

		ctx.writeAndFlush(response);
		ctx.close();
	}

}
