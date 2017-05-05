package com.emc.mongoose.storage.mock.impl.base;

import com.codahale.metrics.Clock;
import com.codahale.metrics.Counter;
import com.emc.mongoose.common.Constants;
import static com.emc.mongoose.common.Constants.LOCALE_DEFAULT;
import com.emc.mongoose.model.metrics.CustomMeter;
import com.emc.mongoose.model.metrics.ResumableUserTimeClock;
import com.emc.mongoose.storage.mock.api.StorageMock;
import com.emc.mongoose.storage.mock.api.StorageIoStats;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.ui.log.Loggers;

import org.apache.logging.log4j.Level;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public final class BasicStorageIoStats
extends Thread
implements StorageIoStats {

	private final Counter countFailWrite, countFailRead, countFailDelete, countContainers;
	private final CustomMeter tpWrite, tpRead, tpDelete, bwWrite, bwRead;
	private final long updatePeriodSec;
	private final StorageMock storage;

	public BasicStorageIoStats(final StorageMock storage, final int metricsPeriodSec) {
		super(BasicStorageIoStats.class.getSimpleName());
		setDaemon(true);
		this.updatePeriodSec = metricsPeriodSec;
		this.storage = storage;
		final Clock clock = new ResumableUserTimeClock();
		countFailWrite = new Counter();
		countFailRead = new Counter();
		countFailDelete = new Counter();
		countContainers = new Counter();
		tpWrite = new CustomMeter(clock, metricsPeriodSec);
		tpRead = new CustomMeter(clock, metricsPeriodSec);
		tpDelete = new CustomMeter(clock, metricsPeriodSec);
		bwWrite = new CustomMeter(clock, metricsPeriodSec);
		bwRead = new CustomMeter(clock, metricsPeriodSec);
	}

	private static final String
		MSG_FMT_METRICS = "Capacity used: %d (%.1f%%), containers count: %d\n" +
		"\tOperation |Count       |Failed      |TP[op/s]avg |TP[op/s]last|BW[MB/s]avg |BW[MB/s]last\n" +
		"\t----------|------------|------------|------------|------------|------------|------------\n" +
		"\tWrite     |%12d|%12d|%12.3f|%12.3f|%12.3f|%12.3f\n" +
		"\tRead      |%12d|%12d|%12.3f|%12.3f|%12.3f|%12.3f\n" +
		"\tDelete    |%12d|%12d|%12.3f|%12.3f|            |";

	@Override
	public synchronized void start() {
		Loggers.MSG.debug("Start");
		super.start();
	}

	@Override
	public void run() {
		Loggers.MSG.debug("Running");
		try {
			while(updatePeriodSec > 0 && !isInterrupted()) {
				Loggers.MSG.info(toString());
				TimeUnit.SECONDS.sleep(updatePeriodSec);
			}
		} catch(final InterruptedException ignored) {
			Loggers.MSG.debug("Interrupted");
		} catch(final Exception e) {
			LogUtil.exception(Level.WARN, e, "Failure");
		}
	}

	@Override
	public void markWrite(final boolean success, final long size) {
		if(success) {
			tpWrite.mark();
			bwWrite.mark(size);
		} else {
			countFailWrite.inc();
		}
	}

	@Override
	public void markRead(final boolean success, final long size) {
		if(success) {
			tpRead.mark();
			bwRead.mark(size);
		} else {
			countFailRead.inc();
		}
	}

	@Override
	public void markDelete(final boolean success) {
		if(success) {
			tpDelete.mark();
		} else {
			countFailDelete.inc();
		}
	}

	@Override
	public void containerCreate() {
		countContainers.inc();
	}

	@Override
	public void containerDelete() {
		countContainers.dec();
	}

	@Override
	public double getWriteRate() {
		return tpWrite.getLastRate();
	}

	@Override
	public double getWriteRateBytes() {
		return bwWrite.getLastRate();
	}

	@Override
	public double getReadRate() {
		return tpRead.getLastRate();
	}

	@Override
	public double getReadRateBytes() {
		return bwRead.getLastRate();
	}

	@Override
	public double getDeleteRate() {
		return tpDelete.getLastRate();
	}

	@Override
	public void close()
	throws IOException {
		if(!isInterrupted()) {
			interrupt();
		}
	}

	@Override
	public final String toString() {
		long countTotal = storage.getSize();
		return String.format(
			LOCALE_DEFAULT, MSG_FMT_METRICS,
			//
			countTotal, 100.0 * countTotal / storage.getCapacity(), countContainers.getCount(),
			//
			tpWrite.getCount(), countFailWrite.getCount(),
			tpWrite.getMeanRate(), tpWrite.getLastRate(),
			bwWrite.getMeanRate() / Constants.MIB, bwWrite.getLastRate() / Constants.MIB,
			//
			tpRead.getCount(), countFailRead.getCount(),
			tpRead.getMeanRate(), tpRead.getLastRate(),
			bwRead.getMeanRate() / Constants.MIB, bwRead.getLastRate() / Constants.MIB,
			//
			tpDelete.getCount(), countFailDelete.getCount(),
			tpDelete.getMeanRate(), tpDelete.getLastRate()
		);
	}
}
