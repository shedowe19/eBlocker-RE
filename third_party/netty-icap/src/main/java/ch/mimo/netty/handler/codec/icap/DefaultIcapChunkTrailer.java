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

import io.netty.handler.codec.http.DefaultLastHttpContent;

/**
 * This class is used to indicate the end of a chunked data stream and to hold 
 * trailing headers.
 * 
 * @author Michael Mimo Moratti (mimo@mimo.ch)
 *
 * @see IcapChunkTrailer implementation.
 *
 */
public class DefaultIcapChunkTrailer extends DefaultLastHttpContent implements IcapChunkTrailer {
	
	private boolean preview;
	private boolean earlyTerminated;
	private Integer useOriginalBodyOffset;

	public DefaultIcapChunkTrailer() {
		this.preview = false;
		this.earlyTerminated = false;
	}
	
	public DefaultIcapChunkTrailer(boolean isPreview, boolean isEarlyTerminated) {
		this.preview = isPreview;
		this.earlyTerminated = isEarlyTerminated;
	}

	public DefaultIcapChunkTrailer(boolean isPreview, boolean isEarlyTerminated, Integer useOriginalBodyOffset) {
		this.preview = isPreview;
		this.earlyTerminated = isEarlyTerminated;
		this.useOriginalBodyOffset = useOriginalBodyOffset;
	}
	
	@Override
	public void setPreviewChunk(boolean preview) {
		this.preview = preview;
	}

	@Override
	public boolean isPreviewChunk() {
		return preview;
	}

	@Override
	public void setEarlyTermination(boolean earlyTermination) {
		this.earlyTerminated = earlyTermination;
	}

	@Override
	public boolean isEarlyTerminated() {
		return earlyTerminated;
	}

	@Override
	public boolean isLast() {
		return true;
	}

	@Override
	public void setUseOriginalBody(Integer offset) {
		this.useOriginalBodyOffset = offset;
	}

	@Override
	public Integer getUseOriginalBody() {
		return useOriginalBodyOffset;
	}

	public String toString() {
		return "DefaultIcapChunkTrailer: [isPreviewChunk=" + preview + "] [wasEarlyTerminated=" + earlyTerminated + "] [useOriginalBodyOffset=" + useOriginalBodyOffset + "]";
	}
}
