package com.emc.nagaina.api;

import com.emc.mongoose.api.model.concurrent.Daemon;

import javax.jmdns.ServiceListener;
import java.util.concurrent.ExecutionException;

/**
 Created on 01.09.16.
 */
public interface StorageMockClient<T extends DataItemMock>
extends ServiceListener, Daemon {
	T getObject(
		final String containerName, final String id, final long offset, final long size
	) throws ExecutionException, InterruptedException;
}
