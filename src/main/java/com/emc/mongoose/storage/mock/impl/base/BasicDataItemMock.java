package com.emc.mongoose.storage.mock.impl.base;

import com.emc.mongoose.model.item.BasicDataItem;
import com.emc.mongoose.storage.mock.api.DataItemMock;
import com.emc.mongoose.ui.log.Loggers;

import static com.emc.mongoose.model.item.DataItem.getRangeCount;

public class BasicDataItemMock
extends BasicDataItem
implements DataItemMock {
	//
	public BasicDataItemMock() {
		super();
	}
	//
	public BasicDataItemMock(final String value) {
		super(value);
	}
	//
	public BasicDataItemMock(final long offset, final long size) {
		super(offset, size);
	}
	//
	public BasicDataItemMock(final String name, final long offset, final long size) {
		super(name, offset, size);
	}
	//
	public BasicDataItemMock(final String name, final long offset, final long size, final int layerNum) {
		super(name, offset, size, layerNum);
	}
	//
	public final synchronized void update(final long offset, final long size)
	throws IllegalArgumentException, IllegalStateException {
		if(size < 0) {
			throw new IllegalArgumentException("Range size should not be negative");
		}
		final int
			countRangesTotal = getRangeCount(this.size),
			maskIndexStart = getRangeCount(offset),
			maskIndexEnd = getRangeCount(offset + size);
		for(int i = maskIndexStart; i < maskIndexEnd; i ++) {
			if(countRangesTotal > 0 && countRangesTotal == modifiedRangesMask.cardinality()) {
				// mask is full, switch to the next layer
				layerNum ++;
				modifiedRangesMask.clear();
			}
			if(modifiedRangesMask.get(i)) {
				throw new IllegalStateException(
					"Range " + i + " is already updated, but mask is: " +
					modifiedRangesMask.toString()
				);
			} else {
				modifiedRangesMask.set(i);
			}
		}
		if(Loggers.MSG.isTraceEnabled()) {
			Loggers.MSG.trace(
				"{}: byte range {}-{} updated, mask range {}-{} is set",
				name, offset, offset + size, maskIndexStart, maskIndexEnd
			);
		}
	}
	//
	public final synchronized void append(final long size) {
		if(size < 0) {
			throw new IllegalArgumentException(name + ": range size should not be negative");
		}
		final int
			lastCellPos = this.size > 0 ? getRangeCount(this.size) - 1 : 0,
			nextCellPos = getRangeCount(this.size + size);
		if(lastCellPos < nextCellPos && modifiedRangesMask.get(lastCellPos)) {
			modifiedRangesMask.set(lastCellPos, nextCellPos);
		}
		this.size += size;
	}
}
