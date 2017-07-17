package com.emc.mongoose.storage.mock.api;

import com.emc.mongoose.api.model.item.DataItem;

/**
 Created on 19.07.16.
 */
public interface DataItemMock
extends DataItem {
	
	/**
	 Marks the range as updated only if its boundaries are fit the masked cell
	 @param offset
	 @param size
	 */
	void update(final long offset, final long size);

	void append(final long size);

}
