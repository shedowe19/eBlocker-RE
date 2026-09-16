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
import org.junit.Ignore;
import org.junit.Test;

public class IcapRequestEncoderTest extends AbstractEncoderTest {

	private EmbeddedChannel embeddedChannel;
	
	@Before
	public void setUp() {
	    embeddedChannel = new EmbeddedChannel(new IcapRequestEncoder());
	}
	
	@Test
    @Ignore("encoder can not return null in netty 4 anymore")
	public void testEncoderWithUnknownObject() {
		embeddedChannel.writeOutbound("");
		assertNull("poll should return null",readOutbound());
	}
	
	@Test
	public void encodeOPTIONSRequest() throws UnsupportedEncodingException {
		embeddedChannel.writeOutbound(DataMockery.createOPTIONSIcapRequest());
		String request = getBufferContent(readOutbound());
		assertResponse(DataMockery.createOPTIONSRequest(),request);
	}
	
	@Test
	public void encodeOPTIONSRequestWithBody() throws UnsupportedEncodingException {
		embeddedChannel.writeOutbound(DataMockery.createOPTIONSRequestWithBodyIcapMessage());
		String request = getBufferContent(readOutbound());
		assertResponse(DataMockery.createOPTIONSRequestWithBody(),request);
		embeddedChannel.writeOutbound(DataMockery.createOPTIONSRequestWithBodyBodyChunkIcapChunk());
		String dataChunk = getBufferContent(readOutbound());
		assertResponse(DataMockery.createOPTIONSRequestWithBodyBodyChunk(),dataChunk);
		embeddedChannel.writeOutbound(DataMockery.createOPTIONSRequestWithBodyLastChunkIcapChunk());
		String lastChunk = getBufferContent(readOutbound());
		assertResponse(DataMockery.createOPTIONSRequestWithBodyLastChunk(),lastChunk);
	}
	
	@Test
	public void encodeREQMODRequestWithoutBody() throws UnsupportedEncodingException {
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithGetRequestNoBodyIcapMessage());
		String request = getBufferContent(readOutbound());
		doOutput(request);
		assertResponse(DataMockery.createREQMODWithGetRequestNoBody(),request);
	}
	
	@Test
	public void encodeRESMODWithGetRequestNoBody() throws UnsupportedEncodingException {
		embeddedChannel.writeOutbound(DataMockery.createRESPMODWithGetRequestNoBodyIcapMessage());
		String request = getBufferContent(readOutbound());
		doOutput(request);
		assertResponse(DataMockery.createRESPMODWithGetRequestNoBody(),request);
	}
	
	@Test
	public void encodeREQMODWithTwoChunkBody() throws UnsupportedEncodingException {
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithTwoChunkBodyIcapMessage());
		String request = getBufferContent(readOutbound());
		assertResponse(DataMockery.createREQMODWithTwoChunkBodyAnnouncement(),request);
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithTwoChunkBodyIcapChunkOne());
		String chunkOne = getBufferContent(readOutbound());
		assertResponse(DataMockery.createREQMODWithTowChunkBodyChunkOne(),chunkOne);
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithTwoChunkBodyIcapChunkTwo());
		String chunkTwo = getBufferContent(readOutbound());
		assertResponse(DataMockery.createREQMODWithTwoChunkBodyChunkTwo(),chunkTwo);
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithTwoChunkBodyIcapChunkThree());
		String chunkThree = getBufferContent(readOutbound());
		assertResponse(DataMockery.createREQMODWithTwoChunkBodyChunkThree(),chunkThree);
	}
	
	@Test
	public void encodeREQModWithTowChunkBodyAndTrailingHeader() throws UnsupportedEncodingException {
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithTwoChunkBodyIcapMessage());
		String request = getBufferContent(readOutbound());
		assertResponse(DataMockery.createREQMODWithTwoChunkBodyAnnouncement(),request);
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithTwoChunkBodyIcapChunkOne());
		String chunkOne = getBufferContent(readOutbound());
		assertResponse(DataMockery.createREQMODWithTowChunkBodyChunkOne(),chunkOne);
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithTwoChunkBodyIcapChunkTwo());
		String chunkTwo = getBufferContent(readOutbound());
		assertResponse(DataMockery.createREQMODWithTwoChunkBodyChunkTwo(),chunkTwo);
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithTwoChunkBodyChunkThreeIcapChunkTrailer());
		String chunkThree = getBufferContent(readOutbound());
		assertResponse(DataMockery.createREQMODWithTwoChunkBodyChunkThreeWithTrailer(),chunkThree);
	}
	
	@Test
	public void encodeREQMODWithPreview() throws UnsupportedEncodingException {
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithPreviewAnnouncementIcapMessage());
		String request = getBufferContent(readOutbound());
		assertResponse(DataMockery.createREQMODWithPreviewAnnouncement(),request);
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithPreviewIcapChunk());
		String previewChunk = getBufferContent(readOutbound());
		assertResponse(DataMockery.createREQMODWithPreviewChunk(),previewChunk);
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithPreviewLastIcapChunk());
		String lastChunk = getBufferContent(readOutbound());
		assertResponse(DataMockery.createREQMODWithPreviewLastChunk(),lastChunk);
	}
	
	@Test
	public void encodeREQMODWithEarlyTerminatedPreview() throws UnsupportedEncodingException {
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithEarlyTerminatedPreviewAnnouncementIcapMessage());
		String request = getBufferContent(readOutbound());
		assertResponse(DataMockery.createREQMODWithEarlyTerminatedPreviewAnnouncement(),request);
		
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithEarlyTerminatedPreviewIcapChunk());
		String previewChunk = getBufferContent(readOutbound());
		assertResponse(DataMockery.createREQMODWithEarlyTerminatedPreviewChunk(),previewChunk);
		embeddedChannel.writeOutbound(DataMockery.createREQMODWithEarlyTerminatedPreviewLastIcapChunk());
		String lastChunk = getBufferContent(readOutbound());
		assertResponse(DataMockery.createREQMODWithEarlyTerminatedPreviewLastChunk(),lastChunk);
	}
	
	@Test
	public void createOPTIONSRequestProgramaticallyAndEncodeItToValidateEncapsulationHeaderExistence() {
		IcapRequest request = new DefaultIcapRequest(IcapVersion.ICAP_1_0,IcapMethod.OPTIONS,"/foo/bar","icap.server.com");
		embeddedChannel.writeOutbound(request);
		ByteBuf buffer = readOutbound();
		assertTrue("No Encapsulated header found",buffer.toString(Charset.defaultCharset()).indexOf("Encapsulated") > 0);
	}

	private <T> T readOutbound() {
		return ReferenceCountUtil.releaseLater((T)embeddedChannel.readOutbound());
	}
}
