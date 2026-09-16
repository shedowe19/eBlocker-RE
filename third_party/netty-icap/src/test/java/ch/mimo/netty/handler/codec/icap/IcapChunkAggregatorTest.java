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

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import junit.framework.Assert;

import io.netty.buffer.ByteBuf;
import org.junit.Before;
import org.junit.Test;

public class IcapChunkAggregatorTest extends AbstractIcapTest {

    private EmbeddedChannel embeddedChannel;

	@Before
	public void setUp() throws UnsupportedEncodingException {
	    embeddedChannel = new EmbeddedChannel(new IcapChunkAggregator(4012));
	}

	@Test
	public void offerUnknownObject() {
		embeddedChannel.writeInbound("The ultimate answer is 42");
	}

	@Test
	public void retrieveOptionsBody() {
		ByteBuf buffer = IcapChunkAggregator.extractHttpBodyContentFromIcapMessage(DataMockery.createOPTIONSResponseWithBodyAndContentIcapResponse());
		assertNotNull("buffer was null",buffer);
	}

	@Test
	public void retrieveHttpRequestBody() {
		ByteBuf buffer = IcapChunkAggregator.extractHttpBodyContentFromIcapMessage(DataMockery.createREQMODWithBodyContentIcapMessage());
		assertNotNull("buffer was null",buffer);
	}

	@Test
	public void retrieveHttpResponseBody() {
		ByteBuf buffer = IcapChunkAggregator.extractHttpBodyContentFromIcapMessage(DataMockery.createRESPMODWithPreviewDataIcapRequest());
		assertNotNull("buffer was null",buffer);
	}


