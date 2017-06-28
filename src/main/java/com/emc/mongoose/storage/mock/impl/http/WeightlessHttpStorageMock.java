package com.emc.mongoose.storage.mock.impl.http;

import com.emc.mongoose.model.NamingThreadFactory;
import com.emc.mongoose.common.concurrent.ThreadUtil;
import com.emc.mongoose.common.net.ssl.SslContext;
import com.emc.mongoose.model.data.ContentSource;
import com.emc.mongoose.storage.mock.api.DataItemMock;
import com.emc.mongoose.storage.mock.impl.base.BasicDataItemMock;
import com.emc.mongoose.storage.mock.impl.base.StorageMockBase;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.ui.log.Loggers;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;

import org.apache.commons.lang.SystemUtils;

import org.apache.logging.log4j.Level;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 Created on 11.07.16.
 */
public final class WeightlessHttpStorageMock
extends StorageMockBase<DataItemMock>{

	public static final String SVC_NAME = WeightlessHttpStorageMock.class.getSimpleName().toLowerCase();

	private EventLoopGroup dispatcherGroup;
	private EventLoopGroup workerGroup;
	private Channel channel;
	private final List<ChannelInboundHandler> handlers;
	private final int port;
	private final boolean sslFlag;

	@SuppressWarnings("ConstantConditions")
	public WeightlessHttpStorageMock(
		final String itemInputFile, final int storageCapacity, final int containerCapacity,
		final int containerCountLimit, final int metricsPeriodSec, final long dropEveryConnection,
		final long missEveryResponse, final ContentSource contentSrc, final int port, final boolean sslFlag,
		final List<ChannelInboundHandler> handlers
	) {
		super(
			itemInputFile, storageCapacity, containerCapacity, containerCountLimit, metricsPeriodSec,
			dropEveryConnection, missEveryResponse, contentSrc
		);
		this.port = port;
		this.sslFlag = sslFlag;
		final int workerCount/*;
		final int confWorkerCount = storageConfig.getDriverConfig().getIoConfig().getWorkers();
		if(confWorkerCount < 1) {
			workerCount*/ = ThreadUtil.getHardwareThreadCount();
		/*} else {
			workerCount = confWorkerCount;
		}*/

		this.handlers = handlers;

		try {
			if(SystemUtils.IS_OS_LINUX) {
				dispatcherGroup = new EpollEventLoopGroup(
					1, new NamingThreadFactory("dispatcher@port#" + port + "-", true)
				);
				workerGroup = new EpollEventLoopGroup(
					workerCount, new NamingThreadFactory("ioworker@port#" + port + "-", true)
				);
			} else {
				dispatcherGroup = new NioEventLoopGroup(
					1, new NamingThreadFactory("dispatcher@port#" + port + "-", true)
				);
				workerGroup = new NioEventLoopGroup(
					workerCount, new NamingThreadFactory("ioworker@port#" + port + "-", true)
				);
			}
			final ServerBootstrap serverBootstrap = new ServerBootstrap();
			serverBootstrap.group(dispatcherGroup, workerGroup)
				.channel(
					SystemUtils.IS_OS_LINUX ?
					EpollServerSocketChannel.class : NioServerSocketChannel.class
				)
				.childHandler(
					new ChannelInitializer<SocketChannel>() {
						@Override
						protected final void initChannel(final SocketChannel socketChannel)
						throws Exception {
							final ChannelPipeline pipeline = socketChannel.pipeline();
							if(sslFlag) {
								Loggers.MSG.debug("SSL/TLS is enabled for the channel");
								final SSLEngine sslEngine = SslContext.INSTANCE.createSSLEngine();
								sslEngine.setEnabledProtocols(
									new String[] { "TLSv1", "TLSv1.1", "TLSv1.2", "SSLv3" }
								);
								sslEngine.setUseClientMode(false);
								sslEngine.setEnabledCipherSuites(
									SslContext.INSTANCE.getServerSocketFactory().getSupportedCipherSuites()
								);
								sslEngine.setNeedClientAuth(false);
								sslEngine.setEnableSessionCreation(true);
								pipeline.addLast(new SslHandler(sslEngine));
							}
							pipeline.addLast(new HttpServerCodec());
							pipeline.addLast(new ChunkedWriteHandler());
							for(final ChannelInboundHandler handler: handlers) {
								pipeline.addLast(handler);
							}
						}
					}
				);
			final ChannelFuture bind = serverBootstrap.bind(port);
			bind.sync();
			channel = bind.sync().channel();
		} catch(final Exception e) {
			LogUtil.exception(Level.ERROR, e, "Failed to start the service at port #{}", port);
			throw new IllegalStateException();
		}
		Loggers.MSG.info("Listening the port #{}", port);
	}

	@Override
	public final int getPort() {
		return port;
	}

	@Override
	public final boolean sslEnabled() {
		return sslFlag;
	}

	@Override
	public final boolean await(final long timeout, final TimeUnit timeUnit)
	throws InterruptedException {
		try {
			channel.closeFuture().await(timeout, timeUnit); // one channel is enough
		} catch(final InterruptedException e) {
			Loggers.MSG.info("Interrupting the Nagaina");
		}

		return true;
	}

	@Override
	protected final void doClose()
	throws IOException {
		super.doClose();
		channel.close();
		dispatcherGroup.shutdownGracefully(1, 1, TimeUnit.SECONDS);
		workerGroup.shutdownGracefully(1, 1, TimeUnit.SECONDS);
		handlers.clear();
	}

	@Override
	protected DataItemMock newDataObject(final String id, final long offset, final long size) {
		return new BasicDataItemMock(id, offset, size, 0);
	}
}
