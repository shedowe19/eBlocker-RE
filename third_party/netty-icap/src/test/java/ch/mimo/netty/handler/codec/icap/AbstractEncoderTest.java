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

import java.nio.charset.Charset;

import io.netty.buffer.ByteBuf;
import org.junit.After;
import org.junit.Before;

public abstract class AbstractEncoderTest extends AbstractIcapTest {

	private StringBuilder outputBuilder;
	
	@Before
	public void prepareBuilder() {
		outputBuilder = new StringBuilder();
	}
	
	@After
	public void printBuilderOutput() {
		if(outputBuilder != null) {
			doOutput(outputBuilder.toString());
		}
	}
	
	protected String getBufferContent(Object object) {
		assertNotNull("poll returned null",object);
		assertTrue("returned object from embedder is not of type ByteBuf",object instanceof ByteBuf);
		ByteBuf buffer = (ByteBuf) object;
		return buffer.toString(Charset.defaultCharset());
	}
	
	protected void assertResponse(ByteBuf expected, String request) {
		String content = getBufferContent(expected);
		outputBuilder.append(content);
		assertEquals("encoded request is wrong",content,request);
	}
}
