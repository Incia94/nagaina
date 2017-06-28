package com.emc.mongoose.tests.unit;

import com.emc.mongoose.common.api.SizeInBytes;
import com.emc.mongoose.common.math.Random;
import com.emc.mongoose.model.data.ContentSource;
import com.emc.mongoose.model.data.ContentSourceUtil;
import com.emc.mongoose.storage.mock.api.StorageMock;
import com.emc.mongoose.storage.mock.impl.http.StorageMockFactory;
import com.emc.mongoose.ui.config.Config;
import com.emc.mongoose.ui.config.reader.jackson.ConfigParser;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.ui.log.Loggers;
import org.apache.logging.log4j.Level;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.emc.mongoose.ui.config.Config.ItemConfig;
import static com.emc.mongoose.ui.config.Config.StorageConfig;
import static com.emc.mongoose.ui.config.Config.TestConfig.StepConfig;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 Created by kurila on 22.03.17.
 */
@RunWith(Parameterized.class)
public class AtmosApiTest {
	
	@BeforeClass
	public static void setUpClass()
	throws Exception {
		LogUtil.init();
	}
	
	private static final Config config;
	static {
		try {
			config = ConfigParser.loadDefaultConfig();
		} catch(final IOException e) {
			throw new RuntimeException(e);
		}
	}
	private static final StorageConfig storageConfig = config.getStorageConfig();
	private static final ItemConfig itemConfig = config.getItemConfig();
	private static final StepConfig stepConfig = config.getTestConfig().getStepConfig();
	private static final byte SAMPLE_DATA[] = new byte[1048576];
	static {
		final ByteBuffer bb = ByteBuffer.wrap(SAMPLE_DATA);
		final LongBuffer lb = bb.asLongBuffer();
		final Random rnd = new Random();
		for(int i = 0; i < SAMPLE_DATA.length / Long.BYTES; i ++) {
			lb.put(i, rnd.nextLong());
		}
	}
	
	private final StorageMock storageMock;
	private final int objCount;
	private final int objSize;
	private final int concurrency;
	private final List<String> objIds;
	
	public AtmosApiTest(final int objCount, final int objSize, final int concurrency)
	throws Exception {
		Loggers.MSG.info("Object count: {}, size: {}", objCount, SizeInBytes.formatFixedSize(objSize));
		this.objCount = objCount;
		this.objSize = objSize;
		this.concurrency = concurrency;
		objIds = new ArrayList<>(objCount);

		final StorageConfig.MockConfig mockConfig = storageConfig.getMockConfig();
		final StorageConfig.MockConfig.ContainerConfig containerConfig = mockConfig.getContainerConfig();
		final StorageConfig.MockConfig.FailConfig failConfig = mockConfig.getFailConfig();
		final StorageConfig.NetConfig netConfig = storageConfig.getNetConfig();
		final ItemConfig.NamingConfig namingConfig = itemConfig.getNamingConfig();
		final ItemConfig.DataConfig.ContentConfig contentConfig = itemConfig.getDataConfig().getContentConfig();
		final ContentSource contentSrc = ContentSourceUtil.getInstance(
			contentConfig.getFile(), contentConfig.getSeed(), contentConfig.getRingConfig().getSize(),
			contentConfig.getRingConfig().getCache()
		);

		storageMock = new StorageMockFactory(
			itemConfig.getInputConfig().getFile(), mockConfig.getCapacity(), containerConfig.getCapacity(),
			containerConfig.getCountLimit(), (int) stepConfig.getMetricsConfig().getPeriod(), failConfig.getConnections(),
			failConfig.getResponses(), contentSrc, netConfig.getNodeConfig().getPort(), netConfig.getSsl(),
			(float) stepConfig.getLimitConfig().getRate(), namingConfig.getPrefix(), namingConfig.getRadix()
		).newStorageMock();

		storageMock.start();
		
		final Random rnd = new Random();
		for(int i = 0; i < objCount; i ++) {
			objIds.add(Long.toString(Math.abs(rnd.nextLong()), Character.MAX_RADIX));
		}
		
		final ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		final int objCountPerThread = objCount / concurrency;
		for(int i = 0; i < concurrency; i ++) {
			final int i_ = i;
			final Runnable task = () -> {
				int respCode;
				HttpURLConnection conn_;
				OutputStream out_;
				String loc;
				try {
					for(int j = 0; j < objCountPerThread; j ++) {
						conn_ = (HttpURLConnection) new URL(
							"http", "127.0.0.1", 9020, "/rest/objects"
						).openConnection();
						conn_.setFixedLengthStreamingMode(objSize);
						conn_.setDoOutput(true);
						conn_.setRequestMethod("POST");
						out_ = conn_.getOutputStream();
						int n = objSize / SAMPLE_DATA.length;
						for(int k = 0; k < n; k ++) {
							out_.write(SAMPLE_DATA);
						}
						n = objSize % SAMPLE_DATA.length;
						out_.write(SAMPLE_DATA, 0, n);
						out_.flush();
						out_.close();
						respCode = conn_.getResponseCode();
						loc = conn_.getHeaderField("location");
						objIds.set(objCountPerThread * i_ + j, loc.substring(loc.lastIndexOf('/') + 1));
						if(HttpURLConnection.HTTP_OK != respCode) {
							Loggers.ERR.error("Create object \"{}\" response code: {}", loc, respCode);
						}
						conn_.disconnect();
					}
				} catch(final Exception e) {
					LogUtil.exception(Level.ERROR, e, "Failure");
				}
			};
			executor.submit(task);
		}
		executor.shutdown();
		executor.awaitTermination(5, TimeUnit.MINUTES);
		if(!executor.isTerminated()) {
			Loggers.ERR.warn("Timeout");
			executor.shutdownNow();
		}
	}
	
