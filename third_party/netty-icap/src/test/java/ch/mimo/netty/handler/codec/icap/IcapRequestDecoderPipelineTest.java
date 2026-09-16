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

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.Before;
import org.junit.Test;

public class IcapRequestDecoderPipelineTest extends AbstractIcapTest {

    private EmbeddedChannel embeddedChannel;

	@Before
	public void setUp() throws UnsupportedEncodingException {
		embeddedChannel = new EmbeddedChannel(new IcapRequestDecoder(),new IcapChunkAggregator(4012));
	}
	
	@Test
	public void decodeREQMODRequestWithoutBody() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithGetRequestNoBody());
		IcapRequest request = readInbound();
		assertNotNull("The request object is null",request);
		DataMockery.assertCreateREQMODWithGetRequestNoBody(request);
		assertTrue("body found",request.getHttpRequest().content().readableBytes() <= 0);
	}
	
	@Test
	public void decodeREQMODRequestWithBody() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithTwoChunkBody());
		IcapRequest request = readInbound();
		assertNotNull("The request object is null",request);
		DataMockery.assertCreateREQMODWithTwoChunkBody(request);
		assertEquals("body has wrong size",109,request.getHttpRequest().content().readableBytes());
	}
	
	@Test
	public void decodeREQMODRequestWithBodyTwice() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithTwoChunkBody());
		IcapRequest request = readInbound();
		assertNotNull("The request object is null",request);
		DataMockery.assertCreateREQMODWithTwoChunkBody(request);
		assertEquals("body has wrong size",109,request.getHttpRequest().content().readableBytes());
		embeddedChannel.writeInbound(DataMockery.createREQMODWithTwoChunkBody());
		request = readInbound();
		DataMockery.assertCreateREQMODWithTwoChunkBody(request);
		assertEquals("body has wrong size",109,request.getHttpRequest().content().readableBytes());
	}

	private <T> T readInbound() {
		return ReferenceCountUtil.releaseLater((T) embeddedChannel.readInbound());
	}
}
