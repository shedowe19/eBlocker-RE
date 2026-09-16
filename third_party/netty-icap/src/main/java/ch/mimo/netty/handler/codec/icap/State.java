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
 * Abstract state implementation for all Decoder states.
 * 
 * @author Michael Mimo Moratti (mimo@mimo.ch)
 *
 * @param <T>
 * 
 * @see IcapMessageDecoder
 * @see StateEnum
 */
public abstract class State<T extends Object> {
	
	private String name;
	
	public State(String name) {
		this.name = name;
	}
	
	/**
	 * Preparation method
	 */
	public abstract void onEntry(ByteBuf buffer, IcapMessageDecoder icapMessageDecoder) throws DecodingException;
	
	/**
	 * execution method
	 * @return {@link StateReturnValue} that contains, dependent on the relevance a return value.
	 */
	public abstract StateReturnValue execute(ByteBuf buffer, IcapMessageDecoder icapMessageDecoder) throws DecodingException;
	
	/**
	 * Flow decision method
	 * @return has to return a valid next state. Can be itself.
	 */
	public abstract StateEnum onExit(ByteBuf buffer, IcapMessageDecoder icapMessageDecoder, T decisionInformation) throws DecodingException;

	public String toString() {
		return name;
	}
}
