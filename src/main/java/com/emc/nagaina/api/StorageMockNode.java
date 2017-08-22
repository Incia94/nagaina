package com.emc.nagaina.api;

import com.emc.mongoose.api.model.concurrent.Daemon;

/**
 Created on 07.09.16.
 */
public interface StorageMockNode<T extends DataItemMock>
extends Daemon {

	StorageMockClient<T> client();

	StorageMockServer<T> server();

}
