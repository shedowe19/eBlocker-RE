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
import java.nio.charset.Charset;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.Before;
import org.junit.Test;

public class IcapRequestEncoderPipelineTest extends AbstractIcapTest {

	private EmbeddedChannel embeddedChannel;
	
	@Before
	public void setUp() throws UnsupportedEncodingException {
	    embeddedChannel = new EmbeddedChannel(new IcapRequestEncoder(), new IcapChunkSeparator(4012));
	}
	
	@Test
	public void encodeREQMODRequestWithoutBody() throws UnsupportedEncodingException {
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithGetRequestNoBodyIcapMessage());
		ByteBuf buffer = readOutbound();
		assertNotNull("buffer was null",buffer);
		assertEquals("buffer content is wrong",DataMockery.createREQMODWithGetRequestNoBody().toString(Charset.defaultCharset()),buffer.toString(Charset.defaultCharset()));
	}
	
	@Test
	public void encodeREQMODRequestWithBody() throws UnsupportedEncodingException {
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithGetRequestAndDataIcapMessage());
		ByteBuf buffer = readOutbound();
		assertNotNull("message buffer was null",buffer);
		String message = buffer.toString(Charset.defaultCharset());
		assertEquals("message buffer content is wrong",DataMockery.createREQMODWithGetRequestAndData().toString(Charset.defaultCharset()),message);
		buffer = readOutbound();
		assertNotNull("chnunk buffer was null",buffer);
		message = buffer.toString(Charset.defaultCharset());
		assertEquals("chunk buffer content is wrong",DataMockery.createREQMODWithGetRequestAndDataFirstChunk().toString(Charset.defaultCharset()),message);
		buffer = readOutbound();
		assertNotNull("last chnunk buffer was null",buffer);
		message = buffer.toString(Charset.defaultCharset());
		assertEquals("last chunk is wrong",DataMockery.createREQMODWithGetRequestAndDataLastChunk().toString(Charset.defaultCharset()),message);
		assertNull("poll is not empty",readOutbound());
	}

	private <T> T readOutbound() {
		return ReferenceCountUtil.releaseLater((T)embeddedChannel.readOutbound());
	}
}
