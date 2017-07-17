package com.emc.mongoose.storage.mock.impl.base;

import com.emc.mongoose.api.common.net.ServiceUtil;
import com.emc.mongoose.api.model.DaemonBase;
import com.emc.mongoose.storage.mock.api.DataItemMock;
import com.emc.mongoose.storage.mock.api.StorageMock;
import com.emc.mongoose.storage.mock.api.StorageMockServer;
import com.emc.mongoose.storage.mock.api.exception.ContainerMockException;
import com.emc.mongoose.storage.mock.impl.remote.MDns;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.ui.log.Loggers;
import static com.emc.mongoose.storage.mock.impl.http.WeightlessHttpStorageMock.SVC_NAME;

import org.apache.logging.log4j.Level;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

import java.io.IOException;
import java.rmi.RemoteException;
import java.util.concurrent.TimeUnit;
import static java.rmi.registry.Registry.REGISTRY_PORT;

/**
 Created on 06.09.16.
 */
public class BasicStorageMockServer<T extends DataItemMock>
extends DaemonBase
implements StorageMockServer<T> {

	private final StorageMock<T> storage;
	private final String svcName;
	private final JmDNS jmDns;
	private ServiceInfo serviceInfo;

	public BasicStorageMockServer(final StorageMock<T> storage, final JmDNS jmDns)
	throws RemoteException {
		this.storage = storage;
		this.jmDns = jmDns;
		this.svcName = SVC_NAME + "_" + storage.getPort();
	}

	@Override
	protected final void doStart()
	throws IllegalStateException {
		try {
			ServiceUtil.create(this, REGISTRY_PORT);
			Loggers.MSG.info("Register the service");
			serviceInfo = ServiceInfo.create(MDns.Type.HTTP.toString(), svcName, MDns.DEFAULT_PORT, "storage mock");
			jmDns.registerService(serviceInfo);
			Loggers.MSG.info("Storage mock was registered as service");
		} catch(final IOException e) {
			LogUtil.exception(Level.ERROR, e, "Failed to register as service");
		}
		try {
			storage.start();
		} catch(final RemoteException e) {
			throw new IllegalStateException(e);
		}
	}
	
	@Override
	protected void doShutdown()
	throws IllegalStateException {
		try {
			storage.shutdown();
		} catch(final RemoteException e) {
			throw new IllegalStateException(e);
		}
	}
	
	@Override
	protected void doInterrupt()
	throws IllegalStateException {
		jmDns.unregisterService(serviceInfo);
		try {
			ServiceUtil.close(this);
			storage.interrupt();
		} catch(final Exception e) {
			LogUtil.exception(Level.WARN, e, "Failed to interrupt the storage mock service");
		}
	}
	
	@Override
	public final boolean await(final long timeout, final TimeUnit timeUnit)
	throws InterruptedException, RemoteException {
		return storage.await(timeout, timeUnit);
	}
	
	@Override
	protected final void doClose() {
	}
	
	@Override
	public T getObjectRemotely(
		final String containerName, final String id, final long offset, final long size
	) throws ContainerMockException {
		return storage.getObject(containerName, id, offset, size);
	}

	@Override
	public final int getRegistryPort() {
		return REGISTRY_PORT;
	}

	@Override
	public final String getName() {
		return null;
	}
}
