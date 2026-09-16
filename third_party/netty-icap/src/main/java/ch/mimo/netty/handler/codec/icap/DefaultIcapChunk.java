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
import io.netty.handler.codec.http.DefaultHttpContent;

/**
 * This is the main Chunk implementation class. It extends {@link io.netty.handler.codec.http.DefaultHttpContent} and adds
 * all necessary methods and members for preview handling.
 * 
 * @author Michael Mimo Moratti (mimo@mimo.ch)
 *
 */
public class DefaultIcapChunk extends DefaultHttpContent implements IcapChunk {

	private boolean preview;
	private boolean earlyTerminated;
	private boolean last;

	public DefaultIcapChunk(ByteBuf content) {
		super(content);
		last = !content.isReadable();
	}
	
	public void setPreviewChunk(boolean preview) {
		this.preview = preview;
	}
	
	public boolean isPreviewChunk() {
		return preview;
	}
	
	public void setEarlyTermination(boolean earlyTermination) {
		this.earlyTerminated = earlyTermination;
	}
	
	public boolean isEarlyTerminated() {
		return earlyTerminated;
	}

	@Override
	public boolean isLast() {
		return last;
	}

	public String toString() {
		return "DefaultIcapChunk: [isPreviewChunk=" + preview + "] [wasEarlyTerminated=" + earlyTerminated + "] [data=" + content().readableBytes() + "]";
	}
}
