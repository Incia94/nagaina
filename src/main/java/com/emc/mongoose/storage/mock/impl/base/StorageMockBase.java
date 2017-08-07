package com.emc.mongoose.storage.mock.impl.base;

import com.emc.mongoose.api.common.ByteRange;
import com.emc.mongoose.api.common.collection.ListingLRUMap;
import com.emc.mongoose.api.model.concurrent.DaemonBase;
import com.emc.mongoose.api.model.data.DataInput;
import com.emc.mongoose.api.model.item.ItemFactory;
import com.emc.mongoose.api.model.item.CsvFileItemInput;
import com.emc.mongoose.storage.mock.api.DataItemMock;
import com.emc.mongoose.storage.mock.api.ObjectContainerMock;
import com.emc.mongoose.storage.mock.api.StorageIoStats;
import com.emc.mongoose.storage.mock.api.StorageMock;
import com.emc.mongoose.storage.mock.api.exception.ContainerMockException;
import com.emc.mongoose.storage.mock.api.exception.ContainerMockNotFoundException;
import com.emc.mongoose.storage.mock.api.exception.ObjectMockNotFoundException;
import com.emc.mongoose.storage.mock.api.exception.StorageMockCapacityLimitReachedException;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.ui.log.Loggers;

import org.apache.logging.log4j.Level;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 Created on 19.07.16.
 */
