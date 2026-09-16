/*******************************************************************************
 * Copyright 2012 Michael Mimo Moratti
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

public class IcapRequestDecoderTest extends AbstractIcapTest {

    private EmbeddedChannel embeddedChannel;

	@Before
	public void setUp() throws UnsupportedEncodingException {
		embeddedChannel = new EmbeddedChannel(new IcapRequestDecoder());
	}
	
	@Test 
	public void testConstructorValueValidation() {
		boolean error = false;
		try {
			new IcapRequestDecoder(0,1,1,1);
		} catch(IllegalArgumentException iage) {
			error = true;
		}
		assertTrue("No exception was thrown for the maxInitialLength validation",error);
		error = false;
		try {
			new IcapRequestDecoder(1,0,1,1);
		} catch(IllegalArgumentException iage) {
			error = true;
		}
		assertTrue("No exception was thrown for the maxIcapHeaderSize validation",error);
		error = false;
		error = false;
		try {
			new IcapRequestDecoder(1,1,0,1);
		} catch(IllegalArgumentException iage) {
			error = true;
		}
		assertTrue("No exception was thrown for the maxHttpHeaderSize validation",error);
		error = false;
		error = false;
		try {
			new IcapRequestDecoder(1,1,1,0);
		} catch(IllegalArgumentException iage) {
			error = true;
		}
		assertTrue("No exception was thrown for the maxChunkSize validation",error);
		error = false;
		try {
			new IcapRequestDecoder(1,1,1,1);
		} catch(IllegalArgumentException iage) {
			error = true;
		}
		assertFalse("All input values are greater null but exception occured",error);
	}
	
	@Test
	public void decodeOPTIONRequestTest() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createOPTIONSRequest());
		IcapRequest result = readInbound();
		assertNotNull("The decoded icap request instance is null",result);
		DataMockery.assertCreateOPTIONSRequest(result);
	}
	
	@Test
	public void decodeOPTIONSRequestWithoutEncapsulatedHeader() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createOPTIONSRequestWithoutEncapsulatedHeader());
		IcapRequest result = readInbound();
		assertNotNull("The decoded icap request instance is null",result);
	}
	
	@Test
	public void stripPrefixingWhitespacesFromMessage() throws UnsupportedEncodingException {
        embeddedChannel.writeInbound(DataMockery.createWhiteSpacePrefixedOPTIONSRequest());
		IcapRequest result = readInbound();
		assertNotNull("The decoded icap request instance is null",result);
		DataMockery.assertCreateWhiteSpacePrefixedOPTIONSRequest(result);
	}
	
	@Test
	public void decodeOPTIONSRequestWithBody() throws UnsupportedEncodingException {
        embeddedChannel.writeInbound(DataMockery.createOPTIONSRequestWithBody());
		IcapRequest result = readInbound();
		assertNotNull("The decoded icap request instance is null",result);
		DataMockery.assertOPTIONSRequestWithBody(result);
        embeddedChannel.writeInbound(DataMockery.createOPTIONSRequestWithBodyBodyChunk());
		IcapChunk dataChunk = readInbound();
		DataMockery.assertOPTIONSRequestWithBodyBodyChunk(dataChunk);
        embeddedChannel.writeInbound(DataMockery.createOPTIONSRequestWithBodyLastChunk());
		IcapChunk lastChunk = readInbound();
		DataMockery.assertOPTIONSRequestWithBodyLastChunk(lastChunk);
	}
	
	@Test
	public void decodeREQMODRequestWithNullBody() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithGetRequestNoBody());
		IcapRequest result = readInbound();
		assertNotNull("The decoded icap request instance is null",result);
		DataMockery.assertCreateREQMODWithGetRequestNoBody(result);
	}
	
	@Test
	public void decodeRESPMODRequestWithNullBody() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestNoBody());
		IcapRequest result = readInbound();
		assertNotNull("The decoded icap request instance is null",result);
		DataMockery.assertCreateRESPMODWithGetRequestNoBody(result);
	}
	
	@Test
	public void decodeRESPMODRequestWithNullBodyAndReverseRequestAlignement() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestNoBodyAndReverseRequestAlignement());
		IcapRequest result = readInbound();
		assertNotNull("The decoded icap request instance is null",result);
		DataMockery.assertCreateRESPMODWithGetRequestNoBodyAndReverseRequestAlignement(result);
	}
	
	@Test
	public void decodeREQMODRequestWithTwoChunkBody() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithTwoChunkBody());
		IcapRequest result = readInbound();
		assertNotNull("The decoded icap request instance is null",result);
		DataMockery.assertCreateREQMODWithTwoChunkBody(result);
		DataMockery.assertCreateREQMODWithTwoChunkBodyFirstChunk((IcapChunk)readInbound());
		DataMockery.assertCreateREQMODWithTwoChunkBodySecondChunk((IcapChunk)readInbound());
		DataMockery.assertCreateREQMODWithTwoChunkBodyThirdChunk((IcapChunk)readInbound());
	}
	
	@Test
	public void decodeREQMODRequestWithTwoChunkBodyAndTrailingHeaders() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithTwoChunkBodyAndTrailingHeaders());
		IcapRequest result = readInbound();
		assertNotNull("The decoded icap request instance is null",result);
		DataMockery.assertCreateREQMODWithTwoChunkBody(result);
		DataMockery.assertCreateREQMODWithTwoChunkBodyFirstChunk((IcapChunk)readInbound());
		DataMockery.assertCreateREQMODWithTwoChunkBodySecondChunk((IcapChunk)readInbound());
		DataMockery.assertCreateREQMODWithTwoChunkBodyTrailingHeaderChunk((IcapChunkTrailer)readInbound());
	}
	
	@Test
	public void decodeREQMODRequestWithPreview() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithPreview());
		IcapRequest result = readInbound();
		assertNotNull("The decoded icap request instance is null",result);
		DataMockery.assertCreateREQMODWithPreview(result);
		DataMockery.assertCreateREQMODWithPreviewChunk((IcapChunk)readInbound());
		DataMockery.assertCreateREQMODWithPreviewChunkLastChunk((IcapChunk)readInbound());
	}
	
	@Test
	public void decodeREQMODRequestWithPreviewExpectingChunkTrailer() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithPreview());
		IcapRequest result = readInbound();
		assertNotNull("The decoded icap request instance is null",result);
		DataMockery.assertCreateREQMODWithPreview(result);
		DataMockery.assertCreateREQMODWithPreviewChunk((IcapChunk)readInbound());
		DataMockery.assertCreateREQMODWithPreviewChunkLastChunk((IcapChunk)readInbound());
	}
	
	@Test
	public void decodeREQMODRequestWithEarlyTerminatedPreview() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithEarlyTerminatedPreview());
		IcapMessage result = readInbound();
		assertNotNull("The decoded icap request instance is null",result);
		DataMockery.assertCreateREQMODWithEarlyTerminatedPreview((IcapChunk)readInbound());
		DataMockery.assertCreateREQMODWithEarlyTerminatedPreviewLastChunk((IcapChunk)readInbound());
	}
	
	@Test
	public void decodeRESPMODWithGetRequestAndPreview() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestAndPreview());
		IcapRequest result = readInbound();
		assertNotNull("The decoded icap request instance is null",result);
		DataMockery.assertCreateRESPMODWithGetRequestAndPreview(result);
		DataMockery.assertCreateRESPMODWithGetRequestAndPreviewChunk((IcapChunk)readInbound());
		DataMockery.assertCreateRESPMODWithGetRequestAndPreviewLastChunk((IcapChunk)readInbound());
	}
	
	@Test
	public void decodeRESPMODPreviewWithZeroBody() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createRESPMODPreviewWithZeroBody());
		IcapRequest result = readInbound();
		assertNotNull("The decoded icap request instance is null",result);
	}
	
	@Test
	public void decodeREQMODWithGetRequestAndHugeChunk() throws UnsupportedEncodingException {
	    embeddedChannel = new EmbeddedChannel(new IcapRequestDecoder(4000,4000,4000,10));
		embeddedChannel.writeInbound(DataMockery.createREQMODWithTwoChunkBody());
		IcapRequest result = readInbound();
		assertNotNull("The decoded icap request instance is null",result);
		DataMockery.assertCreateREQMODWithTwoChunkBody(result);
		IcapChunk chunk1 = readInbound();
		assertEquals("chunk 1 has wrong contents","This is da",chunk1.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk2 = readInbound();
		assertEquals("chunk 2 has wrong contents","ta that wa",chunk2.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk3 = readInbound();
		assertEquals("chunk 3 has wrong contents","s returned",chunk3.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk4 = readInbound();
		assertEquals("chunk 4 has wrong contents"," by an ori",chunk4.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk5 = readInbound();
		assertEquals("chunk 5 has wrong contents","gin server",chunk5.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk6 = readInbound();
		assertEquals("chunk 6 has wrong contents",".",chunk6.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk7 = readInbound();
		assertEquals("chunk 7 has wrong contents","And this t",chunk7.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk8 = readInbound();
		assertEquals("chunk 8 has wrong contents","he second ",chunk8.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk9 = readInbound();
		assertEquals("chunk 9 has wrong contents","chunk whic",chunk9.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk10 = readInbound();
		assertEquals("chunk 10 has wrong contents","h contains",chunk10.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk11 = readInbound();
		assertEquals("chunk 11 has wrong contents"," more info",chunk11.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk12 = readInbound();
		assertEquals("chunk 12 has wrong contents","rmation.",chunk12.content().toString(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk13 = readInbound();
		assertTrue("last chunk is of wrong type",chunk13 instanceof IcapChunkTrailer);
		assertTrue("last chunk is not marked as such",chunk13.isLast());
	}
	
	@Test
	public void decodeRESPMODWithGetRequestAndPreviewAndHugeChunk() throws UnsupportedEncodingException {
	    embeddedChannel = new EmbeddedChannel(new IcapRequestDecoder(4000,4000,4000,10));
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestAndPreview());
		IcapRequest result = readInbound();
		assertNotNull("The decoded icap request instance is null",result);
		DataMockery.assertCreateRESPMODWithGetRequestAndPreview(result);
		IcapChunk chunk1 = readInbound();
		assertEquals("chunk 1 has wrong contents","This is da",chunk1.content().toString(IcapCodecUtil.ASCII_CHARSET));
		assertTrue("chunk 1 is not marked as preview chunk",chunk1.isPreviewChunk());
		IcapChunk chunk2 = readInbound();
		assertEquals("chunk 2 has wrong contents","ta that wa",chunk2.content().toString(IcapCodecUtil.ASCII_CHARSET));
		assertTrue("chunk 2 is not marked as preview chunk",chunk2.isPreviewChunk());
		IcapChunk chunk3 = readInbound();
		assertEquals("chunk 3 has wrong contents","s returned",chunk3.content().toString(IcapCodecUtil.ASCII_CHARSET));
		assertTrue("chunk 3 is not marked as preview chunk",chunk3.isPreviewChunk());
		IcapChunk chunk5 = readInbound();
		assertEquals("chunk 5 has wrong contents"," by an ori",chunk5.content().toString(IcapCodecUtil.ASCII_CHARSET));
		assertTrue("chunk 5 is not marked as preview chunk",chunk5.isPreviewChunk());
		IcapChunk chunk6 = readInbound();
		assertEquals("chunk 6 has wrong contents","gin server",chunk6.content().toString(IcapCodecUtil.ASCII_CHARSET));
		assertTrue("chunk 6 is not marked as preview chunk",chunk6.isPreviewChunk());
		IcapChunk chunk7 = readInbound();
		assertEquals("chunk 7 has wrong contents",".",chunk7.content().toString(IcapCodecUtil.ASCII_CHARSET));
		assertTrue("chunk 7 is not marked as preview chunk",chunk7.isPreviewChunk());
		IcapChunk chunk8 = readInbound();
		assertTrue("last chunk is of wrong type",chunk8 instanceof IcapChunkTrailer);
		assertTrue("last chunk is not marked as such",chunk8.isLast());
		assertTrue("last chunk is not marked as preview chunk",chunk8.isPreviewChunk());
	}
	
	@Test
	public void decodeREQMODfollowedByRESPMODbothWithoutBody() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithGetRequestNoBody());
		Object object = readInbound();
		assertNotNull("REQMOD request was null",object);
		assertTrue("wrong object type",object instanceof IcapRequest);
		IcapRequest reqmodRequest = (IcapRequest)object;
		assertEquals("wrong request method",IcapMethod.REQMOD,reqmodRequest.getMethod());
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestNoBody());
		object = readInbound();
		assertNotNull("RESPMOD request was null",object);
		assertTrue("wrong object type",object instanceof IcapRequest);
		IcapRequest respmodRequest = (IcapRequest)object;
		assertEquals("wrong request method",IcapMethod.RESPMOD,respmodRequest.getMethod());
	}
	
	@Test
	public void decodeREQMODFollowedByRESPMODWithPreviewFollowedByRESPMODFollowedByOPTIONS() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithGetRequestNoBody());
		Object object = readInbound();
		assertNotNull("REQMOD request was null",object);
		assertTrue("wrong object type",object instanceof IcapRequest);
		IcapRequest reqmodRequest = (IcapRequest)object;
		assertEquals("wrong request method",IcapMethod.REQMOD,reqmodRequest.getMethod());
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestAndPreview());
		object = readInbound();
		assertNotNull("RESPMOD request was null",object);
		assertTrue("wrong object type",object instanceof IcapRequest);
		IcapRequest respmodRequest = (IcapRequest)object;
		assertEquals("wrong request method",IcapMethod.RESPMOD,respmodRequest.getMethod());
		object = readInbound();
		assertNotNull("RESPMOD preview chunk was null",object);
		assertTrue("wrong object type",object instanceof IcapChunk);
		IcapChunk chunk = (IcapChunk)object;
		assertTrue("chunk is not preview",chunk.isPreviewChunk());
		object = readInbound();
		assertNotNull("preview chunk trailer is null",object);
		assertTrue("wrong object type",object instanceof IcapChunkTrailer);
		IcapChunkTrailer trailer = (IcapChunkTrailer)object;
		assertTrue("chunk trailer is not marked as preview",trailer.isPreviewChunk());
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestNoBody());
		object = readInbound();
		assertNotNull("RESPMOD request was null",object);
		assertTrue("wrong object type",object instanceof IcapRequest);
		IcapRequest respmodRequest1 = (IcapRequest)object;
		assertEquals("wrong request method",IcapMethod.RESPMOD,respmodRequest1.getMethod());
		embeddedChannel.writeInbound(DataMockery.createOPTIONSRequest());
		object = readInbound();
		assertNotNull("options request is null",object);
		assertTrue("wrong object type",object instanceof IcapRequest);
		IcapRequest optionsRequest = (IcapRequest)object;
		assertEquals("wrong request method",IcapMethod.OPTIONS,optionsRequest.getMethod());
	}
	
	@Test
	public void decodeREQMODWithTwoChunkBodyFollowedByRESPMODWithPreviewFollowedByRESMODNoBodyFollowedByOPTIONSRequest() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithTwoChunkBody());
		Object object = readInbound();
		assertTrue("wrong object type",object instanceof IcapRequest);
		IcapRequest respmodRequest = (IcapRequest)object;
		assertEquals("wrong request method",IcapMethod.REQMOD,respmodRequest.getMethod());
		object = readInbound();
		assertNotNull("REQMOD preview chunk was null",object);
		assertTrue("wrong object type",object instanceof IcapChunk);
		object = readInbound();
		assertNotNull("REQMOD preview chunk was null",object);
		assertTrue("wrong object type",object instanceof IcapChunk);
		object = readInbound();
		assertNotNull("preview chunk trailer is null",object);
		assertTrue("wrong object type",object instanceof IcapChunkTrailer);
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestAndPreview());
		object = readInbound();
		assertNotNull("RESPMOD request was null",object);
		assertTrue("wrong object type",object instanceof IcapRequest);
		respmodRequest = (IcapRequest)object;
		assertEquals("wrong request method",IcapMethod.RESPMOD,respmodRequest.getMethod());
		object = readInbound();
		assertNotNull("RESPMOD preview chunk was null",object);
		assertTrue("wrong object type",object instanceof IcapChunk);
		IcapChunk chunk = (IcapChunk)object;
		assertTrue("chunk is not preview",chunk.isPreviewChunk());
		object = readInbound();
		assertNotNull("preview chunk trailer is null",object);
		assertTrue("wrong object type",object instanceof IcapChunkTrailer);
		IcapChunkTrailer trailer = (IcapChunkTrailer)object;
		assertTrue("chunk trailer is not marked as preview",trailer.isPreviewChunk());
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestNoBody());
		object = readInbound();
		assertNotNull("RESPMOD request was null",object);
		assertTrue("wrong object type",object instanceof IcapRequest);
		IcapRequest respmodRequest1 = (IcapRequest)object;
		assertEquals("wrong request method",IcapMethod.RESPMOD,respmodRequest1.getMethod());
		embeddedChannel.writeInbound(DataMockery.createOPTIONSRequest());
		object = readInbound();
		assertNotNull("options request is null",object);
		assertTrue("wrong object type",object instanceof IcapRequest);
		IcapRequest optionsRequest = (IcapRequest)object;
		assertEquals("wrong request method",IcapMethod.OPTIONS,optionsRequest.getMethod());
	}

	private <T> T readInbound() {
		return ReferenceCountUtil.releaseLater((T)embeddedChannel.readInbound());
	}
}

