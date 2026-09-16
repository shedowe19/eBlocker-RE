package org.restexpress.pipeline;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.util.ReferenceCountUtil;
import java.util.List;

public class RequestURLDecoder
extends MessageToMessageDecoder<HttpObject>
{
	private boolean shouldUpload(HttpObject object)
	{
		HttpRequest request = (HttpRequest) object;
		return (request.method().equals(HttpMethod.POST) && HttpPostRequestDecoder.isMultipart(request));
	}

	@Override
	protected void decode(ChannelHandlerContext channelHandlerContext, HttpObject object, List<Object> list)
	throws Exception
	{
		if (object instanceof HttpRequest)
		{
			if (shouldUpload(object))
			{
				if (channelHandlerContext.pipeline().get("aggregator") != null)
				{
					channelHandlerContext.pipeline().remove("aggregator");
				}

				if (channelHandlerContext.pipeline().get("DefaultRequestHandler") != null)
				{
					channelHandlerContext.pipeline().remove("DefaultRequestHandler");
				}
			}
			else
			{
				if (channelHandlerContext.pipeline().get("FileUploadHandler") != null)
				{
					channelHandlerContext.pipeline().remove("FileUploadHandler");
				}
			}
		}

		list.add(ReferenceCountUtil.retain(object));
	}
}