public abstract class StorageMockBase<I extends DataItemMock>
extends DaemonBase
implements StorageMock<I> {
	
	private final String itemInputFile;
	private final StorageIoStats ioStats;
	protected final DataInput dataInput;
	private final int storageCapacity, containerCapacity;
	private final long dropEveryConnection, missEveryResponse;

	private final ListingLRUMap<String, ObjectContainerMock<I>> storageMap;
	private final ObjectContainerMock<I> defaultContainer;
	private final AtomicLong connCounter = new AtomicLong();
	private final AtomicLong respCounter = new AtomicLong(0);

	private volatile boolean isCapacityExhausted = false;

	@SuppressWarnings("unchecked")
	public StorageMockBase(
		final String itemInputFile, final int storageCapacity, final int containerCapacity,
		final int containerCountLimit, final int metricsPeriodSec, final long dropEveryConnection,
		final long missEveryResponse, final DataInput dataInput
	) {
		super();
		storageMap = new ListingLRUMap<>(containerCountLimit);
		this.itemInputFile = itemInputFile;
		this.dataInput = dataInput;
		this.ioStats = new BasicStorageIoStats(this, metricsPeriodSec);
		this.storageCapacity = storageCapacity;
		this.containerCapacity = containerCapacity;
		this.dropEveryConnection = dropEveryConnection;
		this.missEveryResponse = missEveryResponse;
		this.defaultContainer = new WeightlessObjectContainerMock<>(containerCapacity);
		storageMap.put(DEFAULT_CONTAINER_NAME, defaultContainer);
	}

	////////////////////////////////////////////////////////////////////////////////////////////////
	// Container methods
	////////////////////////////////////////////////////////////////////////////////////////////////

	@Override
	public final ObjectContainerMock<I> createContainer(final String name) {
		final ObjectContainerMock<I> container = new WeightlessObjectContainerMock<>(containerCapacity);
		synchronized(storageMap) {
			storageMap.put(name, container);
		}
		ioStats.containerCreate();
		return container;
	}

	@Override
	public final ObjectContainerMock<I> getContainer(final String name) {
		synchronized(storageMap) {
			return storageMap.get(name);
		}
	}

	@Override
	public final void deleteContainer(final String name) {
		synchronized(storageMap) {
			storageMap.remove(name);
		}
		ioStats.containerDelete();
	}

	////////////////////////////////////////////////////////////////////////////////////////////////
	// Object methods
	////////////////////////////////////////////////////////////////////////////////////////////////

	protected abstract I newDataObject(final String id, final long offset, final long size);

	@Override
	public final void createObject(
		final String containerName, final String id, final long offset, final long size
	) throws ContainerMockNotFoundException, StorageMockCapacityLimitReachedException {
		if(isCapacityExhausted) {
			throw new StorageMockCapacityLimitReachedException();
		}
		final ObjectContainerMock<I> c = getContainer(containerName);
		if(c != null) {
			c.put(id, newDataObject(id, offset, size));
		} else {
			throw new ContainerMockNotFoundException(containerName);
		}
	}
	
	@Override
	public final void updateObject(
		final String containerName, final String id, final long size, final ByteRange byteRange
	) throws ContainerMockException, ObjectMockNotFoundException {
		final ObjectContainerMock<I> c = getContainer(containerName);
		if(c != null) {
			final I obj = c.get(id);
			if(obj != null) {
				final long rangeBeg = byteRange.getBeg();
				final long rangeEnd = byteRange.getEnd();
				final long rangeSize = byteRange.getSize();
				try {
					final long baseObjSize = obj.size();
					if(rangeSize > -1) {
						obj.append(rangeSize);
					} else {
						if(rangeBeg > -1) {
							if(rangeEnd > -1) {
								if(rangeEnd >= rangeBeg) {
									obj.update(rangeBeg, rangeEnd - rangeBeg + 1);
								} else {
									throw new AssertionError();
								}
							} else if(rangeBeg == baseObjSize) {
								obj.append(size);
							} else {
								// rewrite the range with same data
								// so do nothing here
							}
						} else if(rangeEnd > -1) {
							obj.update(baseObjSize - rangeEnd, baseObjSize);
						} else {
							throw new AssertionError();
						}
					}
				} catch(final IOException e) {
					throw new AssertionError(e);
				}
			} else {
				throw new ObjectMockNotFoundException(id);
			}
		} else {
			throw new ContainerMockNotFoundException(containerName);
		}
	}

	@Override
	public final I getObject(
		final String containerName, final String id, final long offset, final long size
	) throws ContainerMockException {
		// TODO partial read using offset and size args
		final ObjectContainerMock<I> c = getContainer(containerName);
		if(c != null) {
			final I obj = c.get(id);
			if(obj != null) {
				obj.setDataInput(dataInput);
			}
			return obj;
		} else {
			throw new ContainerMockNotFoundException(containerName);
		}
	}

	@Override
	public final void deleteObject(
		final String containerName, final String id, final long offset, final long size
	) throws ContainerMockNotFoundException {
		final ObjectContainerMock<I> c = getContainer(containerName);
		if(c != null) {
			c.remove(id);
		} else {
			throw new ContainerMockNotFoundException(containerName);
		}
	}

	@Override
	public final I listObjects(
		final String containerName, final String afterObjectId, final Collection<I> outputBuffer,
		final int limit
	) throws ContainerMockException {
		final ObjectContainerMock<I> container = getContainer(containerName);
		if(container != null) {
			return container.list(afterObjectId, outputBuffer, limit);
		} else {
			throw new ContainerMockNotFoundException(containerName);
		}
	}

	private final Thread storageCapacityMonitorThread = new Thread("storageMockCapacityMonitor") {
		{
			setDaemon(true);
		}
		@SuppressWarnings("InfiniteLoopStatement")
		@Override
		public final void run() {
			long currObjCount;
			try {
				while(true) {
					TimeUnit.SECONDS.sleep(1);
					currObjCount = getSize();
					if(!isCapacityExhausted && currObjCount > storageCapacity) {
						isCapacityExhausted = true;
					} else if(isCapacityExhausted && currObjCount <= storageCapacity) {
						isCapacityExhausted = false;
					}
				}
			} catch(final InterruptedException ignored) {
			}
		}
	};

	@Override
	protected void doStart() {
		loadPersistedDataItems();
		ioStats.start();
		storageCapacityMonitorThread.start();
	}
	
	@Override
	protected void doShutdown()
	throws IllegalStateException {
	}
	
	@Override
	protected void doInterrupt()
	throws IllegalStateException {
		storageCapacityMonitorThread.interrupt();
	}
	
	@Override
	public long getSize() {
		long size = 0;
		synchronized(storageMap) {
			for(final ObjectContainerMock<I> container : storageMap.values()) {
				size += container.size();
			}
		}
		return size;
	}

	@Override
	public long getCapacity() {
		return storageCapacity;
	}

	@Override
	public final boolean dropConnection() {
		if(dropEveryConnection > 0) {
			if(0 == connCounter.incrementAndGet() % dropEveryConnection) {
				return true;
			}
		}
		return false;
	}

	@Override
	public final boolean missResponse() {
		if(missEveryResponse > 0) {
			if(0 == respCounter.incrementAndGet() % missEveryResponse) {
				return true;
			}
		}
		return false;
	}

	@Override
	public final void put(final List<I> dataItems) {
		String objNameParts[];
		ObjectContainerMock<I> container;
		for(final I object : dataItems) {
			objNameParts = object.getName().split("/");
			if(objNameParts.length == 1) {
				defaultContainer.put(objNameParts[0], object);
			} else if(objNameParts.length == 2) {
				container = getContainer(objNameParts[0]);
				if(container == null) {
					container = createContainer(objNameParts[0]);
				}
				container.put(objNameParts[1], object);
			} else if(objNameParts.length == 3) {
				if(objNameParts[0].isEmpty()) {
					container = getContainer(objNameParts[1]);
					if(container == null) {
						container = createContainer(objNameParts[1]);
					}
					container.put(objNameParts[2], object);
				}
			}
		}
	}

	@Override
	public StorageIoStats getStats() {
		return ioStats;
	}

	@SuppressWarnings({"InfiniteLoopStatement", "unchecked"})
	private void loadPersistedDataItems() {
		if(itemInputFile != null && !itemInputFile.isEmpty()) {
			final Path itemInputFile = Paths.get(this.itemInputFile);
			if(!Files.exists(itemInputFile)) {
				Loggers.ERR.warn("Item input file @ \"{}\" doesn't exists", itemInputFile);
				return;
			}
			if(Files.isDirectory(itemInputFile)) {
				Loggers.ERR.warn("Item input file @ \"{}\" is a directory", itemInputFile);
				return;
			}
			
			final LongAdder count = new LongAdder();
			List<I> buff;
			int n;
			final Thread displayProgressThread = new Thread(
				() -> {
					try {
						while(true) {
							Loggers.MSG.info("{} items loaded...", count.sum());
							TimeUnit.SECONDS.sleep(10);
						}
					} catch(final InterruptedException e) {
					}
				}
			);
			
			final ItemFactory<I> itemFactory = new BasicDataItemMockFactory<>();
			try(final CsvFileItemInput<I> csvFileItemInput = new CsvFileItemInput<>(itemInputFile, itemFactory)) {
				displayProgressThread.start();
				do {
					buff = new ArrayList<>(4096);
					n = csvFileItemInput.get(buff, 4096);
					if(n > 0) {
						put(buff);
						count.add(n);
					} else {
						break;
					}
				} while(true);
			} catch(final EOFException e) {
				Loggers.MSG.info("Loaded {} data items from file {}", count.sum(), itemInputFile);
			} catch(final IOException | NoSuchMethodException e) {
				LogUtil.exception(
					Level.WARN, e, "Failed to load the data items from file \"{}\"", itemInputFile
				);
			} finally {
				displayProgressThread.interrupt();
			}
		}
	}
	
	@Override
	protected void doClose()
	throws IOException {
		ioStats.close();
		dataInput.close();
		try {
			storageMap.clear();
		} catch(final ConcurrentModificationException e) {
			LogUtil.exception(Level.DEBUG, e, "Failed to clean up the storage mock");
		}
		try {
			for(final ObjectContainerMock<I> containerMock : storageMap.values()) {
				containerMock.close();
			}
		} catch(final ConcurrentModificationException e) {
			LogUtil.exception(Level.DEBUG, e, "Failed to clean up the containers");
		}
	}
}
