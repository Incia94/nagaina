package com.emc.mongoose.storage.mock.api;

import com.emc.mongoose.api.model.svc.Service;
import com.emc.mongoose.storage.mock.api.exception.ContainerMockException;

import java.rmi.RemoteException;

/**
 Created on 31.08.16.
 */
public interface StorageMockServer<T extends DataItemMock>
extends Service {
	T getObjectRemotely(
		final String containerName, final String id, final long offset, final long size
	) throws RemoteException, ContainerMockException;
}
