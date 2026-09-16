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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import junit.framework.Assert;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

public final class DataMockery extends Assert {

	private DataMockery() {

	}
	
	public static final ByteBuf createWhiteSpacePrefixedOPTIONSRequest() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"  OPTIONS icap://icap.mimo.ch:1344/reqmod ICAP/1.0");
		addLine(buffer,"Host: icap.google.com:1344");
		addLine(buffer,"Encapsulated: null-body=0");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final void assertCreateWhiteSpacePrefixedOPTIONSRequest(IcapRequest message) {
		assertEquals("Uri is wrong","icap://icap.mimo.ch:1344/reqmod",message.getUri());
		assertHeaderValue("Host","icap.google.com:1344",message);
		assertHeaderValue("Encapsulated","null-body=0",message);
	}
	
	public static final ByteBuf createOPTIONSRequest() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"OPTIONS icap://icap.mimo.ch:1344/reqmod ICAP/1.0");
		addLine(buffer,"Host: icap.google.com:1344");
		addLine(buffer,"Encapsulated: null-body=0");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final ByteBuf createOPTIONSRequestWithoutEncapsulatedHeader() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"OPTIONS icap://icap.mimo.ch:1344/reqmod ICAP/1.0");
		addLine(buffer,"Host: icap.google.com:1344");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final IcapRequest createOPTIONSIcapRequest() {
		IcapRequest request = new DefaultIcapRequest(IcapVersion.ICAP_1_0,IcapMethod.OPTIONS,"icap://icap.mimo.ch:1344/reqmod","icap.google.com:1344");
		return request;
	}
	
	public static final void assertCreateOPTIONSRequest(IcapRequest message) {
		assertEquals("Uri is wrong","icap://icap.mimo.ch:1344/reqmod",message.getUri());
		assertHeaderValue("Host","icap.google.com:1344",message);
		assertHeaderValue("Encapsulated","null-body=0",message);
	}
	
	public static final ByteBuf createOPTIONSRequestWithBody() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"OPTIONS icap://icap.mimo.ch:1344/reqmod ICAP/1.0");
		addLine(buffer,"Host: icap.google.com:1344");
		addLine(buffer,"Encapsulated: opt-body=0");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final IcapResponse createOPTIONSIcapResponse() {
		IcapResponse response = new DefaultIcapResponse(IcapVersion.ICAP_1_0,IcapResponseStatus.OK);
		response.addHeader("Methods","REQMOD RESPMOD");
		response.addHeader("Service","Joggels icap server 1.0");
		response.addHeader("ISTag","5BDEEEA9-12E4-2");
		response.addHeader("Max-Connections","100");
		response.addHeader("Options-TTL","1000");
		response.addHeader("Allow","204");
		response.addHeader("Preview","1024");
		return response;
	}
	
	public static final ByteBuf createOPTIONSResponse() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"ICAP/1.0 200 OK");
		addLine(buffer,"Methods: REQMOD RESPMOD");
		addLine(buffer,"Service: Joggels icap server 1.0");
		addLine(buffer,"ISTag: 5BDEEEA9-12E4-2");
		addLine(buffer,"Max-Connections: 100");
		addLine(buffer,"Options-TTL: 1000");
		addLine(buffer,"Allow: 204");
		addLine(buffer,"Preview: 1024");
		addLine(buffer,"Encapsulated: null-body=0");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final void assertOPTIONSResponse(IcapResponse response) {
		assertEquals("wrong protocol version",IcapVersion.ICAP_1_0,response.getProtocolVersion());
		assertEquals("response code not as expected",IcapResponseStatus.OK,response.getStatus());
		assertHeaderValue("Methods","REQMOD RESPMOD",response);
		assertHeaderValue("Service","Joggels icap server 1.0",response);
		assertHeaderValue("ISTag","5BDEEEA9-12E4-2",response);
		assertHeaderValue("Max-Connections","100",response);
		assertHeaderValue("Options-TTL","1000",response);
		assertHeaderValue("Allow","204",response);
		assertHeaderValue("Preview","1024",response);
		assertHeaderValue("Encapsulated","null-body=0",response);
	}
	
	public static final IcapResponse createOPTIONSIcapResponseWithBody() {
		IcapResponse response = new DefaultIcapResponse(IcapVersion.ICAP_1_0,IcapResponseStatus.OK);
		response.addHeader("Methods","REQMOD RESPMOD");
		response.addHeader("Service","Joggels icap server 1.0");
		response.addHeader("ISTag","5BDEEEA9-12E4-2");
		response.addHeader("Max-Connections","100");
		response.addHeader("Options-TTL","1000");
		response.addHeader("Allow","204");
		response.addHeader("Preview","1024");
		response.addHeader("Opt-body-type","Simple-text");
		response.setBody(IcapMessageElementEnum.OPTBODY);
		return response;
	}
	
	public static final IcapResponse createOPTIONSResponseWithBodyIcapResponse() {
		IcapResponse response = createOPTIONSIcapResponseWithBody();
		response.addHeader("Encapsulated","opt-body=0");
		return response;
	}
	
	public static final IcapResponse createOPTIONSResponseWithBodyAndContentIcapResponse() {
		IcapResponse response = createOPTIONSIcapResponseWithBody();
		response.addHeader("Encapsulated","opt-body=0");
		response.setContent(Unpooled.wrappedBuffer("Hello World".getBytes()));
		return response;
	}
	
	public static final ByteBuf createOPTIONSResponseWithBody() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"ICAP/1.0 200 OK");
		addLine(buffer,"Methods: REQMOD RESPMOD");
		addLine(buffer,"Service: Joggels icap server 1.0");
		addLine(buffer,"ISTag: 5BDEEEA9-12E4-2");
		addLine(buffer,"Max-Connections: 100");
		addLine(buffer,"Options-TTL: 1000");
		addLine(buffer,"Allow: 204");
		addLine(buffer,"Preview: 1024");
		addLine(buffer,"Opt-body-type: Simple-text");
		addLine(buffer,"Encapsulated: opt-body=0");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final IcapChunk createOPTIONSIcapChunk() {
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("This is a sample Options response body text".getBytes(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk = new DefaultIcapChunk(buffer);
		return chunk;
	}
	
	public static final ByteBuf createOPTIONSChunk() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();;
		addLine(buffer,"2b");
		addLine(buffer,"This is a sample Options response body text");
		return buffer;
	}
	
	public static final IcapChunk createOPTIONSLastIcapChunk() {
		IcapChunk chunk = new DefaultIcapChunk(Unpooled.buffer(0, 0));
		return chunk;
	}
	
	public static final ByteBuf createOPTIONSLastChunk() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"0");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final void assertOPTIONSRequestWithBody(IcapRequest message) {
		assertEquals("Uri is wrong","icap://icap.mimo.ch:1344/reqmod",message.getUri());
		assertEquals("wrong request type",IcapMethod.OPTIONS,message.getMethod());
		assertHeaderValue("Host","icap.google.com:1344",message);
		assertHeaderValue("Encapsulated","opt-body=0",message);
	}
	
	public static final IcapMessage createOPTIONSRequestWithBodyIcapMessage() {
		IcapRequest request = new DefaultIcapRequest(IcapVersion.ICAP_1_0,IcapMethod.OPTIONS,"icap://icap.mimo.ch:1344/reqmod","icap.google.com:1344");
		request.setBody(IcapMessageElementEnum.OPTBODY);
		return request;
	}
	
	public static final ByteBuf createOPTIONSRequestWithBodyBodyChunk() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addChunk(buffer,"This is a options body chunk.");
		return buffer;
	}
	
	public static final IcapChunk createOPTIONSRequestWithBodyBodyChunkIcapChunk() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("This is a options body chunk.".getBytes("ASCII"));
		IcapChunk chunk = new DefaultIcapChunk(buffer);
		return chunk;
	}
	
	public static void assertOPTIONSRequestWithBodyBodyChunk(IcapChunk chunk) {
		assertChunk("options body chunk",chunk,"This is a options body chunk.",false);
	}
	
	public static final ByteBuf createOPTIONSRequestWithBodyLastChunk() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLastChunk(buffer);
		return buffer;
	}
	
	public static final IcapChunk createOPTIONSRequestWithBodyLastChunkIcapChunk() throws UnsupportedEncodingException {
		return new DefaultIcapChunkTrailer();
	}
	
	public static final void assertOPTIONSRequestWithBodyLastChunk(IcapChunk chunk) {
		assertChunk("options last chunk",chunk,null,true);
	}
	
	public static final ByteBuf createREQMODWithGetRequestNoBody() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"REQMOD icap://icap.mimo.ch:1344/reqmod ICAP/1.0");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"Encapsulated: req-hdr=0, null-body=170");
		addLine(buffer,null);
		addLine(buffer,"GET / HTTP/1.1");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain");
		addLine(buffer,"Accept-Encoding: compress");
		addLine(buffer,"Cookie: ff39fk3jur@4ii0e02i");
		addLine(buffer,"If-None-Match: \"xyzzy\", \"r2d2xxxx\"");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final IcapRequest createREQMODWithGetRequestNoBodyIcapMessage() {
		IcapRequest request = new DefaultIcapRequest(IcapVersion.ICAP_1_0,IcapMethod.REQMOD,"icap://icap.mimo.ch:1344/reqmod","icap-server.net");
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.GET,"/");
		request.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain");
		httpRequest.headers().add("Accept-Encoding","compress");
		httpRequest.headers().add("Cookie","ff39fk3jur@4ii0e02i");
		httpRequest.headers().add("If-None-Match","\"xyzzy\", \"r2d2xxxx\"");
		return request;
	}
	
	public static final IcapRequest createREQMODWithGetRequestNoBodyAndEncapsulationHeaderIcapMessage() {
		IcapRequest request = createREQMODWithGetRequestNoBodyIcapMessage();
		request.addHeader("Encapsulated","req-hdr=0, null-body=170");
		return request;
	}
	
	public static final IcapRequest createREQMODWithGetRequestNoBodyAndEncapsulationHeaderAndNullBodySetIcapMessage() {
		IcapRequest request = createREQMODWithGetRequestNoBodyIcapMessage();
		request.addHeader("Encapsulated","req-hdr=0, null-body=170");
		request.setBody(IcapMessageElementEnum.NULLBODY);
		return request;
	}
	
	public static final void assertCreateREQMODWithGetRequestNoBody(IcapRequest message) {
		assertNotNull("the request was null",message);
		assertEquals("Uri is wrong","icap://icap.mimo.ch:1344/reqmod",message.getUri());
		assertHeaderValue("Host","icap-server.net",message);
		assertHeaderValue("Encapsulated","req-hdr=0, null-body=170",message);
		assertNotNull("http request was null",message.getHttpRequest());
		assertEquals("http request method was wrong",HttpMethod.GET,message.getHttpRequest().getMethod());
		assertHttpMessageHeaderValue("Host","www.origin-server.com",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept","text/html, text/plain",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept-Encoding","compress",message.getHttpRequest());
		assertHttpMessageHeaderValue("Cookie","ff39fk3jur@4ii0e02i",message.getHttpRequest());
		assertHttpMessageHeaderValue("If-None-Match","\"xyzzy\", \"r2d2xxxx\"",message.getHttpRequest());
	}
	
	public static final IcapResponse createREQMODWithGetRequestNoBodyIcapResponse() {
		IcapResponse response = new DefaultIcapResponse(IcapVersion.ICAP_1_0,IcapResponseStatus.OK);
		response.addHeader("Host","icap-server.net");
		response.addHeader("ISTag","Serial-0815");
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.GET,"/");
		response.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain");
		httpRequest.headers().add("Accept-Encoding","compress");
		httpRequest.headers().add("Cookie","ff39fk3jur@4ii0e02i");
		httpRequest.headers().add("If-None-Match","\"xyzzy\", \"r2d2xxxx\"");
		return response;
	}
	
	public static final ByteBuf createREQMODWithGetRequestResponse() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"ICAP/1.0 200 OK");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"ISTag: Serial-0815");
		addLine(buffer,"Encapsulated: req-hdr=0, null-body=170");
		addLine(buffer,null);
		addLine(buffer,"GET / HTTP/1.1");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain");
		addLine(buffer,"Accept-Encoding: compress");
		addLine(buffer,"Cookie: ff39fk3jur@4ii0e02i");
		addLine(buffer,"If-None-Match: \"xyzzy\", \"r2d2xxxx\"");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final ByteBuf createREQMODResponseContainingHttpResponse() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"ICAP/1.0 200 OK");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"ISTag: Serial-0815");
		addLine(buffer,"Encapsulated: res-hdr=0, null-body=171");
		addLine(buffer,null);
		addLine(buffer,"HTTP/1.1 200 OK");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain");
		addLine(buffer,"Accept-Encoding: compress");
		addLine(buffer,"Cookie: ff39fk3jur@4ii0e02i");
		addLine(buffer,"If-None-Match: \"xyzzy\", \"r2d2xxxx\"");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final void assertREQMODResponseContainingHttpResponse(IcapResponse response) {
		assertNotNull("response was null",response);
		assertEquals("wrong icap version",IcapVersion.ICAP_1_0,response.getProtocolVersion());
		assertEquals("wrong icap status code",IcapResponseStatus.OK,response.getStatus());
		assertHeaderValue("Host","icap-server.net",response);
		assertHeaderValue("ISTag","Serial-0815",response);
		assertNotNull("Http response was null",response.getHttpResponse());
		HttpResponse httpResponse = response.getHttpResponse();
		assertEquals("wrong http version",HttpVersion.HTTP_1_1,httpResponse.getProtocolVersion());
		assertEquals("wrong http status code",HttpResponseStatus.OK,httpResponse.getStatus());
		assertHttpMessageHeaderValue("Host","www.origin-server.com",httpResponse);
		assertHttpMessageHeaderValue("Accept","text/html, text/plain",httpResponse);
		assertHttpMessageHeaderValue("Accept-Encoding","compress",httpResponse);
		assertHttpMessageHeaderValue("Cookie","ff39fk3jur@4ii0e02i",httpResponse);
		assertHttpMessageHeaderValue("If-None-Match","\"xyzzy\", \"r2d2xxxx\"",httpResponse);
	}
	
	public static final IcapResponse createREQMODResponseContainingHttpResponseIcapResponse() {
		IcapResponse response = new DefaultIcapResponse(IcapVersion.ICAP_1_0,IcapResponseStatus.OK);
		response.addHeader("Host","icap-server.net");
		response.addHeader("ISTag","Serial-0815");
		FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.setHttpResponse(httpResponse);
		httpResponse.headers().add("Host","www.origin-server.com");
		httpResponse.headers().add("Accept","text/html, text/plain");
		httpResponse.headers().add("Accept-Encoding","compress");
		httpResponse.headers().add("Cookie","ff39fk3jur@4ii0e02i");
		httpResponse.headers().add("If-None-Match","\"xyzzy\", \"r2d2xxxx\"");
		return response;
	}
	
	public static final void assertREQMODWithGetRequestResponse(IcapResponse response) {
		assertEquals("wrong protocol version",IcapVersion.ICAP_1_0,response.getProtocolVersion());
		assertEquals("response code not as expected",IcapResponseStatus.OK,response.getStatus());
		assertHeaderValue("Host","icap-server.net",response);
		assertHeaderValue("ISTag","Serial-0815",response);
		assertHeaderValue("Encapsulated","req-hdr=0, null-body=170",response);
		HttpRequest httpRequest = response.getHttpRequest();
		assertNotNull("http request was null",httpRequest);
		assertEquals("http request was of wrong type",HttpMethod.GET,httpRequest.getMethod());
		assertEquals("http request was of wrong version",HttpVersion.HTTP_1_1,httpRequest.getProtocolVersion());
		assertHttpMessageHeaderValue("Host","www.origin-server.com",httpRequest);
		assertHttpMessageHeaderValue("Accept","text/html, text/plain",httpRequest);
		assertHttpMessageHeaderValue("Accept-Encoding","compress",httpRequest);
		assertHttpMessageHeaderValue("Cookie","ff39fk3jur@4ii0e02i",httpRequest);
		assertHttpMessageHeaderValue("If-None-Match","\"xyzzy\", \"r2d2xxxx\"",httpRequest);

	}
	
	public static final ByteBuf createRESPMODWithGetRequestNoBody() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"RESPMOD icap://icap.mimo.ch:1344/reqmod ICAP/1.0");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"Encapsulated: req-hdr=0, res-hdr=137, null-body=296");
		addLine(buffer,null);
		addLine(buffer,"GET /origin-resource HTTP/1.1");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain, image/gif");
		addLine(buffer,"Accept-Encoding: gzip, compress");
		addLine(buffer,null);
		addLine(buffer,"HTTP/1.1 200 OK");
		addLine(buffer,"Date: Mon, 10 Jan 2000 09:52:22 GMT");
		addLine(buffer,"Server: Apache/1.3.6 (Unix)");
		addLine(buffer,"ETag: \"63840-1ab7-378d415b\"");
		addLine(buffer,"Content-Type: text/html");
		addLine(buffer,"Content-Length: 51");
		addLine(buffer,null);
		return buffer;
	}	
	
	public static final IcapMessage createRESPMODWithGetRequestNoBodyIcapMessage() {
		IcapRequest request = new DefaultIcapRequest(IcapVersion.ICAP_1_0,IcapMethod.RESPMOD,"icap://icap.mimo.ch:1344/reqmod","icap-server.net");
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.GET,"/origin-resource");
		request.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain, image/gif");
		httpRequest.headers().add("Accept-Encoding","gzip, compress");
		FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,HttpResponseStatus.OK);
		request.setHttpResponse(httpResponse);
		httpResponse.headers().add("Date","Mon, 10 Jan 2000 09:52:22 GMT");
		httpResponse.headers().add("Server","Apache/1.3.6 (Unix)");
		httpResponse.headers().add("ETag","\"63840-1ab7-378d415b\"");
		httpResponse.headers().add("Content-Type","text/html");
		httpResponse.headers().add("Content-Length","51");
		return request;
	}
	
	public static final void assertCreateRESPMODWithGetRequestNoBody(IcapRequest message) {
		assertEquals("Uri is wrong","icap://icap.mimo.ch:1344/reqmod",message.getUri());
		assertHeaderValue("Host","icap-server.net",message);
		assertHeaderValue("Encapsulated","req-hdr=0, res-hdr=137, null-body=296",message);
		assertNotNull("http request was null",message.getHttpRequest());
		assertEquals("http request method was wrong",HttpMethod.GET,message.getHttpRequest().getMethod());
		assertHttpMessageHeaderValue("Host","www.origin-server.com",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept","text/html, text/plain, image/gif",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept-Encoding","gzip, compress",message.getHttpRequest());
		assertNotNull("http response was null",message.getHttpResponse());
		assertEquals("http response status was wrong",HttpResponseStatus.OK,message.getHttpResponse().getStatus());
		assertHttpMessageHeaderValue("Date","Mon, 10 Jan 2000 09:52:22 GMT",message.getHttpResponse());
		assertHttpMessageHeaderValue("Server","Apache/1.3.6 (Unix)",message.getHttpResponse());
		assertHttpMessageHeaderValue("ETag","\"63840-1ab7-378d415b\"",message.getHttpResponse());
		assertHttpMessageHeaderValue("Content-Type","text/html",message.getHttpResponse());
		assertHttpMessageHeaderValue("Content-Length","51",message.getHttpResponse());
	}
	
	public static final IcapResponse createRESPMODWithGetRequestNoBodyIcapResponse() {
		IcapResponse response = new DefaultIcapResponse(IcapVersion.ICAP_1_0,IcapResponseStatus.OK);
		response.addHeader("Host","icap-server.net");
		response.addHeader("ISTag","Serial-0815");
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.GET,"/origin-resource");
		response.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain, image/gif");
		httpRequest.headers().add("Accept-Encoding","gzip, compress");
		FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,HttpResponseStatus.OK);
		response.setHttpResponse(httpResponse);
		httpResponse.headers().add("Date","Mon, 10 Jan 2000 09:52:22 GMT");
		httpResponse.headers().add("Server","Apache/1.3.6 (Unix)");
		httpResponse.headers().add("ETag","\"63840-1ab7-378d415b\"");
		httpResponse.headers().add("Content-Type","text/html");
		httpResponse.headers().add("Content-Length","51");
		return response;
	}
	
	public static final ByteBuf createRESPMODWithGetRequestNoBodyResponse() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"ICAP/1.0 200 OK");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"ISTag: Serial-0815");
		addLine(buffer,"Encapsulated: req-hdr=0, res-hdr=137, null-body=296");
		addLine(buffer,null);
		addLine(buffer,"GET /origin-resource HTTP/1.1");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain, image/gif");
		addLine(buffer,"Accept-Encoding: gzip, compress");
		addLine(buffer,null);
		addLine(buffer,"HTTP/1.1 200 OK");
		addLine(buffer,"Date: Mon, 10 Jan 2000 09:52:22 GMT");
		addLine(buffer,"Server: Apache/1.3.6 (Unix)");
		addLine(buffer,"ETag: \"63840-1ab7-378d415b\"");
		addLine(buffer,"Content-Type: text/html");
		addLine(buffer,"Content-Length: 51");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final void assertRESPMODWithGetRequestNoBodyResponse(IcapResponse response) {
		assertEquals("wrong protocol version",IcapVersion.ICAP_1_0,response.getProtocolVersion());
		assertEquals("response code not as expected",IcapResponseStatus.OK,response.getStatus());
		assertHeaderValue("Host","icap-server.net",response);
		assertHeaderValue("ISTag","Serial-0815",response);
		assertHeaderValue("Encapsulated","req-hdr=0, res-hdr=137, null-body=296",response);
		HttpRequest httpRequest = response.getHttpRequest();
		assertNotNull("http request was null",httpRequest);
		assertEquals("http request was of wrong type",HttpMethod.GET,httpRequest.getMethod());
		assertEquals("http request was of wrong version",HttpVersion.HTTP_1_1,httpRequest.getProtocolVersion());
		assertHttpMessageHeaderValue("Host","www.origin-server.com",httpRequest);
		assertHttpMessageHeaderValue("Accept","text/html, text/plain, image/gif",httpRequest);
		assertHttpMessageHeaderValue("Accept-Encoding","gzip, compress",httpRequest);
		HttpResponse httpResponse = response.getHttpResponse();
		assertNotNull("http response was null",httpResponse);
		assertEquals("http response was of wrong version",HttpVersion.HTTP_1_1,httpResponse.getProtocolVersion());
		assertEquals("http response status was wrong",HttpResponseStatus.OK,httpResponse.getStatus());
		assertHttpMessageHeaderValue("Date","Mon, 10 Jan 2000 09:52:22 GMT",httpResponse);
		assertHttpMessageHeaderValue("Server","Apache/1.3.6 (Unix)",httpResponse);
		assertHttpMessageHeaderValue("ETag","\"63840-1ab7-378d415b\"",httpResponse);
		assertHttpMessageHeaderValue("Content-Type","text/html",httpResponse);
		assertHttpMessageHeaderValue("Content-Length","51",httpResponse);
	}
	
	
	public static final ByteBuf createRESPMODWithGetRequestNoBodyAndReverseRequestAlignement() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"RESPMOD icap://icap.mimo.ch:1344/reqmod ICAP/1.0");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"Encapsulated: res-hdr=0, req-hdr=137, null-body=296");
		addLine(buffer,null);
		addLine(buffer,"HTTP/1.1 200 OK");
		addLine(buffer,"Date: Mon, 10 Jan 2000 09:52:22 GMT");
		addLine(buffer,"Server: Apache/1.3.6 (Unix)");
		addLine(buffer,"ETag: \"63840-1ab7-378d415b\"");
		addLine(buffer,"Content-Type: text/html");
		addLine(buffer,"Content-Length: 51");
		addLine(buffer,null);
		addLine(buffer,"GET /origin-resource HTTP/1.1");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain, image/gif");
		addLine(buffer,"Accept-Encoding: gzip, compress");
		addLine(buffer,null);
		return buffer;
	}	
	
	public static final void assertCreateRESPMODWithGetRequestNoBodyAndReverseRequestAlignement(IcapRequest message) {
		assertEquals("Uri is wrong","icap://icap.mimo.ch:1344/reqmod",message.getUri());
		assertHeaderValue("Host","icap-server.net",message);
		assertHeaderValue("Encapsulated","res-hdr=0, req-hdr=137, null-body=296",message);
		assertNotNull("http request was null",message.getHttpRequest());
		assertEquals("http request method was wrong",HttpMethod.GET,message.getHttpRequest().getMethod());
		assertHttpMessageHeaderValue("Host","www.origin-server.com",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept","text/html, text/plain, image/gif",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept-Encoding","gzip, compress",message.getHttpRequest());
		assertNotNull("http response was null",message.getHttpResponse());
		assertEquals("http response status was wrong",HttpResponseStatus.OK,message.getHttpResponse().getStatus());
		assertHttpMessageHeaderValue("Date","Mon, 10 Jan 2000 09:52:22 GMT",message.getHttpResponse());
		assertHttpMessageHeaderValue("Server","Apache/1.3.6 (Unix)",message.getHttpResponse());
		assertHttpMessageHeaderValue("ETag","\"63840-1ab7-378d415b\"",message.getHttpResponse());
		assertHttpMessageHeaderValue("Content-Type","text/html",message.getHttpResponse());
		assertHttpMessageHeaderValue("Content-Length","51",message.getHttpResponse());
	}
	
	
	public static final ByteBuf createREQMODWithTwoChunkBody() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"REQMOD icap://icap.mimo.ch:1344/reqmod ICAP/1.0");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"Encapsulated: req-hdr=0, req-body=171");
		addLine(buffer,null);
		addLine(buffer,"POST / HTTP/1.1");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain");
		addLine(buffer,"Accept-Encoding: compress");
		addLine(buffer,"Cookie: ff39fk3jur@4ii0e02i");
		addLine(buffer,"If-None-Match: \"xyzzy\", \"r2d2xxxx\"");
		addLine(buffer,null);
		addChunk(buffer,"This is data that was returned by an origin server.");
		addChunk(buffer,"And this the second chunk which contains more information.");
		addLastChunk(buffer);
		return buffer;
	}
	
	public static final ByteBuf createREQMODWithImplicitTwoChunkBodyResponse() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"ICAP/1.0 200 OK");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"Encapsulated: req-hdr=0, req-body=169");
		addLine(buffer,null);
		addLine(buffer,"POST / HTTP/1.1");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain");
		addLine(buffer,"Accept-Encoding: compress");
		addLine(buffer,"Cookie: ff39fk3jur@4ii0e02i");
		addLine(buffer,"If-None-Match: \"xyzzy\", \"r2d2xxxx\"");
		addLine(buffer,null);
		addChunk(buffer,"This is data that was returned by an origin server.");
		addChunk(buffer,"And this the second chunk which contains more information.");
		addLastChunk(buffer);
		return buffer;
	}
	
	public static final void assertCreateREQMODWithImplicitTwoChunkBodyResponse(IcapResponse message) {
		assertEquals("response was of wrong version",IcapVersion.ICAP_1_0,message.getProtocolVersion());
		assertEquals("response had wrong code",IcapResponseStatus.OK,message.getStatus());
		assertHeaderValue("Host","icap-server.net",message);
		assertHeaderValue("Encapsulated","req-hdr=0, req-body=169",message);
		assertNotNull("http request was null",message.getHttpRequest());
		assertEquals("http request method was wrong",HttpMethod.POST,message.getHttpRequest().getMethod());
		assertHttpMessageHeaderValue("Host","www.origin-server.com",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept","text/html, text/plain",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept-Encoding","compress",message.getHttpRequest());
		assertHttpMessageHeaderValue("Cookie","ff39fk3jur@4ii0e02i",message.getHttpRequest());
		assertHttpMessageHeaderValue("If-None-Match","\"xyzzy\", \"r2d2xxxx\"",message.getHttpRequest());
	}
	
	public static final ByteBuf createREQMODWithTwoChunkBodyAnnouncement() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"REQMOD icap://icap.mimo.ch:1344/reqmod ICAP/1.0");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"Encapsulated: req-hdr=0, req-body=171");
		addLine(buffer,null);
		addLine(buffer,"POST / HTTP/1.1");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain");
		addLine(buffer,"Accept-Encoding: compress");
		addLine(buffer,"Cookie: ff39fk3jur@4ii0e02i");
		addLine(buffer,"If-None-Match: \"xyzzy\", \"r2d2xxxx\"");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final IcapMessage createREQMODWithTwoChunkBodyIcapMessage() {
		IcapRequest request = new DefaultIcapRequest(IcapVersion.ICAP_1_0,IcapMethod.REQMOD,"icap://icap.mimo.ch:1344/reqmod","icap-server.net");
		request.setBody(IcapMessageElementEnum.REQBODY);
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.POST,"/");
		request.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain");
		httpRequest.headers().add("Accept-Encoding","compress");
		httpRequest.headers().add("Cookie","ff39fk3jur@4ii0e02i");
		httpRequest.headers().add("If-None-Match","\"xyzzy\", \"r2d2xxxx\"");
		return request;
	}
	
	public static final IcapMessage createREQMODWithTwoChunkBodyAndEncapsulationHeaderIcapMessage() {
		IcapMessage request = createREQMODWithTwoChunkBodyIcapMessage();
		request.addHeader("Encapsulated","req-hdr=0, req-body=171");
		return request;
	}
	
	public static final IcapMessage createREQMODWithBodyContentIcapMessage() {
		IcapMessage request = createREQMODWithTwoChunkBodyIcapMessage();
		request.addHeader("Encapsulated","req-hdr=0, req-body=171");
		request.setHttpRequest(request.getHttpRequest().replace(Unpooled.wrappedBuffer("Hello World".getBytes())));
		return request;
	}
	
	public static final IcapChunk createREQMODWithTwoChunkBodyIcapChunkOne() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("This is data that was returned by an origin server.".getBytes("ASCII"));
		IcapChunk chunk = new DefaultIcapChunk(buffer);
		return chunk;
	}
	
	public static final ByteBuf createREQMODWithTowChunkBodyChunkOne() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addChunk(buffer,"This is data that was returned by an origin server.");
		return buffer;
	}
	
	public static final IcapChunk createREQMODWithTwoChunkBodyIcapChunkTwo() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("And this the second chunk which contains more information.".getBytes("ASCII"));
		IcapChunk chunk = new DefaultIcapChunk(buffer);
		return chunk;
	}
	
	public static final ByteBuf createREQMODWithTwoChunkBodyChunkTwo() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addChunk(buffer,"And this the second chunk which contains more information.");
		return buffer;
	}
	
	public static final IcapChunk createREQMODWithTwoChunkBodyIcapChunkThree() throws UnsupportedEncodingException {
		return new DefaultIcapChunkTrailer();
	}
	
	public static final ByteBuf createREQMODWithTwoChunkBodyChunkThree() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLastChunk(buffer);
		return buffer;
	}
	
	public static final IcapChunkTrailer createREQMODWithTwoChunkBodyChunkThreeIcapChunkTrailer() throws UnsupportedEncodingException {
		IcapChunkTrailer trailer = new DefaultIcapChunkTrailer();
		trailer.trailingHeaders().add("TrailingHeaderKey1","TrailingHeaderValue1");
		trailer.trailingHeaders().add("TrailingHeaderKey2","TrailingHeaderValue2");
		trailer.trailingHeaders().add("TrailingHeaderKey3","TrailingHeaderValue3");
		trailer.trailingHeaders().add("TrailingHeaderKey4","TrailingHeaderValue4");
		return trailer;
	}
	
	public static final ByteBuf createREQMODWithTwoChunkBodyChunkThreeWithTrailer() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"0");
		addLine(buffer,"TrailingHeaderKey1: TrailingHeaderValue1");
		addLine(buffer,"TrailingHeaderKey2: TrailingHeaderValue2");
		addLine(buffer,"TrailingHeaderKey3: TrailingHeaderValue3");
		addLine(buffer,"TrailingHeaderKey4: TrailingHeaderValue4");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final ByteBuf createREQMODWithTwoChunkBodyAndTrailingHeaders() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"REQMOD icap://icap.mimo.ch:1344/reqmod ICAP/1.0");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"Encapsulated: req-hdr=0, req-body=171");
		addLine(buffer,null);
		addLine(buffer,"POST / HTTP/1.1");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain");
		addLine(buffer,"Accept-Encoding: compress");
		addLine(buffer,"Cookie: ff39fk3jur@4ii0e02i");
		addLine(buffer,"If-None-Match: \"xyzzy\", \"r2d2xxxx\"");
		addLine(buffer,null);
		addChunk(buffer,"This is data that was returned by an origin server.");
		addChunk(buffer,"And this the second chunk which contains more information.");
		addLine(buffer,"0");
		addLine(buffer,"TrailingHeaderKey1: TrailingHeaderValue1");
		addLine(buffer,"TrailingHeaderKey2: TrailingHeaderValue2");
		addLine(buffer,"TrailingHeaderKey3: TrailingHeaderValue3");
		addLine(buffer,"TrailingHeaderKey4: TrailingHeaderValue4");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final void assertCreateREQMODWithTwoChunkBody(IcapRequest message) {
		assertEquals("Uri is wrong","icap://icap.mimo.ch:1344/reqmod",message.getUri());
		assertHeaderValue("Host","icap-server.net",message);
		assertHeaderValue("Encapsulated","req-hdr=0, req-body=171",message);
		assertNotNull("http request was null",message.getHttpRequest());
		assertEquals("http request method was wrong",HttpMethod.POST,message.getHttpRequest().getMethod());
		assertHttpMessageHeaderValue("Host","www.origin-server.com",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept","text/html, text/plain",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept-Encoding","compress",message.getHttpRequest());
		assertHttpMessageHeaderValue("Cookie","ff39fk3jur@4ii0e02i",message.getHttpRequest());
		assertHttpMessageHeaderValue("If-None-Match","\"xyzzy\", \"r2d2xxxx\"",message.getHttpRequest());
	}
	
	public static final void assertCreateREQMODWithTwoChunkBodyFirstChunk(IcapChunk chunk) {
		assertChunk("first chunk",chunk,"This is data that was returned by an origin server.",false);
	}
	
	public static final void assertCreateREQMODWithTwoChunkBodySecondChunk(IcapChunk chunk) {
		assertChunk("second chunk",chunk,"And this the second chunk which contains more information.",false);
	}
	
	public static final void assertCreateREQMODWithTwoChunkBodyThirdChunk(IcapChunk chunk) {
		assertTrue("last chunk is wrong type",chunk instanceof IcapChunkTrailer);
		assertChunk("third chunk",chunk,null,true);
	}
	
	public static final void assertCreateREQMODWithTwoChunkBodyTrailingHeaderChunk(IcapChunkTrailer trailer) {
		assertNotNull("trailer is null",trailer);
		assertTrailingHeaderValue("TrailingHeaderKey1","TrailingHeaderValue1",trailer);
		assertTrailingHeaderValue("TrailingHeaderKey2","TrailingHeaderValue2",trailer);
		assertTrailingHeaderValue("TrailingHeaderKey3","TrailingHeaderValue3",trailer);
		assertTrailingHeaderValue("TrailingHeaderKey4","TrailingHeaderValue4",trailer);
	}
	
	public static final IcapResponse createREQMODWithTwoChunkBodyIcapResponse() {
		IcapResponse response = new DefaultIcapResponse(IcapVersion.ICAP_1_0,IcapResponseStatus.OK);
		response.addHeader("Host","icap-server.net");
		response.addHeader("ISTag","Serial-0815");
		response.setBody(IcapMessageElementEnum.REQBODY);
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.POST,"/");
		response.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain");
		httpRequest.headers().add("Accept-Encoding","compress");
		httpRequest.headers().add("Cookie","ff39fk3jur@4ii0e02i");
		httpRequest.headers().add("If-None-Match","\"xyzzy\", \"r2d2xxxx\"");
		return response;
	}
	
	public static final void assertREQMODWithTwoChunkBodyResponse(IcapResponse response) {
		assertHeaderValue("Host","icap-server.net",response);
		assertHeaderValue("ISTag","Serial-0815",response);
		assertHeaderValue("Encapsulated","req-hdr=0, req-body=171",response);
		assertNotNull("http request was null",response.getHttpRequest());
		assertEquals("http request method was wrong",HttpMethod.POST,response.getHttpRequest().getMethod());
		assertHttpMessageHeaderValue("Host","www.origin-server.com",response.getHttpRequest());
		assertHttpMessageHeaderValue("Accept","text/html, text/plain",response.getHttpRequest());
		assertHttpMessageHeaderValue("Accept-Encoding","compress",response.getHttpRequest());
		assertHttpMessageHeaderValue("Cookie","ff39fk3jur@4ii0e02i",response.getHttpRequest());
		assertHttpMessageHeaderValue("If-None-Match","\"xyzzy\", \"r2d2xxxx\"",response.getHttpRequest());
	}
	
	public static final ByteBuf createREQMODWithTwoChunkBodyResponse() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"ICAP/1.0 200 OK");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"ISTag: Serial-0815");
		addLine(buffer,"Encapsulated: req-hdr=0, req-body=171");
		addLine(buffer,null);
		addLine(buffer,"POST / HTTP/1.1");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain");
		addLine(buffer,"Accept-Encoding: compress");
		addLine(buffer,"Cookie: ff39fk3jur@4ii0e02i");
		addLine(buffer,"If-None-Match: \"xyzzy\", \"r2d2xxxx\"");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final ByteBuf createREQMODWithPreview() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"REQMOD icap://icap.mimo.ch:1344/reqmod ICAP/1.0");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"Preview: 51");
		addLine(buffer,"Encapsulated: req-hdr=0, req-body=171");
		addLine(buffer,null);
		addLine(buffer,"POST / HTTP/1.1");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain");
		addLine(buffer,"Accept-Encoding: compress");
		addLine(buffer,"Cookie: ff39fk3jur@4ii0e02i");
		addLine(buffer,"If-None-Match: \"xyzzy\", \"r2d2xxxx\"");
		addLine(buffer,null);
		addChunk(buffer,"This is data that was returned by an origin server.");
		addLastChunk(buffer);
		return buffer;
	}
	
	public static final ByteBuf createREQMODWithPreviewAnnouncement() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"REQMOD icap://icap.mimo.ch:1344/reqmod ICAP/1.0");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"Preview: 51");
		addLine(buffer,"Encapsulated: req-hdr=0, req-body=171");
		addLine(buffer,null);
		addLine(buffer,"POST / HTTP/1.1");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain");
		addLine(buffer,"Accept-Encoding: compress");
		addLine(buffer,"Cookie: ff39fk3jur@4ii0e02i");
		addLine(buffer,"If-None-Match: \"xyzzy\", \"r2d2xxxx\"");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final IcapMessage createREQMODWithPreviewAnnouncementIcapMessage() {
		IcapRequest request = new DefaultIcapRequest(IcapVersion.ICAP_1_0,IcapMethod.REQMOD,"icap://icap.mimo.ch:1344/reqmod","icap-server.net");
		request.addHeader("Preview","51");
		request.setBody(IcapMessageElementEnum.REQBODY);
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.POST,"/");
		request.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain");
		httpRequest.headers().add("Accept-Encoding","compress");
		httpRequest.headers().add("Cookie","ff39fk3jur@4ii0e02i");
		httpRequest.headers().add("If-None-Match","\"xyzzy\", \"r2d2xxxx\"");
		return request;
	}
	
	public static final IcapMessage createREQMODWithPreviewAnnouncement204ResponseIcapMessage() {
		return new DefaultIcapResponse(IcapVersion.ICAP_1_0,IcapResponseStatus.NO_CONTENT);
	}
	
	public static final IcapResponse createREQMODWithPreviewAnnouncement100ContinueIcapMessage() {
		return new DefaultIcapResponse(IcapVersion.ICAP_1_0,IcapResponseStatus.CONTINUE);
	}
	
	public static final void assertCreateREQMODWithPreviewAnnouncement204Response(IcapResponse response) {
		assertNotNull("response was null",response);
		assertEquals("wrong icap version",IcapVersion.ICAP_1_0,response.getProtocolVersion());
		assertEquals("wrong response status",IcapResponseStatus.NO_CONTENT,response.getStatus());
	}
	
	public static final IcapChunk createREQMODWithPreviewIcapChunk() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("This is data that was returned by an origin server.".getBytes("ASCII"));
		IcapChunk chunk = new DefaultIcapChunk(buffer);
		chunk.setPreviewChunk(true);
		return chunk;
	}
	
	public static final ByteBuf createREQMODWithPreviewChunk() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addChunk(buffer,"This is data that was returned by an origin server.");
		return buffer;
	}
	
	public static final IcapChunk createREQMODWithPreview100ContinueIcapChunk() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("This is the second chunk that is received when 100 continue was sent.".getBytes("ASCII"));
		IcapChunk chunk = new DefaultIcapChunk(buffer);
		return chunk;
	}
	
	public static final IcapChunk createREQMODWithPreview100ContinueLastIcapChunk() {
		return new DefaultIcapChunkTrailer(false,false);
	}
	
	public static final IcapChunk createREQMODWithPreviewLastIcapChunk() throws UnsupportedEncodingException {
		return new DefaultIcapChunkTrailer(true,false);
	}
	
	public static final ByteBuf createREQMODWithPreviewLastChunk() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLastChunk(buffer);
		return buffer;
	}
	
	
	
	public static final void assertCreateREQMODWithPreview(IcapRequest message) {
		assertEquals("Uri is wrong","icap://icap.mimo.ch:1344/reqmod",message.getUri());
		assertHeaderValue("Host","icap-server.net",message);
		assertHeaderValue("Encapsulated","req-hdr=0, req-body=171",message);
		assertHeaderValue("Preview","51",message);
		assertNotNull("http request was null",message.getHttpRequest());
		assertEquals("http request method was wrong",HttpMethod.POST,message.getHttpRequest().getMethod());
		assertHttpMessageHeaderValue("Host","www.origin-server.com",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept","text/html, text/plain",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept-Encoding","compress",message.getHttpRequest());
		assertHttpMessageHeaderValue("Cookie","ff39fk3jur@4ii0e02i",message.getHttpRequest());
		assertHttpMessageHeaderValue("If-None-Match","\"xyzzy\", \"r2d2xxxx\"",message.getHttpRequest());
	}
	
	public static final void assertCreateREQMODWithPreviewChunk(IcapChunk chunk) {
		assertTrue("preview chunk is not marked as such",chunk.isPreviewChunk());
		assertFalse("preview chunk indicated that is is early terminated",chunk.isEarlyTerminated());
		assertChunk("preview chunk", chunk,"This is data that was returned by an origin server.",false);
	}
	
	public static final void assertCreateREQMODWithPreviewChunkLastChunk(IcapChunk chunk) {
		assertTrue("last chunk is wrong type",chunk instanceof IcapChunkTrailer);
		assertTrue("preview chunk is not marked as such",chunk.isPreviewChunk());
		assertTrue("preview chunk is not last chunk",chunk.isLast());
		assertFalse("preview chunk states that it is early terminated",chunk.isEarlyTerminated());
	}
	
	public static final void assertCreateREQMODWithPreview100ContinueChunk(IcapChunk chunk) {
		assertChunk("preview chunk", chunk,"This is the second chunk that is received when 100 continue was sent.",false);
	}
	
	public static final ByteBuf createREQMODWithEarlyTerminatedPreview() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"REQMOD icap://icap.mimo.ch:1344/reqmod ICAP/1.0");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"Preview: 151");
		addLine(buffer,"Encapsulated: req-hdr=0, req-body=169");
		addLine(buffer,null);
		addLine(buffer,"POST / HTTP/1.1");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain");
		addLine(buffer,"Accept-Encoding: compress");
		addLine(buffer,"Cookie: ff39fk3jur@4ii0e02i");
		addLine(buffer,"If-None-Match: \"xyzzy\", \"r2d2xxxx\"");
		addLine(buffer,null);
		addLine(buffer,"33");
		addLine(buffer,"This is data that was returned by an origin server.");
		addLine(buffer,"0; ieof");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final IcapMessage createREQMODWithEarlyTerminatedPreviewAnnouncementIcapMessage() {
		IcapRequest request = new DefaultIcapRequest(IcapVersion.ICAP_1_0,IcapMethod.REQMOD,"icap://icap.mimo.ch:1344/reqmod","icap-server.net");
		request.addHeader("Preview","151");
		request.setBody(IcapMessageElementEnum.REQBODY);
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.POST,"/");
		request.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain");
		httpRequest.headers().add("Accept-Encoding","compress");
		httpRequest.headers().add("Cookie","ff39fk3jur@4ii0e02i");
		httpRequest.headers().add("If-None-Match","\"xyzzy\", \"r2d2xxxx\"");
		return request;
	}
	
	public static final IcapChunk createREQMODWithEarlyTerminatedPreviewIcapChunk() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("This is data that was returned by an origin server.".getBytes("ASCII"));
		IcapChunk chunk = new DefaultIcapChunk(buffer);
		chunk.setPreviewChunk(true);
		return chunk;
	}
	
	public static final ByteBuf createREQMODWithEarlyTerminatedPreviewChunk() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addChunk(buffer,"This is data that was returned by an origin server.");
		return buffer;
	}
	
	public static final IcapChunk createREQMODWithEarlyTerminatedPreviewLastIcapChunk() throws UnsupportedEncodingException {
		IcapChunk chunk = new DefaultIcapChunk(Unpooled.buffer(0, 0));
		chunk.setPreviewChunk(true);
		chunk.setEarlyTermination(true);
		return chunk;
	}
	
	public static final ByteBuf createREQMODWithEarlyTerminatedPreviewLastChunk() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"0; ieof");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final ByteBuf createREQMODWithEarlyTerminatedPreviewAnnouncement() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"REQMOD icap://icap.mimo.ch:1344/reqmod ICAP/1.0");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"Preview: 151");
		addLine(buffer,"Encapsulated: req-hdr=0, req-body=171");
		addLine(buffer,null);
		addLine(buffer,"POST / HTTP/1.1");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain");
		addLine(buffer,"Accept-Encoding: compress");
		addLine(buffer,"Cookie: ff39fk3jur@4ii0e02i");
		addLine(buffer,"If-None-Match: \"xyzzy\", \"r2d2xxxx\"");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final void assertCreateREQMODWithEarlyTerminatedPreview(IcapRequest message) {
		assertEquals("Uri is wrong","icap://icap.mimo.ch:1344/reqmod",message.getUri());
		assertHeaderValue("Host","icap-server.net",message);
		assertHeaderValue("Encapsulated","req-hdr=0, req-body=169",message);
		assertHeaderValue("Preview","151",message);
		assertNotNull("http request was null",message.getHttpRequest());
		assertEquals("http request method was wrong",HttpMethod.POST,message.getHttpRequest().getMethod());
		assertHttpMessageHeaderValue("Host","www.origin-server.com",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept","text/html, text/plain",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept-Encoding","compress",message.getHttpRequest());
		assertHttpMessageHeaderValue("Cookie","ff39fk3jur@4ii0e02i",message.getHttpRequest());
		assertHttpMessageHeaderValue("If-None-Match","\"xyzzy\", \"r2d2xxxx\"",message.getHttpRequest());
	}
	
	public static final void assertCreateREQMODWithEarlyTerminatedPreview(IcapChunk chunk) {
		assertNotNull("preview chunk was null",chunk);
		assertTrue("preview chunk is not marked as such",chunk.isPreviewChunk());
		assertFalse("preview chunk does not indicated that is is early terminated",chunk.isEarlyTerminated());
		assertChunk("preview chunk", chunk,"This is data that was returned by an origin server.",false);
	}
	
	public static final void assertCreateREQMODWithEarlyTerminatedPreviewLastChunk(IcapChunk chunk) {
		assertTrue("last chunk is wrong type",chunk instanceof IcapChunkTrailer);
		assertNotNull("preview last chunk was null",chunk);
		assertTrue("preview chunk is not marked as such",chunk.isPreviewChunk());
		assertTrue("preview chunk is not last chunk",chunk.isLast());
		assertTrue("preview chunk is not early terminated",chunk.isEarlyTerminated());
	}

	public static final ByteBuf createRESPMODWithGetRequestAndPreview() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"RESPMOD icap://icap.mimo.ch:1344/reqmod ICAP/1.0");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"Encapsulated: req-hdr=0, res-hdr=137, res-body=296");
		addLine(buffer,"Preview: 51");
		addLine(buffer,null);
		addLine(buffer,"GET /origin-resource HTTP/1.1");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain, image/gif");
		addLine(buffer,"Accept-Encoding: gzip, compress");
		addLine(buffer,null);
		addLine(buffer,"HTTP/1.1 200 OK");
		addLine(buffer,"Date: Mon, 10 Jan 2000 09:52:22 GMT");
		addLine(buffer,"Server: Apache/1.3.6 (Unix)");
		addLine(buffer,"ETag: \"63840-1ab7-378d415b\"");
		addLine(buffer,"Content-Type: text/html");
		addLine(buffer,"Content-Length: 151");
		addLine(buffer,null);
		addChunk(buffer,"This is data that was returned by an origin server.");
		addLastChunk(buffer);
		return buffer;
	}	
	
	public static final ByteBuf createRESPMODPreviewWithZeroBody() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"RESPMOD icap://icap.mimo.ch:1344/reqmod ICAP/1.0");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"Encapsulated: req-hdr=0, res-hdr=137, res-body=296");
		addLine(buffer,"Preview: 0");
		addLine(buffer,"Allow: 204");
		addLine(buffer,null);
		addLine(buffer,"GET /origin-resource HTTP/1.1");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain, image/gif");
		addLine(buffer,"Accept-Encoding: gzip, compress");
		addLine(buffer,null);
		addLine(buffer,"HTTP/1.1 200 OK");
		addLine(buffer,"Date: Mon, 10 Jan 2000 09:52:22 GMT");
		addLine(buffer,"Server: Apache/1.3.6 (Unix)");
		addLine(buffer,"ETag: \"63840-1ab7-378d415b\"");
		addLine(buffer,"Content-Type: text/html");
		addLine(buffer,"Content-Length: 151");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final IcapRequest createRESPMODWithGetRequestAndPreviewIncludingEncapsulationHeaderIcapRequest() {
		IcapRequest request = new DefaultIcapRequest(IcapVersion.ICAP_1_0,IcapMethod.RESPMOD,"icap://icap.mimo.ch:1344/reqmod","icap-server.net");
		request.addHeader("Encapsulated","req-hdr=0, res-hdr=137, res-body=296");
		request.addHeader("Preview","51");
		request.setBody(IcapMessageElementEnum.RESBODY);
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.GET,"/origin-resource");
		request.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain, image/gif");
		httpRequest.headers().add("Accept-Encoding","gzip, compress");
		FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,HttpResponseStatus.OK);
		request.setHttpResponse(httpResponse);
		httpResponse.headers().add("Date","Mon, 10 Jan 2000 09:52:22 GMT");
		httpResponse.headers().add("Server","Apache/1.3.6 (Unix)");
		httpResponse.headers().add("ETag","\"63840-1ab7-378d415b\"");
		httpResponse.headers().add("Content-Type","text/html");
		httpResponse.headers().add("Content-Length","151");
		return request;
	}
	
	public static final IcapChunk createRESPMODWithGetRequestAndPreviewIcapChunk() {
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("This is data that was returned by an origin server.".getBytes(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk = new DefaultIcapChunk(buffer);
		chunk.setPreviewChunk(true);
		return chunk;
	}
	
	public static final IcapChunk createRESPMODWithGetRequestAndPreviewLastIcapChunk() {
		IcapChunkTrailer trailer = new DefaultIcapChunkTrailer();
		trailer.setPreviewChunk(true);
		return trailer;
	}
	
	public static final IcapChunk createRESPMODWithGetRequestAndPreviewIcapChunkFullMessageChunk() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("And this the second chunk which contains more information.".getBytes(IcapCodecUtil.ASCII_CHARSET));
		IcapChunk chunk = new DefaultIcapChunk(buffer);
		return chunk;
	}
	
	public static final IcapChunk createRESPMODWithGetRequestAndPreviewChunkTrailer() {
		IcapChunkTrailer trailer = new DefaultIcapChunkTrailer();
		return trailer;
	}
	
	public static final void assertCreateRESPMODWithGetRequestAndPreview(IcapRequest message) {
		assertEquals("Uri is wrong","icap://icap.mimo.ch:1344/reqmod",message.getUri());
		assertHeaderValue("Host","icap-server.net",message);
		assertHeaderValue("Encapsulated","req-hdr=0, res-hdr=137, res-body=296",message);
		assertHeaderValue("Preview","51",message);
		assertNotNull("http request was null",message.getHttpRequest());
		assertEquals("http request method was wrong",HttpMethod.GET,message.getHttpRequest().getMethod());
		assertHttpMessageHeaderValue("Host","www.origin-server.com",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept","text/html, text/plain, image/gif",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept-Encoding","gzip, compress",message.getHttpRequest());
		assertNotNull("http response was null",message.getHttpResponse());
		assertEquals("http response status was wrong",HttpResponseStatus.OK,message.getHttpResponse().getStatus());
		assertHttpMessageHeaderValue("Date","Mon, 10 Jan 2000 09:52:22 GMT",message.getHttpResponse());
		assertHttpMessageHeaderValue("Server","Apache/1.3.6 (Unix)",message.getHttpResponse());
		assertHttpMessageHeaderValue("ETag","\"63840-1ab7-378d415b\"",message.getHttpResponse());
		assertHttpMessageHeaderValue("Content-Type","text/html",message.getHttpResponse());
		assertHttpMessageHeaderValue("Content-Length","151",message.getHttpResponse());
	}
	
	public static final ByteBuf createRESPMODWithGetRequestAndPreviewResponse() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"ICAP/1.0 200 OK");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"Encapsulated: req-hdr=0, res-hdr=137, res-body=296");
		addLine(buffer,"Preview: 51");
		addLine(buffer,null);
		addLine(buffer,"GET /origin-resource HTTP/1.1");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain, image/gif");
		addLine(buffer,"Accept-Encoding: gzip, compress");
		addLine(buffer,null);
		addLine(buffer,"HTTP/1.1 200 OK");
		addLine(buffer,"Date: Mon, 10 Jan 2000 09:52:22 GMT");
		addLine(buffer,"Server: Apache/1.3.6 (Unix)");
		addLine(buffer,"ETag: \"63840-1ab7-378d415b\"");
		addLine(buffer,"Content-Type: text/html");
		addLine(buffer,"Content-Length: 151");
		addLine(buffer,null);
		addChunk(buffer,"This is data that was returned by an origin server.");
		addLastChunk(buffer);
		return buffer;
	}	
	
	public static final void assertCreateRESPMODWithGetRequestAndPreviewResponse(IcapResponse message) {
		assertEquals("wrong response version",IcapVersion.ICAP_1_0,message.getProtocolVersion());
		assertEquals("wrong resonse status",IcapResponseStatus.OK,message.getStatus());
		assertHeaderValue("Host","icap-server.net",message);
		assertHeaderValue("Encapsulated","req-hdr=0, res-hdr=137, res-body=296",message);
		assertHeaderValue("Preview","51",message);
		assertNotNull("http request was null",message.getHttpRequest());
		assertEquals("http request method was wrong",HttpMethod.GET,message.getHttpRequest().getMethod());
		assertHttpMessageHeaderValue("Host","www.origin-server.com",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept","text/html, text/plain, image/gif",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept-Encoding","gzip, compress",message.getHttpRequest());
		assertNotNull("http response was null",message.getHttpResponse());
		assertEquals("http response status was wrong",HttpResponseStatus.OK,message.getHttpResponse().getStatus());
		assertHttpMessageHeaderValue("Date","Mon, 10 Jan 2000 09:52:22 GMT",message.getHttpResponse());
		assertHttpMessageHeaderValue("Server","Apache/1.3.6 (Unix)",message.getHttpResponse());
		assertHttpMessageHeaderValue("ETag","\"63840-1ab7-378d415b\"",message.getHttpResponse());
		assertHttpMessageHeaderValue("Content-Type","text/html",message.getHttpResponse());
		assertHttpMessageHeaderValue("Content-Length","151",message.getHttpResponse());
	}

	public static final void assertCreateRESPMODWithGetRequestAndPreviewChunk(IcapChunk chunk) {
		assertTrue("preview chunk is not marked as such",chunk.isPreviewChunk());
		assertFalse("preview chunk indicated that is is early terminated",chunk.isEarlyTerminated());
		assertChunk("preview chunk", chunk,"This is data that was returned by an origin server.",false);
	}
	
	public static final void assertCreateRESPMODWithGetRequestAndPreviewLastChunk(IcapChunk chunk) {
		assertTrue("last chunk is wrong type",chunk instanceof IcapChunkTrailer);
		assertTrue("preview chunk is not marked as such",chunk.isPreviewChunk());
		assertTrue("preview chunk is not last chunk",chunk.isLast());
		assertFalse("preview chunk states that it is early terminated",chunk.isEarlyTerminated());
	}
	
	public static final IcapResponse create100ContinueIcapResponse() {
		IcapResponse response = new DefaultIcapResponse(IcapVersion.ICAP_1_0,IcapResponseStatus.CONTINUE);
		return response;
	}
	
	public static final ByteBuf create100ContinueResponse() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"ICAP/1.0 100 Continue");
		addLine(buffer,"Encapsulated: null-body=0");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final ByteBuf create204NoContentResponse() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"ICAP/1.0 204 NoContent");
		addLine(buffer,"Encapsulated: null-body=0");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final IcapRequest createREQMODWithGetRequestAndDataIcapMessage() {
		IcapRequest request = new DefaultIcapRequest(IcapVersion.ICAP_1_0,IcapMethod.REQMOD,"icap://icap.mimo.ch:1344/reqmod","icap-server.net");
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("This is data that was returned by an origin server.".getBytes(IcapCodecUtil.ASCII_CHARSET));
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.GET,"/", buffer);
		request.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain");
		httpRequest.headers().add("Accept-Encoding","compress");
		httpRequest.headers().add("Cookie","ff39fk3jur@4ii0e02i");
		httpRequest.headers().add("If-None-Match","\"xyzzy\", \"r2d2xxxx\"");
		return request;
	}
	
	public static final ByteBuf createREQMODWithGetRequestAndData() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"REQMOD icap://icap.mimo.ch:1344/reqmod ICAP/1.0");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"Encapsulated: req-hdr=0, req-body=170");
		addLine(buffer,null);
		addLine(buffer,"GET / HTTP/1.1");
		addLine(buffer,"Host: www.origin-server.com");
		addLine(buffer,"Accept: text/html, text/plain");
		addLine(buffer,"Accept-Encoding: compress");
		addLine(buffer,"Cookie: ff39fk3jur@4ii0e02i");
		addLine(buffer,"If-None-Match: \"xyzzy\", \"r2d2xxxx\"");
		addLine(buffer,null);
		return buffer;
	}
	
	public static final ByteBuf createREQMODWithGetRequestAndDataFirstChunk() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addChunk(buffer,"This is data that was returned by an origin server.");
		return buffer;
	}
	
	public static final ByteBuf createREQMODWithGetRequestAndDataLastChunk() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLastChunk(buffer);
		return buffer;
	}
	
	public static final IcapResponse createREQMODWithDataIcapResponse() {
		IcapResponse response = new DefaultIcapResponse(IcapVersion.ICAP_1_0,IcapResponseStatus.OK);
		response.addHeader("Host","icap-server.net");
		response.addHeader("ISTag","Serial-0815");
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("This is data that was returned by an origin server.".getBytes(IcapCodecUtil.ASCII_CHARSET));
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.GET,"/", buffer);
		response.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain");
		httpRequest.headers().add("Accept-Encoding","compress");
		httpRequest.headers().add("Cookie","ff39fk3jur@4ii0e02i");
		httpRequest.headers().add("If-None-Match","\"xyzzy\", \"r2d2xxxx\"");
		return response;
	}
	
	public static final IcapRequest createRESPMODWithPreviewDataIcapRequest() {
		IcapRequest request = new DefaultIcapRequest(IcapVersion.ICAP_1_0,IcapMethod.RESPMOD,"icap://icap.mimo.ch:1344/reqmod","icap-server.net");
		request.addHeader("Preview","51");
		request.setBody(IcapMessageElementEnum.RESBODY);
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.GET,"/origin-resource");
		request.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain, image/gif");
		httpRequest.headers().add("Accept-Encoding","gzip, compress");
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("This is data that was returned by an origin server.".getBytes(IcapCodecUtil.ASCII_CHARSET));
		FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,HttpResponseStatus.OK, buffer);
		request.setHttpResponse(httpResponse);
		httpResponse.headers().add("Date","Mon, 10 Jan 2000 09:52:22 GMT");
		httpResponse.headers().add("Server","Apache/1.3.6 (Unix)");
		httpResponse.headers().add("ETag","\"63840-1ab7-378d415b\"");
		httpResponse.headers().add("Content-Type","text/html");
		httpResponse.headers().add("Content-Length","151");
		return request;
	}

	public static final IcapRequest createRESPMODWithPreviewZeroDataIcapRequest() {
		IcapRequest request = new DefaultIcapRequest(IcapVersion.ICAP_1_0,IcapMethod.RESPMOD,"icap://icap.mimo.ch:1344/reqmod","icap-server.net");
		request.addHeader("Preview","0");
		request.setBody(IcapMessageElementEnum.RESBODY);
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.GET,"/origin-resource");
		request.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain, image/gif");
		httpRequest.headers().add("Accept-Encoding","gzip, compress");
		FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,HttpResponseStatus.OK);
		request.setHttpResponse(httpResponse);
		httpResponse.headers().add("Date","Mon, 10 Jan 2000 09:52:22 GMT");
		httpResponse.headers().add("Server","Apache/1.3.6 (Unix)");
		httpResponse.headers().add("ETag","\"63840-1ab7-378d415b\"");
		httpResponse.headers().add("Content-Type","text/html");
		httpResponse.headers().add("Content-Length","151");
		return request;
	}
	
	public static final IcapRequest createRESPMODWithPreviewDataAndEarlyTerminationIcapRequest() {
		IcapRequest request = new DefaultIcapRequest(IcapVersion.ICAP_1_0,IcapMethod.RESPMOD,"icap://icap.mimo.ch:1344/reqmod","icap-server.net");
		request.addHeader("Preview","51");
		request.setBody(IcapMessageElementEnum.RESBODY);
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.GET,"/origin-resource");
		request.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain, image/gif");
		httpRequest.headers().add("Accept-Encoding","gzip, compress");
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("This is data that was returned by an orig".getBytes(IcapCodecUtil.ASCII_CHARSET));
		FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,HttpResponseStatus.OK,buffer);
		request.setHttpResponse(httpResponse);
		httpResponse.headers().add("Date","Mon, 10 Jan 2000 09:52:22 GMT");
		httpResponse.headers().add("Server","Apache/1.3.6 (Unix)");
		httpResponse.headers().add("ETag","\"63840-1ab7-378d415b\"");
		httpResponse.headers().add("Content-Type","text/html");
		httpResponse.headers().add("Content-Length","151");
		return request;
	}

	public static IcapResponse createREQMODWithPartialContentUsingCompleteOriginalBodyIcapResponse() {
		IcapResponse response = new DefaultIcapResponse(IcapVersion.ICAP_1_0, IcapResponseStatus.PARTIAL_CONTENT);
		response.addHeader("Host","icap-server.net");
		response.addHeader("ISTag","Serial-0815");
		response.setBody(IcapMessageElementEnum.REQBODY);
		response.setUseOriginalBody(0);
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,"/", Unpooled.EMPTY_BUFFER);
		response.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain");
		httpRequest.headers().add("Accept-Encoding","compress");
		httpRequest.headers().add("Cookie","ff39fk3jur@4ii0e02i");
		httpRequest.headers().add("If-None-Match","\"xyzzy\", \"r2d2xxxx\"");
		return response;
	}

	public static ByteBuf createREQMODWithPartialContentResponse() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer, "ICAP/1.0 206 Partial Content");
		addLine(buffer, "Host: icap-server.net");
		addLine(buffer, "ISTag: Serial-0815");
		addLine(buffer, "Encapsulated: req-hdr=0, req-body=170");
		addLine(buffer, "");
		addLine(buffer, "GET / HTTP/1.1");
		addLine(buffer, "Host: www.origin-server.com");
		addLine(buffer, "Accept: text/html, text/plain");
		addLine(buffer, "Accept-Encoding: compress");
		addLine(buffer, "Cookie: ff39fk3jur@4ii0e02i");
		addLine(buffer, "If-None-Match: \"xyzzy\", \"r2d2xxxx\"");
		addLine(buffer, null);
		return buffer;
	}

	public static void assertREQMODWithPartialContentResponse(IcapResponse response) {
		assertNotNull("response was null", response);
		assertEquals("wrong icap version", IcapVersion.ICAP_1_0, response.getProtocolVersion());
		assertEquals("wrong icap status code", IcapResponseStatus.PARTIAL_CONTENT, response.getStatus());
		assertHeaderValue("Host","icap-server.net", response);
		assertHeaderValue("ISTag","Serial-0815", response);
		assertNotNull("Http request was null", response.getHttpRequest());
		HttpRequest httpRequest = response.getHttpRequest();
		assertEquals("wrong http version", HttpVersion.HTTP_1_1, httpRequest.protocolVersion());
		assertHttpMessageHeaderValue("Host","www.origin-server.com", httpRequest);
		assertHttpMessageHeaderValue("Accept","text/html, text/plain", httpRequest);
		assertHttpMessageHeaderValue("Accept-Encoding","compress", httpRequest);
		assertHttpMessageHeaderValue("Cookie","ff39fk3jur@4ii0e02i", httpRequest);
		assertHttpMessageHeaderValue("If-None-Match","\"xyzzy\", \"r2d2xxxx\"", httpRequest);
	}

	public static IcapChunkTrailer createREQMODWithPartialContentUsingOriginalBodyIcapChunkTrailer(Integer offset) {
		return new DefaultIcapChunkTrailer(false, false, offset);
	}

	public static ByteBuf createREQMODWithPartialContentUsingOriginalBodyTrailerEncodedChunkTrailer(int offset) throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer, "0;use-original-body=" + offset);
		addLine(buffer, null);
		return buffer;
	}

	public static IcapResponse createREQMODWithPartialContentUsingModifiedOriginalBodyIcapResponse() {
		IcapResponse response = new DefaultIcapResponse(IcapVersion.ICAP_1_0, IcapResponseStatus.PARTIAL_CONTENT);
		response.addHeader("Host","icap-server.net");
		response.addHeader("ISTag","Serial-0815");
		response.setBody(IcapMessageElementEnum.REQBODY);
		response.setUseOriginalBody(5);
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("This replaces the first five bytes of the original content.".getBytes(IcapCodecUtil.ASCII_CHARSET));
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/", buffer);
		response.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain");
		httpRequest.headers().add("Accept-Encoding","compress");
		httpRequest.headers().add("Cookie","ff39fk3jur@4ii0e02i");
		httpRequest.headers().add("If-None-Match","\"xyzzy\", \"r2d2xxxx\"");
		return response;
	}

	public static void assertCreateREQMODWithPartialContentUsingModifiedOriginalBodyIcapResponse(IcapResponse response) {
		assertNotNull("response was null", response);
		assertEquals("wrong icap version", IcapVersion.ICAP_1_0, response.getProtocolVersion());
		assertEquals("wrong icap status code", IcapResponseStatus.PARTIAL_CONTENT, response.getStatus());
		assertHeaderValue("Host","icap-server.net", response);
		assertHeaderValue("ISTag","Serial-0815", response);
		assertNotNull("use-original-body not set", response.getUseOriginalBody());
		assertEquals("use-original-body wrong", Integer.valueOf(5), response.getUseOriginalBody());
		assertNotNull("Http request was null", response.getHttpRequest());
		HttpRequest httpRequest = response.getHttpRequest();
		assertEquals("wrong http version", HttpVersion.HTTP_1_1, httpRequest.protocolVersion());
		assertHttpMessageHeaderValue("Host","www.origin-server.com", httpRequest);
		assertHttpMessageHeaderValue("Accept","text/html, text/plain", httpRequest);
		assertHttpMessageHeaderValue("Accept-Encoding","compress", httpRequest);
		assertHttpMessageHeaderValue("Cookie","ff39fk3jur@4ii0e02i", httpRequest);
		assertHttpMessageHeaderValue("If-None-Match","\"xyzzy\", \"r2d2xxxx\"", httpRequest);
	}

	public static ByteBuf createREQMODWithPartialContentUsingModifiedOriginalBodyResponse() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer, "ICAP/1.0 206 Partial Content");
		addLine(buffer, "Host: icap-server.net");
		addLine(buffer, "ISTag: Serial-0815");
		addLine(buffer, "Encapsulated: req-hdr=0, req-body=170");
		addLine(buffer, null);
		addLine(buffer, "GET / HTTP/1.1");
		addLine(buffer, "Host: www.origin-server.com");
		addLine(buffer, "Accept: text/html, text/plain");
		addLine(buffer, "Accept-Encoding: compress");
		addLine(buffer, "Cookie: ff39fk3jur@4ii0e02i");
		addLine(buffer, "If-None-Match: \"xyzzy\", \"r2d2xxxx\"");
		addLine(buffer, null);
		addChunk(buffer, "This replaced the first five bytes of the original content.");
		addLastChunk(buffer, ";use-original-body=5");
		return buffer;
	}

	public static void assertREQMODWithPartialContentUsingModifiedOriginalBodyIcapChunk(IcapChunk chunk) {
		assertNotNull("chunk is null", chunk);
		assertFalse("chunk ist last", chunk.isLast());
		ByteBuf buffer = chunk.content();
		assertNotNull("chunk buffer is null", buffer);
		assertFalse("chunk buffer is empty", Unpooled.EMPTY_BUFFER.equals(buffer));
		assertEquals("chunk content was wrong", "This replaced the first five bytes of the original content.", buffer.toString(IcapCodecUtil.ASCII_CHARSET));
	}

	public static void assertREQMODWithPartialContentUsingModifiedOriginalBodyIcapChunkTrailer(IcapChunkTrailer trailer) {
		assertNotNull("trailer was null", trailer);
		assertTrue("is not last chunk", trailer.isLast());
		assertEquals("unexpected offset", Integer.valueOf(5), trailer.getUseOriginalBody());
	}

	public static final IcapChunk createREQMODWithPartialContentUsingModifiedOriginalBodyIcapChunk() {
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("This replaces the first five bytes of the original content.".getBytes(IcapCodecUtil.ASCII_CHARSET));
		return new DefaultIcapChunk(buffer);
	}

	public static final IcapChunk createREQMODWithPartialContentUsingModifiedOriginalBodyIcapChunkTrailer() {
		return createREQMODWithPartialContentUsingOriginalBodyIcapChunkTrailer(5);
	}

	public static final ByteBuf createREQMODWithPartialContentUsingModifiedOriginalBodyEncodedChunk() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer, "3b");
		addLine(buffer, "This replaces the first five bytes of the original content.");
		return buffer;
	}

    public static final IcapResponse createREQMODWithPartialContentReplacingOriginalBodyIcapResponse() {
        IcapResponse response = new DefaultIcapResponse(IcapVersion.ICAP_1_0, IcapResponseStatus.PARTIAL_CONTENT);
        response.addHeader("Host","icap-server.net");
        response.addHeader("ISTag","Serial-0815");
        response.setBody(IcapMessageElementEnum.REQBODY);
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeBytes("This replaces the first five bytes of the original content.".getBytes(IcapCodecUtil.ASCII_CHARSET));
        FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/", buffer);
        response.setHttpRequest(httpRequest);
        httpRequest.headers().add("Host","www.origin-server.com");
        httpRequest.headers().add("Accept","text/html, text/plain");
        httpRequest.headers().add("Accept-Encoding","compress");
        httpRequest.headers().add("Cookie","ff39fk3jur@4ii0e02i");
        httpRequest.headers().add("If-None-Match","\"xyzzy\", \"r2d2xxxx\"");
        return response;
    }

    public static final IcapChunk createREQMODWithPartialContentReplacingOriginalBodyIcapChunk() {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeBytes("This replaces the original content.".getBytes(IcapCodecUtil.ASCII_CHARSET));
        return new DefaultIcapChunk(buffer);
    }

    public static final ByteBuf createREQMODWithPartialContentReplacingOriginalBodyEncodedChunk() throws UnsupportedEncodingException {
        ByteBuf buffer = Unpooled.buffer();
        addLine(buffer, "23");
        addLine(buffer, "This replaces the original content.");
        return buffer;
    }

	public static final IcapResponse createREQMODWithPartialContentReplacingOriginalBody() {
		IcapResponse response = new DefaultIcapResponse(IcapVersion.ICAP_1_0, IcapResponseStatus.PARTIAL_CONTENT);
		response.addHeader("Host","icap-server.net");
		response.addHeader("ISTag","Serial-0815");
		response.setUseOriginalBody(null);
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("Content replacement".getBytes(IcapCodecUtil.ASCII_CHARSET));
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/", buffer);
		response.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain");
		httpRequest.headers().add("Accept-Encoding","compress");
		httpRequest.headers().add("Cookie","ff39fk3jur@4ii0e02i");
		httpRequest.headers().add("If-None-Match","\"xyzzy\", \"r2d2xxxx\"");
		return response;
	}

    public static final IcapChunkTrailer createREQMODWithPartialContentReplacingOriginalBodyIcapChunkTrailer() {
        DefaultIcapChunkTrailer trailer = new DefaultIcapChunkTrailer(false, false);
        return trailer;
    }

    public static final ByteBuf createREQMODWithPartialContentReplacingOriginalBodyTrailerEncodedChunkTrailer() throws UnsupportedEncodingException {
        ByteBuf buffer = Unpooled.buffer();
        addLine(buffer, "0");
        addLine(buffer, null);
        return buffer;
    }

	public static final IcapResponse createOPTIONSResponseWithBodyInIcapResponse() {
		IcapResponse response = new DefaultIcapResponse(IcapVersion.ICAP_1_0,IcapResponseStatus.OK);
		response.addHeader("Methods","REQMOD RESPMOD");
		response.addHeader("Service","Joggels icap server 1.0");
		response.addHeader("ISTag","5BDEEEA9-12E4-2");
		response.addHeader("Max-Connections","100");
		response.addHeader("Options-TTL","1000");
		response.addHeader("Allow","204");
		response.addHeader("Preview","1024");
		response.addHeader("Opt-body-type","Simple-text");
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("This is data that was returned by an origin server.".getBytes(IcapCodecUtil.ASCII_CHARSET));
		response.setContent(buffer);
		return response;
	}

	public static final ByteBuf create204ResponseWithoutEncapsulatedHeader() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"ICAP/1.0 204 No Content");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,"ISTag: \"209BC533133B6F323892C3A62DFDBEAC\"");
		addLine(buffer,"Date: Thu Sep 22 22:37:55 2012 GMT");
		addLine(buffer,"Service: Symantec Scan Engine/5.2.11.131");
		addLine(buffer,"Service-ID: Respmod AV Scan");
		addLine(buffer,"X-Outer-Container-Is-Mime: 0");
		addLine(buffer,null);
		return buffer;
	}

	public static final ByteBuf create100ResponseWithoutEncapsulatedHeader() throws UnsupportedEncodingException {
		ByteBuf buffer = Unpooled.buffer();
		addLine(buffer,"ICAP/1.0 100 Continue");
		addLine(buffer,"Host: icap-server.net");
		addLine(buffer,null);
		return buffer;
	}

	public static final IcapRequest createREQMODWithPostRequestAndDataIcapMessage() {
		IcapRequest request = new DefaultIcapRequest(IcapVersion.ICAP_1_0,IcapMethod.REQMOD,"icap://icap.mimo.ch:1344/reqmod","icap-server.net");
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("This is data that was returned by an origin server.".getBytes(IcapCodecUtil.ASCII_CHARSET));
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.POST,"/", buffer);
		request.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain");
		httpRequest.headers().add("Accept-Encoding","compress");
		httpRequest.headers().add("Cookie","ff39fk3jur@4ii0e02i");
		httpRequest.headers().add("If-None-Match","\"xyzzy\", \"r2d2xxxx\"");
		return request;
	}

	public static final void assertCreateREQMODWithPostRequestAndDataIcapRequest(IcapRequest message) {
		assertEquals("Uri is wrong","icap://icap.mimo.ch:1344/reqmod",message.getUri());
		assertHeaderValue("Host","icap-server.net",message);
		assertHeaderValue("Encapsulated","req-hdr=0, req-body=171",message);
		assertNotNull("http request was null",message.getHttpRequest());
		assertEquals("http request method was wrong",HttpMethod.POST,message.getHttpRequest().getMethod());
		assertHttpMessageHeaderValue("Host","www.origin-server.com",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept","text/html, text/plain",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept-Encoding","compress",message.getHttpRequest());
		assertHttpMessageHeaderValue("Cookie","ff39fk3jur@4ii0e02i",message.getHttpRequest());
		assertHttpMessageHeaderValue("If-None-Match","\"xyzzy\", \"r2d2xxxx\"",message.getHttpRequest());
		assertEquals("http request message content was wrong","This is data that was returned by an origin server.",message.getHttpRequest().content().toString(IcapCodecUtil.ASCII_CHARSET));
		assertNull("http response was not null",message.getHttpResponse());
	}

	public static final IcapResponse createREQMODWithPostRequestIcapResponse() {
		IcapResponse response = new DefaultIcapResponse(IcapVersion.ICAP_1_0,IcapResponseStatus.OK);
		response.addHeader("Host","icap-server.net");
		response.addHeader("ISTag","Serial-0815");
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes("This is data that was returned by an origin server.".getBytes(IcapCodecUtil.ASCII_CHARSET));
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.POST,"/",buffer);
		response.setHttpRequest(httpRequest);
		httpRequest.headers().add("Host","www.origin-server.com");
		httpRequest.headers().add("Accept","text/html, text/plain");
		httpRequest.headers().add("Accept-Encoding","compress");
		httpRequest.headers().add("Cookie","ff39fk3jur@4ii0e02i");
		httpRequest.headers().add("If-None-Match","\"xyzzy\", \"r2d2xxxx\"");
		return response;
	}

	public static final void assertCreateREQMODWithPostRequestAndDataIcapResponse(IcapResponse message) {
		assertEquals("wrong icap version",IcapVersion.ICAP_1_0,message.getProtocolVersion());
		assertEquals("wrong response status",IcapResponseStatus.OK,message.getStatus());
		assertHeaderValue("Host","icap-server.net",message);
		assertHeaderValue("Encapsulated","req-hdr=0, req-body=171",message);
		assertNotNull("http request was null",message.getHttpRequest());
		assertEquals("http request method was wrong",HttpMethod.POST,message.getHttpRequest().getMethod());
		assertHttpMessageHeaderValue("Host","www.origin-server.com",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept","text/html, text/plain",message.getHttpRequest());
		assertHttpMessageHeaderValue("Accept-Encoding","compress",message.getHttpRequest());
		assertHttpMessageHeaderValue("Cookie","ff39fk3jur@4ii0e02i",message.getHttpRequest());
		assertHttpMessageHeaderValue("If-None-Match","\"xyzzy\", \"r2d2xxxx\"",message.getHttpRequest());
		assertEquals("http request message content was wrong","This is data that was returned by an origin server.",message.getHttpRequest().content().toString(IcapCodecUtil.ASCII_CHARSET));
		assertNull("http response was not null",message.getHttpResponse());
	}

	private static final void addLine(ByteBuf buffer, String value) throws UnsupportedEncodingException {
		if(value == null) {
			buffer.writeBytes(IcapCodecUtil.CRLF);
		} else {
			buffer.writeBytes(value.getBytes("ASCII"));
			buffer.writeBytes(IcapCodecUtil.CRLF);
		}
	}

	private static void addChunk(ByteBuf buffer, String chunkData) throws UnsupportedEncodingException {
		int length = chunkData.length();
		String hex = Integer.toString(length,16);
		buffer.writeBytes(hex.getBytes("ASCII"));
		buffer.writeBytes(IcapCodecUtil.CRLF);
		buffer.writeBytes(chunkData.getBytes("ASCII"));
		buffer.writeBytes(IcapCodecUtil.CRLF);
	}

	private static void addLastChunk(ByteBuf buffer) throws UnsupportedEncodingException {
		addLastChunk(buffer, "");
	}

    private static void addLastChunk(ByteBuf buffer, String extensions) throws UnsupportedEncodingException {
        buffer.writeBytes("0".getBytes("ASCII"));
        buffer.writeBytes(extensions.getBytes("ASCII"));
        buffer.writeBytes(IcapCodecUtil.CRLF);
        buffer.writeBytes(IcapCodecUtil.CRLF);
    }

	private static void assertHeaderValue(String key, String expected, IcapMessage message) {
		assertNotNull("Message was null",message);
		assertTrue("Key does not exist [" + key + "]",message.containsHeader(key));
		assertEquals("The header: " + key + " is invalid",expected,message.getHeader(key));
	}

	private static void assertTrailingHeaderValue(String key, String expected, IcapChunkTrailer message) {
		assertNotNull("Chunk trailer was null",message);
		assertTrue("Key does not exist [" + key + "]",message.trailingHeaders().contains(key));
		assertEquals("The header: " + key + " is invalid",expected,message.trailingHeaders().get(key));
	}

	private static void assertHttpMessageHeaderValue(String key, String expected, HttpMessage message) {
		assertNotNull("Message was null",message);
		assertTrue("Key does not exist [" + key + "]",message.headers().contains(key));
		assertEquals("The header: " + key + " is invalid",expected,message.headers().get(key));
	}

	private static void assertChunk(String title, IcapChunk chunk, String expectedContent, boolean isLast) {
		assertNotNull(title + " chunk is null",chunk);
		if(isLast) {
			assertTrue(title + " is not last chunk",chunk.isLast());
		} else {
			ByteBuf buffer = chunk.content();
			assertNotNull(title + " chunk buffer is null",buffer);
			assertFalse(title + " chunk buffer is empty", Unpooled.EMPTY_BUFFER.equals(buffer));
			String bufferContent = buffer.toString(Charset.defaultCharset());
			assertEquals(title + " chunk content was wrong",expectedContent,bufferContent);
		}
	}
}
