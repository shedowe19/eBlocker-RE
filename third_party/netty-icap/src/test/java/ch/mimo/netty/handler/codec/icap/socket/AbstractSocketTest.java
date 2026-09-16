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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.socket.ServerSocketChannel;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import ch.mimo.netty.handler.codec.icap.AbstractJDKLoggerPreparation;
import ch.mimo.netty.handler.codec.icap.IcapChunkAggregator;
import ch.mimo.netty.handler.codec.icap.IcapChunkSeparator;
import ch.mimo.netty.handler.codec.icap.IcapRequestDecoder;
import ch.mimo.netty.handler.codec.icap.IcapRequestEncoder;
import ch.mimo.netty.handler.codec.icap.IcapResponseDecoder;
import ch.mimo.netty.handler.codec.icap.IcapResponseEncoder;

public abstract class AbstractSocketTest extends AbstractJDKLoggerPreparation {
	
	protected boolean runTrickleTests;
	
	private static final String RUN_TRICKLE_TESTS = "run.trickle.tests";
	
	public enum PipelineType {
		CLASSIC,
		AGGREGATOR,
		SEPARATOR_AGGREGATOR,
		TRICKLE
	}
	
	private static ExecutorService executor;
	
    private static final InetAddress LOCALHOST;

    static {
        InetAddress localhost = null;
        try {
            localhost = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            try {
                localhost = InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 });
            } catch (UnknownHostException e1) {
                try {
                    localhost = InetAddress.getByAddress(new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 });
                } catch (UnknownHostException e2) {
                    System.err.println("Failed to get the localhost.");
                    e2.printStackTrace();
                }
            }
        }

        LOCALHOST = localhost;
    }
	
