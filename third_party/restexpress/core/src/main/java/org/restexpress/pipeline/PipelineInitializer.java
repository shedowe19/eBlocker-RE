/*
 * Copyright 2010, eCollege, Inc.  All rights reserved.
 */
package org.restexpress.pipeline;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import org.restexpress.RestExpress;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.EventExecutorGroup;

/**
 * Provides a tiny DSL to define the pipeline features.
 *
 * @author toddf
 * @since Aug 27, 2010
 */
public class PipelineInitializer
extends ChannelInitializer<SocketChannel>
{
	// SECTION: CONSTANTS

	private static final String AGGREGATOR = "aggregator";
	private static final int DEFAULT_MAX_CONTENT_LENGTH = 20480;

	// SECTION: INSTANCE VARIABLES

	private Map<String, ChannelHandler> requestHandlers = new HashMap<>();
	private int maxContentLength = DEFAULT_MAX_CONTENT_LENGTH;
	private EventExecutorGroup eventExecutorGroup = null;
	private SslContext sslContext = null;
	private boolean useCompression = true;
	private long readTimeout = -1L;
	private TimeUnit readTimeoutUnit = TimeUnit.SECONDS;
	private boolean supportFileUpload = false;

	// SECTION: CONSTRUCTORS

	public PipelineInitializer()
	{
		super();
	}

	// SECTION: BUILDER METHODS


	public PipelineInitializer addRequestHandler(ChannelHandler handler)
	{
		return addRequestHandler(handler.getClass().getSimpleName(), handler);
	}

	public PipelineInitializer addRequestHandler(String name, ChannelHandler handler)
	{
		requestHandlers.computeIfAbsent(name, k -> handler);
		return this;
	}

	public PipelineInitializer setExecutionHandler(EventExecutorGroup executorGroup)
	{
		this.eventExecutorGroup = executorGroup;
		return this;
	}

	/**
	 * Set the maximum length of the aggregated (chunked) content. If the length
	 * of the aggregated content exceeds this value, a TooLongFrameException
	 * will be raised during the request, which can be mapped in the RestExpress
	 * server to return a BadRequestException, if desired.
	 *
	 * @param value
	 * @return this PipelineBuilder for method chaining.
	 */
	public PipelineInitializer setMaxContentLength(int value)
	{
		this.maxContentLength = value;
		return this;
	}

	public PipelineInitializer setSSLContext(SslContext sslContext)
	{
		this.sslContext = sslContext;
		return this;
	}

	public PipelineInitializer setReadTimeout(long timeout, TimeUnit timeUnit)
	{
		this.readTimeout = timeout;
		this.readTimeoutUnit = timeUnit;
		return this;
	}

	public SslContext getSSLContext()
	{
		return sslContext;
	}

	// SECTION: CHANNEL PIPELINE FACTORY

	@Override
	public void initChannel(SocketChannel ch) throws Exception
	{
		ChannelPipeline pipeline = ch.pipeline();

		if (null != sslContext)
		{
			pipeline.addLast("ssl", sslContext.newHandler(ByteBufAllocator.DEFAULT));
		}

		// Inbound handlers

		if (readTimeout > 0)
		{
			pipeline.addLast("timeout", new ReadTimeoutHandler(readTimeout, readTimeoutUnit));
		}

		pipeline.addLast("decoder", new HttpRequestDecoder());
		pipeline.addLast("inflater", new HttpContentDecompressor());

		// Routes request to either the default handler or the file upload one
		if (supportFileUpload)
		{
			pipeline.addLast("URLDecoder", new RequestURLDecoder());
		}

		// Outbound handlers
		pipeline.addLast("encoder", new HttpResponseEncoder());
		pipeline.addLast("chunkWriter", new ChunkedWriteHandler());

		if (useCompression)
		{
			pipeline.addLast("deflater", new HttpContentCompressor());
		}

		// Aggregator MUST be added last, otherwise results are not correct
		pipeline.addLast(AGGREGATOR, new HttpObjectAggregator(maxContentLength));

		addAllHandlers(pipeline);
	}

	private void addAllHandlers(ChannelPipeline pipeline)
	{
		for (Entry<String, ChannelHandler> entry : requestHandlers.entrySet())
		{
			String name = entry.getKey();
			ChannelHandler handler = entry.getValue();

			if (supportFileUpload && isFileUploadHandler(name))
			{
				if (eventExecutorGroup != null)
				{
					pipeline.addBefore(eventExecutorGroup, AGGREGATOR, handler.getClass().getSimpleName(), handler);
				}
				else
				{
					pipeline.addBefore(AGGREGATOR, handler.getClass().getSimpleName(), handler);
				}
			}
			else
			{
				if (eventExecutorGroup != null)
				{
					pipeline.addLast(eventExecutorGroup, handler.getClass().getSimpleName(), handler);
				}
				else
				{
					pipeline.addLast(handler.getClass().getSimpleName(), handler);
				}
			}
		}
	}

	public PipelineInitializer setUseCompression(boolean shouldUseCompression)
	{
		this.useCompression = shouldUseCompression;
		return this;
	}

	public PipelineInitializer setSupportFileUpload(boolean shouldSupportFileUpload)
	{
		this.supportFileUpload = shouldSupportFileUpload;
		return this;
	}

	private boolean isFileUploadHandler(String name)
	{
		return RestExpress.FILEUPLOAD_HANDLER_NAME.equals(name);
	}
}
