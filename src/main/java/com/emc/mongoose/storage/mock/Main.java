package com.emc.mongoose.storage.mock;

import com.emc.mongoose.common.concurrent.Daemon;
import com.emc.mongoose.storage.mock.impl.http.StorageMockFactory;
import com.emc.mongoose.ui.cli.CliArgParser;
import com.emc.mongoose.ui.config.Config;
import static com.emc.mongoose.ui.config.Config.ItemConfig;
import static com.emc.mongoose.ui.config.Config.StorageConfig;
import static com.emc.mongoose.ui.config.Config.TestConfig.StepConfig;
import com.emc.mongoose.ui.config.reader.jackson.ConfigParser;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.ui.log.Loggers;
import static com.emc.mongoose.common.Constants.KEY_STEP_NAME;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;

import java.io.IOException;

/**
 Created on 12.07.16.
 */
public class Main {

	static {
		LogUtil.init();
	}

	public static void main(final String[] args)
	throws IOException {
		
		final Config config = ConfigParser.loadDefaultConfig();
		if(config == null) {
			throw new AssertionError();
		}
		config.apply(CliArgParser.parseArgs(config.getAliasingConfig(), args));

		final StepConfig stepConfig = config.getTestConfig().getStepConfig();
		String jobName = stepConfig.getName();
		if(jobName == null) {
			jobName = ThreadContext.get(KEY_STEP_NAME);
			stepConfig.setName(jobName);
		} else {
			ThreadContext.put(KEY_STEP_NAME, jobName);
		}
		if(jobName == null) {
			throw new AssertionError("Load job name is not set");
		}
		
		Loggers.MSG.info("Configuration loaded");
		
		final StorageConfig storageConfig = config.getStorageConfig();
		final ItemConfig itemConfig = config.getItemConfig();
		final StorageMockFactory storageMockFactory = new StorageMockFactory(
			storageConfig, itemConfig, stepConfig
		);
		if(storageConfig.getMockConfig().getNode()) {
			try(final Daemon storageNodeMock = storageMockFactory.newStorageNodeMock()) {
				storageNodeMock.start();
				try {
					storageNodeMock.await();
				} catch(final InterruptedException ignored) {
				}
			} catch(final Exception e) {
				LogUtil.exception(Level.ERROR, e, "Failed to run storage node mock");
			}
		} else {
			try(final Daemon storageMock = storageMockFactory.newStorageMock()) {
				storageMock.start();
				try {
					storageMock.await();
				} catch(final InterruptedException ignored) {
				}
			} catch(final Exception e) {
				LogUtil.exception(Level.ERROR, e, "Failed to run storage mock");
			}
		}
	}

}
