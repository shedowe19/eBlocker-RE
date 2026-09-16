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
package ch.mimo.netty.handler.codec.icap.socket;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.junit.Test;

import ch.mimo.netty.handler.codec.icap.DataMockery;
import ch.mimo.netty.handler.codec.icap.IcapChunk;
import ch.mimo.netty.handler.codec.icap.IcapChunkTrailer;
import ch.mimo.netty.handler.codec.icap.IcapRequest;
import ch.mimo.netty.handler.codec.icap.IcapResponse;
import ch.mimo.netty.handler.codec.icap.IcapResponseStatus;

public abstract class SocketTests extends AbstractSocketTest {
	
	private class SendOPTIONSRequestServerHandler extends AbstractHandler {
		@Override
		public boolean doMessageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
			IcapRequest request = (IcapRequest) msg;
			DataMockery.assertCreateOPTIONSRequest(request);
			ctx.writeAndFlush(DataMockery.createOPTIONSIcapResponse());
			return true;
		}
	}
	
	private class SendOPTIONSRequestClientHandler extends AbstractHandler {
		@Override
		public boolean doMessageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
			IcapResponse response = (IcapResponse) msg;
			DataMockery.assertOPTIONSResponse(response);
			return true;
		}
	}
	
	private void sendOPTIONSRequest(PipelineType type) {
		runSocketTest(new SendOPTIONSRequestServerHandler(),new SendOPTIONSRequestClientHandler(),new Object[]{DataMockery.createOPTIONSIcapRequest()},type);
	}
	
	@Test
	public void sendOPTIONSRequestThroughClassicPipeline() {
		sendOPTIONSRequest(PipelineType.CLASSIC);
	}

	@Test
	public void sendOPTIONSRequestThroughTricklePipline() {
		sendOPTIONSRequest(PipelineType.TRICKLE);
	}
	
	private class SendRESPMODWithGetRequestNoBodyServerHandler extends AbstractHandler {
		@Override
		public boolean doMessageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
			IcapRequest request = (IcapRequest) msg;
			DataMockery.assertCreateRESPMODWithGetRequestNoBody(request);
			ctx.writeAndFlush(DataMockery.createRESPMODWithGetRequestNoBodyIcapResponse());
			return true;
		}
	}
	
	private class SendRESPMODWithGetRequestNoBodyClientHandler extends AbstractHandler {
		@Override
		public boolean doMessageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
			IcapResponse response = (IcapResponse) msg;
			DataMockery.assertRESPMODWithGetRequestNoBodyResponse(response);
			return true;
		}
	}
	
	private void sendRESPMODWithGetRequestNoBody(PipelineType type) {
		runSocketTest(new SendRESPMODWithGetRequestNoBodyServerHandler(),new SendRESPMODWithGetRequestNoBodyClientHandler(),new Object[]{DataMockery.createRESPMODWithGetRequestNoBodyIcapMessage()},type);
	}
	
	@Test
	public void sendRESPMODWithGetRequestNoBodyThroughClassicPipeline() {
		sendRESPMODWithGetRequestNoBody(PipelineType.CLASSIC);
	}
	
	@Test
	public void sendRESPMODWithGetRequestNoBodyThroughTricklePipeline() {
		sendRESPMODWithGetRequestNoBody(PipelineType.TRICKLE);
	}

	private class SendREQMODWithTwoBodyChunkServerHandler extends AbstractHandler {
		boolean requestMessage = false;
		boolean firstChunk = false;
		boolean secondChunk = false;
		boolean thirdChunk = false;
		@Override
		public boolean doMessageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
			if(msg instanceof IcapRequest) {
				IcapRequest request = (IcapRequest) msg;
				DataMockery.assertCreateREQMODWithTwoChunkBody(request);
				requestMessage = true;
			} else if(msg instanceof IcapChunk) {
				IcapChunk chunk = (IcapChunk)msg;
				if(!firstChunk) {
					DataMockery.assertCreateREQMODWithTwoChunkBodyFirstChunk(chunk);
					firstChunk = true;
				} else if(firstChunk & !secondChunk) {
					DataMockery.assertCreateREQMODWithTwoChunkBodySecondChunk(chunk);
					secondChunk = true;
				} else if(firstChunk & secondChunk & !thirdChunk) {
					DataMockery.assertCreateREQMODWithTwoChunkBodyThirdChunk(chunk);
					ctx.write(DataMockery.createREQMODWithTwoChunkBodyIcapResponse());
					ctx.write(DataMockery.createREQMODWithTwoChunkBodyIcapChunkOne());
					ctx.write(DataMockery.createREQMODWithTwoChunkBodyIcapChunkTwo());
					ctx.writeAndFlush(DataMockery.createREQMODWithTwoChunkBodyIcapChunkThree());
					thirdChunk = true;
				}
			} else {
				fail("unexpected msg instance [" + msg.getClass().getCanonicalName() + "]");
			}
			return requestMessage & firstChunk & secondChunk & thirdChunk;
		}
	}
	
	private class SendREQMODWithTwoBodyChunkWithChunkAggregatorInPipelineServerHandler extends AbstractHandler {

		@Override
		public boolean doMessageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
			if(msg instanceof IcapRequest) {
				IcapRequest request = (IcapRequest) msg;
				DataMockery.assertCreateREQMODWithTwoChunkBody(request);
				ByteBuf contentBuffer = request.getHttpRequest().content();
				String body = contentBuffer.toString(Charset.forName("ASCII"));
				StringBuilder builder = new StringBuilder();
				builder.append("This is data that was returned by an origin server.");
				builder.append("And this the second chunk which contains more information.");
				assertEquals("The body content was wrong",builder.toString(),body);
				ctx.write(DataMockery.createREQMODWithTwoChunkBodyIcapResponse());
				ctx.write(DataMockery.createREQMODWithTwoChunkBodyIcapChunkOne());
				ctx.write(DataMockery.createREQMODWithTwoChunkBodyIcapChunkTwo());
				ctx.writeAndFlush(DataMockery.createREQMODWithTwoChunkBodyIcapChunkThree());
				return true;
			}
			return false;
		}
	}
	
	private class SendREQMODWithTwoBodyChunkClientHandler extends AbstractHandler {
		boolean responseMessage = false;
		boolean firstChunk = false;
		boolean secondChunk = false;
		boolean thirdChunk = false;
		@Override
		public boolean doMessageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
			if(msg instanceof IcapResponse) {
				IcapResponse response = (IcapResponse) msg;
				DataMockery.assertREQMODWithTwoChunkBodyResponse(response);
				responseMessage = true;
			} else if(msg instanceof IcapChunk) {
				IcapChunk chunk = (IcapChunk)msg;
				if(!firstChunk) {
					DataMockery.assertCreateREQMODWithTwoChunkBodyFirstChunk(chunk);
					firstChunk = true;
				} else if(firstChunk & !secondChunk) {
					DataMockery.assertCreateREQMODWithTwoChunkBodySecondChunk(chunk);
					secondChunk = true;
				} else if(firstChunk & secondChunk & !thirdChunk) {
					DataMockery.assertCreateREQMODWithTwoChunkBodyThirdChunk(chunk);
					thirdChunk = true;
				}
			} else {
				fail("unexpected msg instance [" + msg.getClass().getCanonicalName() + "]");
			}
			return responseMessage & firstChunk & secondChunk & thirdChunk;
		}
	}
	
	private void sendREQMODWithTwoBodyChunk(PipelineType type) {
		try {
			runSocketTest(new SendREQMODWithTwoBodyChunkServerHandler(),new SendREQMODWithTwoBodyChunkClientHandler(),new Object[]{DataMockery.createREQMODWithTwoChunkBodyIcapMessage(),
					DataMockery.createREQMODWithTwoChunkBodyIcapChunkOne(),DataMockery.createREQMODWithTwoChunkBodyIcapChunkTwo(),
					DataMockery.createREQMODWithTwoChunkBodyIcapChunkThree()},type);
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			fail("encoding error");
		}
	}
	
	private void sendREQMODWithTwoBodyChunkWithChunkAggregatorInPipeline() {
		try {
			runSocketTest(new SendREQMODWithTwoBodyChunkWithChunkAggregatorInPipelineServerHandler(),new SendREQMODWithTwoBodyChunkClientHandler(),new Object[]{DataMockery.createREQMODWithTwoChunkBodyIcapMessage(),
					DataMockery.createREQMODWithTwoChunkBodyIcapChunkOne(),DataMockery.createREQMODWithTwoChunkBodyIcapChunkTwo(),
					DataMockery.createREQMODWithTwoChunkBodyIcapChunkThree()},PipelineType.AGGREGATOR);
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			fail("encoding error");
		}
	}
	
	@Test
	public void sendREQMODWithTwoBodyChunkThroughClassicPipeline() {
		sendREQMODWithTwoBodyChunk(PipelineType.CLASSIC);
	}
	
	@Test
	public void sendREQMODWithTwoBodyChunkThroughTricklePipeline() {
		sendREQMODWithTwoBodyChunk(PipelineType.TRICKLE);
	}
	
	@Test
	public void sendREQMODWithTowBodyChunkThroughChunkAggregatorPipeline() {
		sendREQMODWithTwoBodyChunkWithChunkAggregatorInPipeline();
	}
	
	private class SendREQMODWithPreviewServerHandler extends AbstractHandler {
		boolean requestMessage = false;
		boolean firstChunk = false;
		boolean secondChunk = false;
		@Override
		public boolean doMessageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
			if(msg instanceof IcapRequest) {
				IcapRequest request = (IcapRequest) msg;
				DataMockery.assertCreateREQMODWithPreview(request);
				requestMessage = true;
			} else if(msg instanceof IcapChunk) {
				IcapChunk chunk = (IcapChunk)msg;
				if(!firstChunk) {
					DataMockery.assertCreateREQMODWithPreviewChunk(chunk);
					firstChunk = true;
				} else if(firstChunk & !secondChunk) {
					DataMockery.assertCreateREQMODWithPreviewChunkLastChunk(chunk);
					ctx.writeAndFlush(DataMockery.createREQMODWithPreviewAnnouncement204ResponseIcapMessage());
					secondChunk = true;
				} 
			} else {
				fail("unexpected msg instance [" + msg.getClass().getCanonicalName() + "]");
			}
			return requestMessage & firstChunk & secondChunk;
		}
	}
	
	private class SendREQMODWithPreviewAggregatorServerHandler extends AbstractHandler {

		@Override
		public boolean doMessageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
			if(msg instanceof IcapRequest) {
				IcapRequest request = (IcapRequest) msg;
				DataMockery.assertCreateREQMODWithPreview(request);
				ByteBuf requestBodyBuffer = request.getHttpRequest().content();
				String body = requestBodyBuffer.toString(Charset.forName("ASCII"));
				StringBuilder builder = new StringBuilder();
				builder.append("This is data that was returned by an origin server.");
				assertEquals("The body content was wrong",builder.toString(),body);
				ctx.writeAndFlush(DataMockery.createREQMODWithPreviewAnnouncement204ResponseIcapMessage());
				return true;
			} else {
				fail("unexpected msg instance [" + msg.getClass().getCanonicalName() + "]");
			}
			return false;
		}
		
	}
	
	private class SendREQMODWithPreviewClientHandler extends AbstractHandler {

		@Override
		public boolean doMessageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
			if(msg instanceof IcapResponse) {
				IcapResponse response = (IcapResponse)msg;
				DataMockery.assertCreateREQMODWithPreviewAnnouncement204Response(response);
			} else {
				fail("unexpected msg instance [" + msg.getClass().getCanonicalName() + "]");
			}
			return true;
		}
	}
	
	private void sendREQMODWithPreview(PipelineType type) {
		try {
		runSocketTest(new SendREQMODWithPreviewServerHandler(),new SendREQMODWithPreviewClientHandler(),new Object[]{DataMockery.createREQMODWithPreviewAnnouncementIcapMessage(),
			DataMockery.createREQMODWithPreviewIcapChunk(),DataMockery.createREQMODWithPreviewLastIcapChunk()},type);
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			fail("encoding error");
		}
	}
	
	@Test
	public void sendREQMODWithPreviewThroughClassicPipeline() {
		sendREQMODWithPreview(PipelineType.CLASSIC);
	}
	
	@Test
	public void sendREQMODWithPreviewThroughTricklePipeline() {
		sendREQMODWithPreview(PipelineType.TRICKLE);
	}
	
	@Test
	public void sendREQMODWithPreviewThroughAggregatorPipleline() {
		try {
			runSocketTest(new SendREQMODWithPreviewAggregatorServerHandler(),new SendREQMODWithPreviewClientHandler(),new Object[]{DataMockery.createREQMODWithPreviewAnnouncementIcapMessage(),
				DataMockery.createREQMODWithPreviewIcapChunk(),DataMockery.createREQMODWithPreviewLastIcapChunk()},PipelineType.AGGREGATOR);
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
				fail("encoding error");
			}
	}
	
	private class SendREQMODWithPreviewAndReturn100ContinueServerHandler extends AbstractHandler {
		boolean requestMessage = false;
		boolean firstChunk = false;
		boolean secondChunk = false;
		boolean thirdChunk = false;
		boolean fourthChunk = false;
		@Override
		public boolean doMessageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
			if(msg instanceof IcapRequest) {
				IcapRequest request = (IcapRequest) msg;
				DataMockery.assertCreateREQMODWithPreview(request);
				requestMessage = true;
			} else if(msg instanceof IcapChunk) {
				IcapChunk chunk = (IcapChunk)msg;
				if(!firstChunk) {
					DataMockery.assertCreateREQMODWithPreviewChunk(chunk);
					firstChunk = true;
				} else if(firstChunk & !secondChunk) {
					DataMockery.assertCreateREQMODWithPreviewChunkLastChunk(chunk);
					ctx.writeAndFlush(DataMockery.createREQMODWithPreviewAnnouncement100ContinueIcapMessage());
					secondChunk = true;
				} else if(firstChunk & secondChunk & !thirdChunk) {
					DataMockery.assertCreateREQMODWithPreview100ContinueChunk(chunk);
					thirdChunk = true;
				} else if(firstChunk & secondChunk & thirdChunk & !fourthChunk) {
					assertTrue("chunk is of wrong type",chunk instanceof IcapChunkTrailer);
					fourthChunk = true;
				}
			} else {
				fail("unexpected msg instance [" + msg.getClass().getCanonicalName() + "]");
			}
			return requestMessage & firstChunk & secondChunk & thirdChunk & fourthChunk;
		}
	}
	
	private class SendREQMODWithPreviewAndReturn100ContinueClientHandler extends AbstractHandler {

		@Override
		public boolean doMessageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
			if(msg instanceof IcapResponse) {
				IcapResponse response = (IcapResponse)msg;
				assertEquals("wrong response type",IcapResponseStatus.CONTINUE,response.getStatus());
				ctx.write(DataMockery.createREQMODWithPreview100ContinueIcapChunk());
				ctx.writeAndFlush(DataMockery.createREQMODWithPreview100ContinueLastIcapChunk());
				return true;
			}
			return false;
		}
	}
	
	private void sendREQMODWithPreviewAndReturn100Continue(PipelineType type) {
		try {
		runSocketTest(new SendREQMODWithPreviewAndReturn100ContinueServerHandler(),new SendREQMODWithPreviewAndReturn100ContinueClientHandler(),new Object[]{DataMockery.createREQMODWithPreviewAnnouncementIcapMessage(),
			DataMockery.createREQMODWithPreviewIcapChunk(),DataMockery.createREQMODWithPreviewLastIcapChunk()},type);
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			fail("encoding error");
		}
	}
	
	@Test
	public void sendREQMODWithPreviewAndReturn100ContinueClassicPipleline() {
		sendREQMODWithPreviewAndReturn100Continue(PipelineType.CLASSIC);
	}
	
	@Test
	public void sendREQMODWithPreviewAndReturn100ContinueTricklePipleline() {
		sendREQMODWithPreviewAndReturn100Continue(PipelineType.TRICKLE);
	}

	private class SendREQMODWithGetRequestAndDataServerHandler extends AbstractHandler {

		private boolean requestReceived = false;
		private boolean dataReceived = false;
		
		@Override
		public boolean doMessageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
			if(msg instanceof IcapRequest) {
				IcapRequest request = (IcapRequest)msg;
				requestReceived = true;
				dataReceived = request.getHttpRequest().content().readableBytes() > 0;
				ctx.writeAndFlush(DataMockery.createREQMODWithDataIcapResponse());
			}
			return requestReceived & dataReceived;
		}
		
	}
	
	private class SendREQMODWithGetRequestAndDataClientHandler extends AbstractHandler {

		private boolean responseReceived = false;
		private boolean dataReceived = false;
		
		@Override
		public boolean doMessageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
			if(msg instanceof IcapResponse) {
				IcapResponse response = (IcapResponse)msg;
				responseReceived = true;
				dataReceived = response.getHttpRequest().content().readableBytes() > 0;
			}
			return responseReceived & dataReceived;
		}
		
	}
	
	@Test
	public void aggregatorSeparatorCombinationTest() {
		runSocketTest(new SendREQMODWithGetRequestAndDataServerHandler(),new SendREQMODWithGetRequestAndDataClientHandler(),new Object[]{DataMockery.createREQMODWithGetRequestAndDataIcapMessage()},PipelineType.SEPARATOR_AGGREGATOR);
	}
	
	private class RepeaterServerHandler extends AbstractHandler {

		private int counter = 0;
		
		@Override
		public boolean doMessageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
			if(msg instanceof IcapRequest) {
				IcapRequest request = (IcapRequest)msg;
				DataMockery.assertCreateREQMODWithPostRequestAndDataIcapRequest(request);
				IcapResponse response = DataMockery.createREQMODWithPostRequestIcapResponse();
				if(counter >= 100) {
					response.addHeader("TEST","END");
				}
				ctx.writeAndFlush(response);
				counter++;
			}
			return counter >= 100;
		}
		
	}
	
	private class RepeaterClientHandler extends AbstractHandler {

		private boolean end;
		
		@Override
		public boolean doMessageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
			if(msg instanceof IcapResponse) {
				IcapResponse response = (IcapResponse)msg;
				DataMockery.assertCreateREQMODWithPostRequestAndDataIcapResponse(response);
				if(response.containsHeader("TEST")) {
					end = true;
				} else {
					ctx.writeAndFlush(DataMockery.createREQMODWithPostRequestAndDataIcapMessage());
				}
			}
			return end;
		}
		
	}
	
	@Test
	public void aggregatorSeparatorCombinationRepeatTest() {
		runSocketTest(new RepeaterServerHandler(),new RepeaterClientHandler(),new Object[]{DataMockery.createREQMODWithPostRequestAndDataIcapMessage()},PipelineType.SEPARATOR_AGGREGATOR);

	}
}

