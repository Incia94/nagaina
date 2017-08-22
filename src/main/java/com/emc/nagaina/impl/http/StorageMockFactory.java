package com.emc.nagaina.impl.http;

import com.emc.mongoose.api.model.data.DataInput;

import com.emc.nagaina.api.DataItemMock;
import com.emc.nagaina.api.StorageMock;
import com.emc.nagaina.api.StorageMockClient;
import com.emc.nagaina.api.StorageMockNode;
import com.emc.nagaina.impl.http.request.AtmosRequestHandler;
import com.emc.nagaina.impl.http.request.S3RequestHandler;
import com.emc.nagaina.impl.http.request.SwiftRequestHandler;

import io.netty.channel.ChannelInboundHandler;

import java.io.IOException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;

/**
 Created on 07.09.16.
 */
public class StorageMockFactory {

	private final String itemInputFile;
	private final int storageCapacity;
	private final int containerCapacity;
	private final int containerCountLimit;
	private final int metricsPeriodSec;
	private final long dropEveryConnection;
	private final long missEveryResponse;
	private final DataInput dataInput;
	private final int port;
	private final boolean sslFlag;
	private final float rateLimit;
	private final String idPrefix;
	private final int idRadix;

	public StorageMockFactory(
		final String itemInputFile, final int storageCapacity, final int containerCapacity,
		final int containerCountLimit, final int metricsPeriodSec, final long dropEveryConnection,
		final long missEveryResponse, final DataInput dataInput, final int port, final boolean sslFlag,
		final float rateLimit, final String idPrefix, final int idRadix
	) {
		this.itemInputFile = itemInputFile;
		this.storageCapacity = storageCapacity;
		this.containerCapacity = containerCapacity;
		this.containerCountLimit = containerCountLimit;
		this.metricsPeriodSec = metricsPeriodSec;
		this.dropEveryConnection = dropEveryConnection;
		this.missEveryResponse = missEveryResponse;
		this.dataInput = dataInput;
		this.port = port;
		this.sslFlag = sslFlag;
		this.rateLimit = rateLimit;
		this.idPrefix = idPrefix;
		this.idRadix = idRadix;
	}

	public StorageMockNode newStorageNodeMock()
	throws IOException {
		final List<ChannelInboundHandler> handlers = new ArrayList<>();
		final StorageMock<DataItemMock> storageMock = new WeightlessHttpStorageMock(
			itemInputFile, storageCapacity, containerCapacity, containerCountLimit, metricsPeriodSec,
			dropEveryConnection, missEveryResponse, dataInput, port, sslFlag, handlers
		);
		final StorageMockNode<DataItemMock> storageMockNode = new BasicStorageMockNode(
			storageMock, dataInput
		);
		final StorageMockClient<DataItemMock> client = storageMockNode.client();
		handlers.add(
			new SwiftRequestHandler<>(rateLimit, idPrefix, idRadix, storageMock, client)
		);
		handlers.add(
			new AtmosRequestHandler<>(rateLimit, idPrefix, idRadix, storageMock, client)
		);
		handlers.add(
			new S3RequestHandler<>(rateLimit, idPrefix, idRadix, storageMock, client)
		);
		return storageMockNode;
	}

	public StorageMock newStorageMock()
	throws IOException {
		final List<ChannelInboundHandler> handlers = new ArrayList<>();
		final StorageMock<DataItemMock> storageMock = new WeightlessHttpStorageMock(
			itemInputFile, storageCapacity, containerCapacity, containerCountLimit, metricsPeriodSec,
			dropEveryConnection, missEveryResponse, dataInput, port, sslFlag, handlers
		);
		try {
			handlers.add(
				new SwiftRequestHandler<>(rateLimit, idPrefix, idRadix, storageMock, null)
			);
			handlers.add(
				new AtmosRequestHandler<>(rateLimit, idPrefix, idRadix, storageMock, null)
			);
			handlers.add(
				new S3RequestHandler<>(rateLimit, idPrefix, idRadix, storageMock, null)
			);
		} catch(final RemoteException ignore) {
		}
		return storageMock;
	}
}