//    @BeforeClass
//    public static void log() {
//    	System.setProperty("icap.test.output","true");
//    }
    
	@BeforeClass
	public static void init() {
		executor = Executors.newCachedThreadPool();
	}
	
	@AfterClass
	public static void destroy() {
    	executor.shutdown();
	}
	
	@Before
	public void evaluateRunTrickleTests() {
		runTrickleTests = Boolean.valueOf(System.getProperty(RUN_TRICKLE_TESTS));
	}
	
    protected abstract Class<? extends ServerChannel> newServerSocketChannelFactory();
    protected abstract Class<? extends Channel> newClientSocketChannelFactory();
    protected abstract EventLoopGroup newServerEventLoopGroup(Executor executor);
	protected abstract EventLoopGroup newClientEventLoopGroup(Executor executor);
	protected abstract Map<ChannelOption, Object> clientAdditionalChannelOptions();

    protected void runSocketTest(AbstractHandler serverHandler, AbstractHandler clientHandler, Object[] messages, PipelineType pipelineType) {
		EventLoopGroup serverBossGroup = newServerEventLoopGroup(executor);
		EventLoopGroup serverWorkerGroup = newServerEventLoopGroup(executor);
    	ServerBootstrap serverBootstrap  = new ServerBootstrap()
			.channel(newServerSocketChannelFactory())
			.childOption(ChannelOption.SO_REUSEADDR, true)
			.childOption(ChannelOption.TCP_NODELAY, true)
			.group(serverBossGroup, serverWorkerGroup);

    	EventLoopGroup clientGroup = newClientEventLoopGroup(executor);
        final Bootstrap clientBootstrap = new Bootstrap()
			.channel(newClientSocketChannelFactory())
			.option(ChannelOption.TCP_NODELAY, true)
			.group(clientGroup);
        for(Map.Entry<ChannelOption, Object> e : clientAdditionalChannelOptions().entrySet()) {
			clientBootstrap.option(e.getKey(), e.getValue());
		}

        ChannelInitializer serverChannelInitializer = classicServerChannelInitializer(serverHandler);
        ChannelInitializer clientChannelInitializer = classicClientChannelInitializer(clientHandler);
		switch (pipelineType) {
			case AGGREGATOR:
				serverChannelInitializer = classicServerPipelineWithChunkAggregator(serverHandler);
				clientChannelInitializer = classicClientPipelineWithChunkAggregator(clientHandler);
				break;
			case SEPARATOR_AGGREGATOR:
				serverChannelInitializer = classicServerPipelineWithAggregatorAndSeparator(serverHandler);
				clientChannelInitializer = classicClientPipelineWithAggregatorAndSeparator(clientHandler);
			break;
			case TRICKLE:
				if(runTrickleTests) {
					serverChannelInitializer = serverTricklePipeline(serverHandler);
					clientChannelInitializer = clientTricklePipeline(clientHandler);
			}
				break;
			case CLASSIC:
			default:
				break;
		}

		serverBootstrap.childHandler(serverChannelInitializer);
        clientBootstrap.handler(clientChannelInitializer);

        ChannelFuture serverChannel = serverBootstrap.bind(new InetSocketAddress(0));
        assertTrue(serverChannel.awaitUninterruptibly().isSuccess());
        int port = ((ServerSocketChannel)serverChannel.channel()).localAddress().getPort();
        
        ChannelFuture channelFuture = clientBootstrap.connect(new InetSocketAddress(LOCALHOST,port));
        assertTrue(channelFuture.awaitUninterruptibly().isSuccess());

        Channel clientChannel = channelFuture.channel();

		for (Object message : messages) {
			clientChannel.write(message);
		}
		clientChannel.flush();

        while(!clientHandler.isProcessed()) {
        	if(clientHandler.hasException()) {
        		break;
        	}
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                // NOOP
            }        	
        }
        
        while(!serverHandler.isProcessed()) {
        	if(serverHandler.hasException()) {
        		break;
        	}
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                // NOOP
            }  
        }

        serverChannel.channel().close();
        clientChannel.close();
        // shutdownNow is deprecated and but we do not want to wait at all
        serverBossGroup.shutdownGracefully();
        serverWorkerGroup.shutdownGracefully();
        clientGroup.shutdownGracefully();

        if(serverHandler.hasException()) {
        	serverHandler.getExceptionCause().printStackTrace();
        	fail("Server Handler has experienced an exception");
        }
        
        if(clientHandler.hasException()) {
        	clientHandler.getExceptionCause().printStackTrace();
        	fail("Server Handler has experienced an exception");
        }
    }

    protected ChannelInitializer classicServerChannelInitializer(final ChannelHandler serverHandler) {
		return new ChannelInitializer() {
			@Override
			protected void initChannel(Channel ch) {
				ch.pipeline()
					.addLast("decoder", new IcapRequestDecoder())
					.addLast("encoder", new IcapResponseEncoder())
					.addLast("handler", serverHandler);
			}
		};
	}

	protected ChannelInitializer classicClientChannelInitializer(final ChannelHandler clientHandler) {
		return new ChannelInitializer() {
			@Override
			protected void initChannel(Channel ch) {
				ch.pipeline()
					.addLast("encoder", new IcapRequestEncoder())
					.addLast("decoder", new IcapResponseDecoder())
					.addLast("handler", clientHandler);
			}
		};
	}
    
    protected ChannelInitializer classicServerPipelineWithChunkAggregator(final ChannelHandler serverHandler) {
    	return new ChannelInitializer() {
			@Override
			protected void initChannel(Channel ch) {
				ch.pipeline()
				.addLast("decoder", new IcapRequestDecoder())
				.addLast("chunkAggregator", new IcapChunkAggregator(4012))
				.addLast("encoder", new IcapResponseEncoder())
				.addLast("handler", serverHandler);
			}
		};
    }

	protected ChannelInitializer classicClientPipelineWithChunkAggregator(final ChannelHandler clientHandler) {
		return new ChannelInitializer() {
			@Override
			protected void initChannel(Channel ch) {
				ch.pipeline()
					.addLast("encoder", new IcapRequestEncoder())
					.addLast("decoder", new IcapResponseDecoder())
					.addLast("handler", clientHandler);
			}
		};
	}

    protected ChannelInitializer serverTricklePipeline(final ChannelHandler serverHandler) {
    	return new ChannelInitializer() {
			@Override
			protected void initChannel(Channel ch) {
				ch.pipeline()
					.addLast("decoder", new IcapRequestDecoder())
					.addLast("encoder", new IcapResponseEncoder())
					.addLast("handler", serverHandler);
			}
		};
    }

	protected ChannelInitializer clientTricklePipeline(final ChannelHandler clientHandler) {
    	return new ChannelInitializer() {
			@Override
			protected void initChannel(Channel ch) {
				ch.pipeline()
					.addLast("trickle", new TrickleDownstreamHandler(20,3))
					.addLast("encoder", new IcapRequestEncoder())
					.addLast("decoder", new IcapResponseDecoder())
					.addLast("handler", clientHandler);
			}
		};
	}
    
    protected ChannelInitializer classicServerPipelineWithAggregatorAndSeparator(final ChannelHandler serverHandler) {
    	return new ChannelInitializer() {
			@Override
			protected void initChannel(Channel ch) {
				ch.pipeline()
					.addLast("decoder", new IcapRequestDecoder())
					.addLast("chunkAggregator", new IcapChunkAggregator(4012))
					.addLast("encoder", new IcapResponseEncoder())
					.addLast("chunkSeparator", new IcapChunkSeparator(4012))
					.addLast("handler", serverHandler);
			}
		};
    }

	protected ChannelInitializer classicClientPipelineWithAggregatorAndSeparator(final ChannelHandler clientHandler) {
    	return new ChannelInitializer() {
			@Override
			protected void initChannel(Channel ch) {
				ch.pipeline()
					.addLast("encoder", new IcapRequestEncoder())
					.addLast("chunkSeparator", new IcapChunkSeparator(4021))
					.addLast("decoder", new IcapResponseDecoder())
					.addLast("chunkAggregator", new IcapChunkAggregator(4012))
					.addLast("handler", clientHandler);
			}
		};
	}
}
