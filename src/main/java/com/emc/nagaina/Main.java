package com.emc.nagaina;

import com.emc.mongoose.api.model.concurrent.Daemon;
import com.emc.mongoose.api.model.data.DataInput;
import com.emc.mongoose.ui.cli.CliArgParser;
import static com.emc.mongoose.api.common.Constants.KEY_TEST_STEP_ID;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.ui.log.Loggers;

import com.emc.nagaina.impl.http.StorageMockFactory;
import com.emc.nagaina.ui.config.Config;
import static com.emc.nagaina.ui.config.Config.ItemConfig;
import static com.emc.nagaina.ui.config.Config.StorageConfig;
import static com.emc.nagaina.ui.config.Config.TestConfig.StepConfig;
import com.emc.nagaina.ui.config.Config.ItemConfig.DataConfig.ContentConfig;
import com.emc.nagaina.ui.config.Config.ItemConfig.NamingConfig;
import com.emc.nagaina.ui.config.Config.StorageConfig.MockConfig;
import com.emc.nagaina.ui.config.Config.StorageConfig.MockConfig.ContainerConfig;
import com.emc.nagaina.ui.config.Config.StorageConfig.MockConfig.FailConfig;
import com.emc.nagaina.ui.config.Config.StorageConfig.NetConfig;
import com.emc.nagaina.ui.config.reader.jackson.ConfigParser;

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
			jobName = ThreadContext.get(KEY_TEST_STEP_ID);
			if(jobName == null) {
				jobName = "nagaina-" + LogUtil.getDateTimeStamp();
			}
			stepConfig.setName(jobName);
		} else {
			ThreadContext.put(KEY_TEST_STEP_ID, jobName);
		}
		if(jobName == null) {
			throw new AssertionError("Load job name is not set");
		}
		
		Loggers.MSG.info("Configuration loaded");

		final StorageConfig storageConfig = config.getStorageConfig();
		final MockConfig mockConfig = storageConfig.getMockConfig();
		final NetConfig netConfig = storageConfig.getNetConfig();
		final ContainerConfig containerConfig = mockConfig.getContainerConfig();
		final FailConfig failConfig = mockConfig.getFailConfig();
		final ItemConfig itemConfig = config.getItemConfig();
		final NamingConfig namingConfig = itemConfig.getNamingConfig();
		final ContentConfig contentConfig = itemConfig.getDataConfig().getContentConfig();
		final DataInput contentSrc = DataInput.getInstance(
			contentConfig.getFile(), contentConfig.getSeed(), contentConfig.getRingConfig().getSize(),
			contentConfig.getRingConfig().getCache()
		);

		final StorageMockFactory storageMockFactory = new StorageMockFactory(
			itemConfig.getInputConfig().getFile(), mockConfig.getCapacity(), containerConfig.getCapacity(),
			containerConfig.getCountLimit(), (int) stepConfig.getMetricsConfig().getPeriod(), failConfig.getConnections(),
			failConfig.getResponses(), contentSrc, netConfig.getNodeConfig().getPort(), netConfig.getSsl(),
			(float) stepConfig.getLimitConfig().getRate(), namingConfig.getPrefix(), namingConfig.getRadix()
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
