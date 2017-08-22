package com.emc.nagaina.impl.http;

import com.emc.mongoose.api.common.exception.OmgDoesNotPerformException;
import com.emc.mongoose.api.common.exception.OmgLookAtMyConsoleException;
import com.emc.mongoose.api.common.net.NetUtil;
import com.emc.mongoose.api.model.concurrent.DaemonBase;
import com.emc.mongoose.api.model.data.DataInput;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.ui.log.Loggers;

import com.emc.nagaina.api.DataItemMock;
import com.emc.nagaina.api.StorageMock;
import com.emc.nagaina.api.StorageMockClient;
import com.emc.nagaina.api.StorageMockNode;
import com.emc.nagaina.api.StorageMockServer;
import com.emc.nagaina.impl.base.BasicStorageMockClient;
import com.emc.nagaina.impl.base.BasicStorageMockServer;

import org.apache.logging.log4j.Level;

import javax.jmdns.JmDNS;
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.concurrent.TimeUnit;

/**
 Created on 09.09.16.
 */
public class BasicStorageMockNode
extends DaemonBase
implements StorageMockNode<DataItemMock> {

	private JmDNS jmDns;
	private StorageMockClient<DataItemMock> client;
	private StorageMockServer<DataItemMock> server;

	public BasicStorageMockNode(final StorageMock<DataItemMock> storage, final DataInput dataInput) {
		// System.setProperty("java.rmi.server.hostname", NetUtil.getHostAddrString()); workaround
		try {
			jmDns = JmDNS.create(NetUtil.getHostAddr());
			Loggers.MSG.info("mDNS address: " + jmDns.getInetAddress());
			server = new BasicStorageMockServer<>(storage, jmDns);
			client = new BasicStorageMockClient<>(dataInput, jmDns);
		} catch(final IOException | OmgDoesNotPerformException | OmgLookAtMyConsoleException e) {
			LogUtil.exception(Level.ERROR, e, "Failed to create storage mock node");
		}
	}

	@Override
	public StorageMockClient<DataItemMock> client() {
		return client;
	}

	@Override
	public StorageMockServer<DataItemMock> server() {
		return server;
	}

	@Override
	protected void doStart()
	throws IllegalStateException {
		try {
			server.start();
			client.start();
		} catch(final RemoteException e) {
			throw new IllegalStateException(e);
		}
	}
	
	@Override
	protected void doShutdown()
	throws IllegalStateException {
		try {
			server.shutdown();
			client.shutdown();
		} catch(final RemoteException e) {
			throw new IllegalStateException(e);
		}
	}
	
	@Override
	protected void doInterrupt()
	throws IllegalStateException {
		try {
			server.interrupt();
			client.interrupt();
		} catch(final RemoteException e) {
			throw new IllegalStateException(e);
		}
	}
	
	@Override
	public boolean await(final long timeout, final TimeUnit timeUnit)
	throws InterruptedException {
		try {
			return server.await(timeout, timeUnit);
		} catch(final RemoteException ignore) {
		}
		return false;
	}
	
	@Override
	protected void doClose()
	throws IOException {
		server.close();
		client.close();
		jmDns.close();
	}
}
