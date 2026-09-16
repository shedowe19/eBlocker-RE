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

public class IcapChunkSeparatorTest extends AbstractIcapTest {

	private EmbeddedChannel embeddedChannel;
	
	@Before
	public void setUp() throws UnsupportedEncodingException {
        embeddedChannel = new EmbeddedChannel(new IcapChunkSeparator(20));
	}
	
	@Test
	public void sendNonIcapMessage() {
		embeddedChannel.writeOutbound("This is a simple string");
		String message = readOutbound();
		assertNotNull("input response was not received",message);
		assertEquals("input message is not equals output message","This is a simple string",message);
	}
	
	@Test
	public void separateREQMODWithGetRequestNoBodyIcapRequest() {
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithGetRequestNoBodyIcapMessage());
		IcapMessage message = readOutbound();
		assertNotNull("message was null",message);
		assertNull("still some elements in the pipeline",readOutbound());
	}
	
	@Test
	public void separateREQMODWithGetRequestAndData() {
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithGetRequestAndDataIcapMessage());
		IcapMessage message = readOutbound();
		assertNotNull("message was null",message);
		assertEquals("message body indicator is wrong",IcapMessageElementEnum.REQBODY,message.getBodyType());
		IcapChunk chunk1 = readOutbound();
		assertNotNull("chunk 1 was null",chunk1);
		assertEquals("chunk 1 content is wrong","This is data that wa",chunk1.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk2 = readOutbound();
		assertNotNull("chunk 2 was null",chunk2);
		assertEquals("chunk 2 content is wrong","s returned by an ori",chunk2.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk3 = readOutbound();
		assertNotNull("chunk 3 was null",chunk3);
		assertEquals("chunk 3 content is wrong","gin server.",chunk3.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunkTrailer trailer = readOutbound();
		assertNotNull("chunk trailer was null",trailer);
	}
	
	@Test
	public void separateREQMODWithGetRequestAndDataResponse() {
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithDataIcapResponse());
		IcapMessage message = readOutbound();
		assertNotNull("message was null",message);
		assertEquals("message body indicator is wrong",IcapMessageElementEnum.REQBODY,message.getBodyType());
		IcapChunk chunk1 = readOutbound();
		assertNotNull("chunk 1 was null",chunk1);
		assertEquals("chunk 1 content is wrong","This is data that wa",chunk1.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk2 = readOutbound();
		assertNotNull("chunk 2 was null",chunk2);
		assertEquals("chunk 2 content is wrong","s returned by an ori",chunk2.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk3 = readOutbound();
		assertNotNull("chunk 3 was null",chunk3);
		assertEquals("chunk 3 content is wrong","gin server.",chunk3.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunkTrailer trailer = readOutbound();
		assertNotNull("chunk trailer was null",trailer);
	}
	
	@Test
	public void separateREQMODWithPreviewData() {
		embeddedChannel.writeOutbound(DataMockery.createRESPMODWithPreviewDataIcapRequest());
		IcapMessage message = readOutbound();
		assertNotNull("message was null",message);
		assertEquals("message body indicator is wrong",IcapMessageElementEnum.RESBODY,message.getBodyType());
		IcapChunk chunk1 = readOutbound();
		assertNotNull("chunk 1 was null",chunk1);
		assertTrue("chunk 1 is not marked as preview",chunk1.isPreviewChunk());
		assertEquals("chunk 1 content is wrong","This is data that wa",chunk1.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk2 = readOutbound();
		assertNotNull("chunk 2 was null",chunk2);
		assertTrue("chunk 2 is not marked as preview",chunk2.isPreviewChunk());
		assertEquals("chunk 2 content is wrong","s returned by an ori",chunk2.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk3 = readOutbound();
		assertNotNull("chunk 3 was null",chunk3);
		assertTrue("chunk 3 is not marked as preview",chunk3.isPreviewChunk());
		assertEquals("chunk 3 content is wrong","gin server.",chunk3.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunkTrailer trailer = readOutbound();
		assertNotNull("chunk trailer was null",trailer);
		assertTrue("trailer is not marked as preview",trailer.isPreviewChunk());
	}

	@Test
	public void separateREQMODWithPreviewZeroData() {
		embeddedChannel.writeOutbound(DataMockery.createRESPMODWithPreviewZeroDataIcapRequest());
		IcapMessage message = readOutbound();
		assertNotNull("message was null", message);
		assertEquals("message body indicator is wrong", IcapMessageElementEnum.RESBODY,message.getBodyType());
		IcapChunkTrailer trailer = readOutbound();
		assertNotNull("chunk trailer was null", trailer);
		assertTrue("trailer is not marked as preview", trailer.isPreviewChunk());
	}
	
	@Test
	public void separateREQMODWithPreviewDataAndEarlyTermination() {
		embeddedChannel.writeOutbound(DataMockery.createRESPMODWithPreviewDataAndEarlyTerminationIcapRequest());
		IcapMessage message = readOutbound();
		assertNotNull("message was null",message);
		assertEquals("message body indicator is wrong",IcapMessageElementEnum.RESBODY,message.getBodyType());
		IcapChunk chunk1 = readOutbound();
		assertNotNull("chunk 1 was null",chunk1);
		assertTrue("chunk 1 is not marked as preview",chunk1.isPreviewChunk());
		assertEquals("chunk 1 content is wrong","This is data that wa",chunk1.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk2 = readOutbound();
		assertNotNull("chunk 2 was null",chunk2);
		assertTrue("chunk 2 is not marked as preview",chunk2.isPreviewChunk());
		assertEquals("chunk 2 content is wrong","s returned by an ori",chunk2.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk3 = readOutbound();
		assertNotNull("chunk 3 was null",chunk3);
		assertTrue("chunk 3 is not marked as preview",chunk3.isPreviewChunk());
		assertEquals("chunk 3 content is wrong","g",chunk3.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunkTrailer trailer = readOutbound();
		assertNotNull("chunk trailer was null",trailer);
		assertTrue("trailer is not marked as preview",trailer.isPreviewChunk());
		assertTrue("trailer is not marked as early terminated",trailer.isEarlyTerminated());
	}

	@Test
	public void separateREQMODWithPartialContentUsingCompleteOriginalBody() {
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithPartialContentUsingCompleteOriginalBodyIcapResponse());
		IcapMessage message = readOutbound();
		assertNotNull("message was null", message);
		assertEquals("message body indicator is wrong", IcapMessageElementEnum.REQBODY, message.getBodyType());
		IcapChunkTrailer trailer = readOutbound();
		assertNotNull("chunk trailer was null", trailer);
		assertFalse("trailer is marked as preview", trailer.isPreviewChunk());
		assertFalse("trailer is marked as early terminated", trailer.isEarlyTerminated());
		assertEquals("trailer misses or has erroneous use-original-body extension", Integer.valueOf(0), trailer.getUseOriginalBody());
	}

	@Test
	public void separateREQMODWithPartialContentUsingModifiedOriginalBody() {
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithPartialContentUsingModifiedOriginalBodyIcapResponse());
		IcapMessage message = readOutbound();
		assertNotNull("message was null", message);
		assertEquals("message body indicator is wrong", IcapMessageElementEnum.REQBODY, message.getBodyType());
		IcapChunk chunk1 = readOutbound();
		assertNotNull("chunk 1 was null", chunk1);
		assertEquals("chunk 1 content is wrong", "This replaces the fi", chunk1.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk2 = readOutbound();
		assertNotNull("chunk 2 was null", chunk2);
		assertEquals("chunk 2 content is wrong", "rst five bytes of th", chunk2.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk3 = readOutbound();
		assertNotNull("chunk 3 was null", chunk3);
		assertEquals("chunk 3 content is wrong", "e original content.", chunk3.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunkTrailer trailer = readOutbound();
		assertNotNull("chunk trailer was null", trailer);
		assertFalse("trailer is marked as preview", trailer.isPreviewChunk());
		assertFalse("trailer is marked as early terminated", trailer.isEarlyTerminated());
		assertEquals("trailer misses or has erroneous use-original-body extension", Integer.valueOf(5), trailer.getUseOriginalBody());
	}

	@Test
	public void separateREQMODWithPartialContentReplacingOriginalBody() {
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithPartialContentReplacingOriginalBody());
		IcapMessage message = readOutbound();
		assertNotNull("message was null", message);
		assertEquals("message body indicator is wrong", IcapMessageElementEnum.REQBODY, message.getBodyType());
		IcapChunk chunk1 = readOutbound();
		assertNotNull("chunk 1 was null", chunk1);
		assertEquals("chunk 1 content is wrong", "Content replacement", chunk1.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunkTrailer trailer = readOutbound();
		assertNotNull("chunk trailer was null", trailer);
		assertFalse("trailer is marked as preview", trailer.isPreviewChunk());
		assertFalse("trailer is marked as early terminated", trailer.isEarlyTerminated());
		assertNull("trailer has use-original-body extension", trailer.getUseOriginalBody());
	}

	@Test
	public void separateOPTIONSResponseWithBody() {
		embeddedChannel.writeOutbound(DataMockery.createOPTIONSResponseWithBodyInIcapResponse());
		IcapMessage message = readOutbound();
		assertNotNull("message was null",message);
		assertEquals("message body indicator is wrong",IcapMessageElementEnum.OPTBODY,message.getBodyType());
		IcapChunk chunk1 = readOutbound();
		assertNotNull("chunk 1 was null",chunk1);
		assertEquals("chunk 1 content is wrong","This is data that wa",chunk1.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk2 = readOutbound();
		assertNotNull("chunk 2 was null",chunk2);
		assertEquals("chunk 2 content is wrong","s returned by an ori",chunk2.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk3 = readOutbound();
		assertNotNull("chunk 3 was null",chunk3);
		assertEquals("chunk 3 content is wrong","gin server.",chunk3.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunkTrailer trailer = readOutbound();
		assertNotNull("chunk trailer was null",trailer);
	}

	private <T> T readOutbound() {
		return ReferenceCountUtil.releaseLater((T) embeddedChannel.readOutbound());
	}
}
