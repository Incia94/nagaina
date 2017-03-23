package com.emc.mongoose.tests.unit;

import com.emc.mongoose.common.api.SizeInBytes;
import com.emc.mongoose.common.math.Random;
import com.emc.mongoose.storage.mock.api.StorageMock;
import com.emc.mongoose.storage.mock.impl.http.StorageMockFactory;
import com.emc.mongoose.ui.config.Config;
import static com.emc.mongoose.ui.config.Config.ItemConfig;
import static com.emc.mongoose.ui.config.Config.StorageConfig;
import static com.emc.mongoose.ui.config.Config.TestConfig.StepConfig;
import com.emc.mongoose.ui.config.reader.jackson.ConfigParser;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.ui.log.Markers;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
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

/**
 Created by kurila on 22.03.17.
 */
@RunWith(Parameterized.class)
public class S3ApiTest {
	
	@BeforeClass
	public static void setUpClass()
	throws Exception {
		LogUtil.init();
	}
	
	private static final Logger LOG = LogManager.getLogger();
	private static final String BUCKET = "s3bucket";
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
	
	public S3ApiTest(final int objCount, final int objSize, final int concurrency)
	throws Exception {
		LOG.info(
			Markers.MSG, "Object count: {}, size: {}", objCount,
			SizeInBytes.formatFixedSize(objSize)
		);
		this.objCount = objCount;
		this.objSize = objSize;
		this.concurrency = concurrency;
		objIds = new ArrayList<>(objCount);
		storageMock = new StorageMockFactory(storageConfig, itemConfig, stepConfig)
			.newStorageMock();
		storageMock.start();
		
		final Random rnd = new Random();
		for(int i = 0; i < objCount; i ++) {
			objIds.add(Long.toString(Math.abs(rnd.nextLong()), Character.MAX_RADIX));
		}
		
		final HttpURLConnection conn = (HttpURLConnection) new URL(
			"http", "127.0.0.1", 9020, "/" + BUCKET
		).openConnection();
		conn.setRequestMethod("PUT");
		LOG.info(
			Markers.MSG, "Create bucket \"{}\" response code: {}", BUCKET, conn.getResponseCode()
		);
		conn.disconnect();
		
		final ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		final int objCountPerThread = objCount / concurrency;
		for(int i = 0; i < concurrency; i ++) {
			final int i_ = i;
			final Runnable task = () -> {
				int respCode;
				HttpURLConnection conn_;
				OutputStream out_;
				String objId;
				try {
					for(int j = 0; j < objCountPerThread; j ++) {
						objId = objIds.get(objCountPerThread * i_ + j);
						conn_ = (HttpURLConnection) new URL(
							"http", "127.0.0.1", 9020, "/" + BUCKET + "/" + objId
						).openConnection();
						conn_.setFixedLengthStreamingMode(objSize);
						conn_.setDoOutput(true);
						conn_.setRequestMethod("PUT");
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
						if(HttpURLConnection.HTTP_OK != respCode) {
							LOG.error(
								Markers.ERR, "Create object \"{}\" response code: {}",
								objId, respCode
							);
						}
						conn_.disconnect();
					}
				} catch(final Exception e) {
					LogUtil.exception(LOG, Level.ERROR, e, "Failure");
				}
			};
			executor.submit(task);
		}
		executor.shutdown();
		executor.awaitTermination(5, TimeUnit.MINUTES);
		if(!executor.isTerminated()) {
			LOG.warn(Markers.ERR, "Timeout");
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
							"http", "127.0.0.1", 9020, "/" + BUCKET + "/" + objId
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
			LOG.warn(Markers.ERR, "Timeout");
			executor.shutdownNow();
		}
	}
	
	@Test
	public final void testList()
	throws Exception {
		final HttpURLConnection conn = (HttpURLConnection) new URL(
			"http", "127.0.0.1", 9020, "/" + BUCKET
		).openConnection();
		final int respCode = conn.getResponseCode();
		assertEquals(
			"Bucket listing response: " + conn.getResponseMessage(),
			HttpURLConnection.HTTP_OK, respCode
		);
		final int contentLen = Integer.parseInt(
			conn.getHeaderFields().get("content-length").get(0)
		);
		assertTrue("Bucket listing response content size should be > 0", contentLen > 0);
		final byte buff[] = new byte[contentLen];
		final InputStream in = conn.getInputStream();
		int readByteCount = 0;
		int n;
		while(readByteCount < contentLen) {
			n = in.read(buff, readByteCount, contentLen - readByteCount);
			if(n < 0) {
				fail("Premature end of stream");
			} else {
				readByteCount += n;
			}
		}
	}
}
