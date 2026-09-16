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

import io.netty.buffer.ByteBuf;

/**
 * ICAP response.
 * 
 * @author Michael Mimo Moratti (mimo@mimo.ch)
 *
 * @see IcapMessage
 * @see DefaultIcapResponse
 */
public interface IcapResponse extends IcapMessage {

	/**
	 * Sets the response status
	 * @param status {@link IcapResponseStatus} value like 200 OK.
	 */
	void setStatus(IcapResponseStatus status);
	
	/**
	 * Gets the response status for this message.
	 * 
	 * @return the response status as {@link IcapResponseStatus}
	 */
	IcapResponseStatus getStatus();
	
	/**
	 * Sets an OPTIONS body to this message.
	 * @param optionsContent {@link ByteBuf} containing the body.
	 */
	void setContent(ByteBuf optionsContent);

	/**
	 * Gets an OPTIONS body if present
	 * @return {@link ByteBuf} or null
	 */
	ByteBuf getContent();

	void setUseOriginalBody(Integer offset);

	Integer getUseOriginalBody();
}
