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
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.List;

/**
 * Main ICAP message decoder implementation. this decoder is bases on a {@link ReplayingDecoder}
 * 
 * Due to the complexity of an ICAP message the decoder was implement with individual states that reside
 * in their own classes.
 * 
 * For a full list of states that are used within this decoder: {@link StateEnum}  
 * 
 * @author Michael Mimo Moratti (mimo@mimo.ch)
 *
 * @see IcapRequestDecoder
 * @see IcapResponseDecoder
 */

public abstract class IcapMessageDecoder extends ReplayingDecoder<StateEnum> {

	private final InternalLogger LOG;
	
    protected final int maxInitialLineLength;
    protected final int maxIcapHeaderSize;
    protected final int maxHttpHeaderSize;
    protected final int maxChunkSize;
    
	protected IcapMessage message;
	
	protected int currentChunkSize;
	
	
	
    /**
     * Creates a new instance with the default
     * {@code maxInitialLineLength (4096}}, {@code maxIcapHeaderSize (8192)}, {@code maxHttpHeaderSize (8192)}, and
     * {@code maxChunkSize (8192)}.
     */
    protected IcapMessageDecoder() {
        this(4096,8192,8192,8192);
    }
    
    /**
     * Creates a new instance with the specified parameters.
     * @param maxInitialLineLength
     * @param maxIcapHeaderSize
     * @param maxHttpHeaderSize
     * @param maxChunkSize
     */
    protected IcapMessageDecoder(int maxInitialLineLength, int maxIcapHeaderSize, int maxHttpHeaderSize, int maxChunkSize) {
		super(StateEnum.SKIP_CONTROL_CHARS);
        LOG = InternalLoggerFactory.getInstance(getClass());
        if (maxInitialLineLength <= 0) {
            throw new IllegalArgumentException("maxInitialLineLength must be a positive integer: " + maxInitialLineLength);
        }
        if (maxIcapHeaderSize <= 0) {
            throw new IllegalArgumentException("maxIcapHeaderSize must be a positive integer: " + maxIcapHeaderSize);
        }
        if(maxHttpHeaderSize <= 0) {
        	throw new IllegalArgumentException("maxHttpHeaderSize must be a positive integer: " + maxIcapHeaderSize);
        }
        if (maxChunkSize <= 0) {
            throw new IllegalArgumentException("maxChunkSize must be a positive integer: " + maxChunkSize);
        }
        this.maxInitialLineLength = maxInitialLineLength;
        this.maxIcapHeaderSize = maxIcapHeaderSize;
        this.maxHttpHeaderSize = maxHttpHeaderSize;
        this.maxChunkSize = maxChunkSize;
    }

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
    	StateEnum stateEnumValue = state();
		if(stateEnumValue != null) {
			try {
				State state = stateEnumValue.getState();
				LOG.debug("Executing state [" + state + ']');
				state.onEntry(in,this);
				StateReturnValue returnValue = state.execute(in,this);
				LOG.debug("Return value from state [" + state + "] = [" + returnValue + "]");
				StateEnum nextState = state.onExit(in,this,returnValue.getDecisionInformation());
				LOG.debug("Next State [" + nextState + "]");
				if(nextState != null) {
					checkpoint(nextState);
				} else {
					reset();
				}
				if(returnValue.isRelevant()) {
					out.add(returnValue.getValue());
				}
			} catch(DecodingException e) {
				reset();
				throw e;
			}
		}
	}
	
	/**
	 * set the decoders message to NULL and the next checkpoint to {@link StateEnum#SKIP_CONTROL_CHARS}
	 */
    private void reset() {
        this.message = null;
        checkpoint(StateEnum.SKIP_CONTROL_CHARS);
    }
	
	public abstract boolean isDecodingResponse();
	
	protected abstract IcapMessage createMessage(String[] initialLine);
}