	@After
	public void tearDown()
	throws Exception {
		objIds.clear();
		storageMock.close();
	}
	
	@Parameterized.Parameters
	public static Collection<Object[]> generateData() {
		return Arrays.asList(
			new Object[][] {
				{ 1000000, 0, 200 },
				{ 1000000, (int) SizeInBytes.toFixedSize("1KB"), 100 },
				{ 10000, (int) SizeInBytes.toFixedSize("1MB"), 50 },
				{ 100, (int) SizeInBytes.toFixedSize("1GB"), 20 }
			}
		);
	}
	
	@Test
	public final void testRead()
	throws Exception {
		
		final ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		final int objCountPerThread = objCount / concurrency;
		for(int i = 0; i < concurrency; i ++) {
			final int i_ = i;
			final Runnable task = () -> {
		
				final byte buff[] = new byte[1048576];
				
				HttpURLConnection conn;
				InputStream in;
				int respCode;
				int contentLen;
				int n;
				String objId;
				
				try {
					for(int j = 0; j < objCountPerThread; j ++) {
						objId = objIds.get(objCountPerThread * i_ + j);
						conn = (HttpURLConnection) new URL(
							"http", "127.0.0.1", 9020, "/rest/objects/" + objId
						).openConnection();
						respCode = conn.getResponseCode();
						assertEquals(
							objId + ": " + conn.getResponseMessage(), HttpURLConnection.HTTP_OK,
							respCode
						);
						contentLen = Integer.parseInt(
							conn.getHeaderFields().get("content-length").get(0)
						);
						assertEquals(objSize, contentLen);
						in = conn.getInputStream();
						contentLen = 0;
						while(contentLen < objSize) {
							n = in.read(buff, contentLen, objSize - contentLen);
							if(n < 0) {
								fail("Premature end of stream");
							} else {
								contentLen += n;
							}
						}
						assertEquals(objSize, contentLen);
						in.close();
						conn.disconnect();
					}
				} catch(final Exception e) {
					fail(e.toString());
				}
			};
			executor.submit(task);
		}
		executor.shutdown();
		executor.awaitTermination(5, TimeUnit.MINUTES);
		if(!executor.isTerminated()) {
			Loggers.ERR.warn("Timeout");
			executor.shutdownNow();
		}
	}
}