	@Test
	public void aggregatorOPTIONSResponseWithBody() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createOPTIONSResponseWithBodyIcapResponse());
		embeddedChannel.writeInbound(DataMockery.createOPTIONSRequestWithBodyBodyChunkIcapChunk());
		embeddedChannel.writeInbound(DataMockery.createOPTIONSRequestWithBodyLastChunkIcapChunk());
		IcapResponse response = readInbound();
		assertNotNull("response was null",response);
		assertEquals("wrong body value in response",IcapMessageElementEnum.OPTBODY,response.getBodyType());
		assertNotNull("no body in options response",response.getContent());
		ByteBuf buffer = response.getContent();
		assertEquals("body was wrong","This is a options body chunk.",buffer.toString(Charset.defaultCharset()));
	}

	@Test
	public void aggregatorREQMODWithGetRequestWithoutChunks() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithGetRequestNoBodyAndEncapsulationHeaderIcapMessage());
		IcapRequest request = readInbound();
		DataMockery.assertCreateREQMODWithGetRequestNoBody(request);
	}

	@Test
	public void aggregatorREQMODWithGetRequestWithoutChunksAndNullBodySet() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithGetRequestNoBodyAndEncapsulationHeaderAndNullBodySetIcapMessage());
		IcapRequest request = readInbound();
		DataMockery.assertCreateREQMODWithGetRequestNoBody(request);
	}

	@Test
	public void aggregatorChunkOnlyTest() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestAndPreviewIcapChunk());
		IcapChunk chunk = (IcapChunk)readInbound();
		assertNotNull("no chunk received from pipeline",chunk);
		DataMockery.assertCreateRESPMODWithGetRequestAndPreviewChunk(chunk);
	}

	@Test
	public void aggregatorMessageWithoutBodyFollowedByBodyChunk() {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithGetRequestNoBodyAndEncapsulationHeaderIcapMessage());
		IcapRequest request = readInbound();
		DataMockery.assertCreateREQMODWithGetRequestNoBody(request);
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestAndPreviewIcapChunk());
		IcapChunk chunk = (IcapChunk)readInbound();
		assertNotNull("no chunk received from pipeline",chunk);
		DataMockery.assertCreateRESPMODWithGetRequestAndPreviewChunk(chunk);
	}

	@Test
	public void aggregateREQMODRequestWithChunks() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithTwoChunkBodyAndEncapsulationHeaderIcapMessage());
		embeddedChannel.writeInbound(DataMockery.createREQMODWithTwoChunkBodyIcapChunkOne());
		embeddedChannel.writeInbound(DataMockery.createREQMODWithTwoChunkBodyIcapChunkTwo());
		embeddedChannel.writeInbound(DataMockery.createREQMODWithTwoChunkBodyIcapChunkThree());
		IcapRequest request = readInbound();
		DataMockery.assertCreateREQMODWithTwoChunkBody(request);
		String body = request.getHttpRequest().content().toString(IcapCodecUtil.ASCII_CHARSET);
		StringBuilder builder = new StringBuilder();
		builder.append("This is data that was returned by an origin server.");
		builder.append("And this the second chunk which contains more information.");
		assertEquals("The body content was wrong",builder.toString(),body);
		Object object = readInbound();
		assertNull("still something there",object);
	}

	@Test
	public void aggregateRESPMODRequestWithPreviewChunks() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestAndPreviewIncludingEncapsulationHeaderIcapRequest());
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestAndPreviewIcapChunk());
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestAndPreviewLastIcapChunk());
		IcapRequest request = readInbound();
		DataMockery.assertCreateRESPMODWithGetRequestAndPreview(request);
		String body = request.getHttpResponse().content().toString(IcapCodecUtil.ASCII_CHARSET);
		StringBuilder builder = new StringBuilder();
		builder.append("This is data that was returned by an origin server.");
		assertEquals("The body content was wrong",builder.toString(),body);
        Object object = readInbound();
		assertNull("still something there",object);
	}

	@Test
	public void aggregateRESPMODRequestWithPreviewChunksAndReadInBetween() throws UnsupportedEncodingException {
		embeddedChannel = new EmbeddedChannel(new IcapChunkAggregator(4012, true));
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestAndPreviewIncludingEncapsulationHeaderIcapRequest());
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestAndPreviewIcapChunk());
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestAndPreviewLastIcapChunk());
		IcapRequest request = readInbound();
		DataMockery.assertCreateRESPMODWithGetRequestAndPreview(request);
		ByteBuf buffer = request.getHttpResponse().content();
		Assert.assertEquals("wrong reader index",0,buffer.readerIndex());
		String body = destructiveRead(buffer);
		StringBuilder builder = new StringBuilder();
		builder.append("This is data that was returned by an origin server.");
		assertEquals("The body content was wrong",builder.toString(),body);
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestAndPreviewIcapChunkFullMessageChunk());
		embeddedChannel.writeInbound(DataMockery.createRESPMODWithGetRequestAndPreviewChunkTrailer());
		IcapRequest request1 = readInbound();
		buffer = request1.getHttpResponse().content();
		Assert.assertEquals("wrong reader index",0,buffer.readerIndex());
		String body1 = destructiveRead(buffer);
		assertEquals("The body content after another chunk was sent is wrong","This is data that was returned by an origin server.And this the second chunk which contains more information.",body1);
		Object object = readInbound();
		assertNull("still something there",object);
	}

	@Test
	public void aggregateREQModRequestWithChunksAndTrailingHeaders() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithTwoChunkBodyAndEncapsulationHeaderIcapMessage());
		embeddedChannel.writeInbound(DataMockery.createREQMODWithTwoChunkBodyIcapChunkOne());
		embeddedChannel.writeInbound(DataMockery.createREQMODWithTwoChunkBodyIcapChunkTwo());
		embeddedChannel.writeInbound(DataMockery.createREQMODWithTwoChunkBodyChunkThreeIcapChunkTrailer());
		IcapRequest request = readInbound();
		DataMockery.assertCreateREQMODWithTwoChunkBody(request);
		assertTrue("Key does not exist [TrailingHeaderKey1]",request.getHttpRequest().headers().contains("TrailingHeaderKey1"));
		assertEquals("The header: TrailingHeaderKey1 is invalid","TrailingHeaderValue1",request.getHttpRequest().headers().get("TrailingHeaderKey1"));
		assertTrue("Key does not exist [TrailingHeaderKey2]",request.getHttpRequest().headers().contains("TrailingHeaderKey2"));
		assertEquals("The header: TrailingHeaderKey2 is invalid","TrailingHeaderValue2",request.getHttpRequest().headers().get("TrailingHeaderKey2"));
		assertTrue("Key does not exist [TrailingHeaderKey3]",request.getHttpRequest().headers().contains("TrailingHeaderKey3"));
		assertEquals("The header: TrailingHeaderKey3 is invalid","TrailingHeaderValue3",request.getHttpRequest().headers().get("TrailingHeaderKey3"));
		assertTrue("Key does not exist [TrailingHeaderKey4]",request.getHttpRequest().headers().contains("TrailingHeaderKey1"));
		assertEquals("The header: TrailingHeaderKey4 is invalid","TrailingHeaderValue4",request.getHttpRequest().headers().get("TrailingHeaderKey4"));
		String body = request.getHttpRequest().content().toString(IcapCodecUtil.ASCII_CHARSET);
		StringBuilder builder = new StringBuilder();
		builder.append("This is data that was returned by an origin server.");
		builder.append("And this the second chunk which contains more information.");
		assertEquals("The body content was wrong",builder.toString(),body);
		Object object = readInbound();
		assertNull("still something there",object);
	}

	@Test
	public void aggregateREQModResponseWithUseOriginalBody() {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithPartialContentUsingModifiedOriginalBodyIcapResponse());
		embeddedChannel.writeInbound(DataMockery.createREQMODWithPartialContentUsingModifiedOriginalBodyIcapChunk());
		embeddedChannel.writeInbound(DataMockery.createREQMODWithPartialContentUsingModifiedOriginalBodyIcapChunkTrailer());
		IcapResponse response = readInbound();
		DataMockery.assertCreateREQMODWithPartialContentUsingModifiedOriginalBodyIcapResponse(response);
	}

	@Test
	public void exceedMaximumBodySize() throws UnsupportedEncodingException {
		embeddedChannel = new EmbeddedChannel(new IcapChunkAggregator(20));
		embeddedChannel.writeInbound(DataMockery.createREQMODWithTwoChunkBodyAndEncapsulationHeaderIcapMessage());
		boolean exception = false;
		try {
			embeddedChannel.writeInbound(DataMockery.createREQMODWithTwoChunkBodyIcapChunkOne());
		} catch(RuntimeException rte) {
			exception = true;
		}
		assertTrue("No Exception was thrown",exception);
	}

	@Test
	public void retrieveREQMODPreviewWithEarlyTermination() throws UnsupportedEncodingException {
		embeddedChannel.writeInbound(DataMockery.createREQMODWithEarlyTerminatedPreviewAnnouncementIcapMessage());
		embeddedChannel.writeInbound(DataMockery.createREQMODWithEarlyTerminatedPreviewIcapChunk());
		embeddedChannel.writeInbound(DataMockery.createREQMODWithEarlyTerminatedPreviewLastIcapChunk());
		IcapRequest request = readInbound();
		assertFalse("The request is marked to be of type preview", request.isPreviewMessage());
	}

	private String destructiveRead(ByteBuf buffer) {
		byte[] data = new byte[buffer.readableBytes()];
		buffer.readBytes(data);
		return new String(data,IcapCodecUtil.ASCII_CHARSET);
	}

	private <T> T readInbound() {
	    return ReferenceCountUtil.releaseLater((T) embeddedChannel.readInbound());
	}

}

