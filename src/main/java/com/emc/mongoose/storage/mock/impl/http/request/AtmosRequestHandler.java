package com.emc.mongoose.storage.mock.impl.http.request;

import com.emc.mongoose.storage.mock.api.DataItemMock;
import com.emc.mongoose.storage.mock.api.StorageMock;
import com.emc.mongoose.storage.mock.api.StorageMockClient;
import com.emc.mongoose.storage.mock.api.exception.ContainerMockException;
import com.emc.mongoose.storage.mock.api.exception.ContainerMockNotFoundException;
import com.emc.mongoose.ui.config.Config.ItemConfig.NamingConfig;
import com.emc.mongoose.ui.config.Config.TestConfig.StepConfig.LimitConfig;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.ui.log.Markers;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.util.Attribute;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import java.io.ByteArrayOutputStream;
import java.rmi.RemoteException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.emc.mongoose.storage.mock.impl.http.request.XmlShortcuts.appendElement;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.LOCATION;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpMethod.PUT;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_IMPLEMENTED;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 Created on 12.07.16.
 */
public class AtmosRequestHandler<T extends DataItemMock>
extends RequestHandlerBase<T> {

	private static final Logger LOG = LogManager.getLogger();
	private static final String
			URI_BASE_PATH = "/rest",
			OBJ_PATH = URI_BASE_PATH + "/objects",
			NS_PATH = URI_BASE_PATH + "/namespace",
			AT_PATH = URI_BASE_PATH + "/accesstokens",
			ST_PATH = URI_BASE_PATH + "/subtenant",
			STS_PATH = ST_PATH + "s/";
	private static final DocumentBuilder DOM_BUILDER;
	private static final TransformerFactory TRANSFORMER_FACTORY = TransformerFactory.newInstance();

	static {
		try {
			DOM_BUILDER = DocumentBuilderFactory.newInstance().newDocumentBuilder();
		} catch (final ParserConfigurationException e) {
			throw new RuntimeException(e);
		}
	}

	public AtmosRequestHandler(
		final LimitConfig limitConfig, final NamingConfig namingConfig,
		final StorageMock<T> localStorage, final StorageMockClient<T> remoteStorage
	) throws RemoteException {
		super(limitConfig, namingConfig, localStorage, remoteStorage);
	}

	@Override
	protected boolean checkApiMatch(final HttpRequest request) {
		return request.uri().startsWith(URI_BASE_PATH);
	}

	private String processMetaDataList(final String[] metaDataList, final String key) {
		if(metaDataList != null) {
			String entry[];
			for(final String metaData : metaDataList) {
				entry = metaData.split("=");
				if(entry.length == 2 && entry[0].equals(key)) {
					return entry[1];
				}
			}
		}
		return null;
	}

	private static final String KEY_EMC_TAGS = "x-emc-tags";
	private static final String KEY_EMC_TOKEN = "x-emc-token";

	@Override
	protected void doHandle(
		final String uriPath, final Map<String, String> queryParams, final HttpMethod method,
		final long size, final ChannelHandlerContext ctx
	) {
		final Channel channel = ctx.channel();
		FullHttpResponse response = null;
		final HttpRequest request = channel.attr(ATTR_KEY_REQUEST).get();
		final HttpHeaders requestHeaders = request.headers();
		channel.attr(ATTR_KEY_CTX_WRITE_FLAG).set(true);
		if(uriPath.startsWith(OBJ_PATH)) {
			final String uriPathParts[] = uriPath.split("/");
			String objectId = uriPathParts.length > 3 ? uriPathParts[3] : null;
			if(objectId == null) {
				if(method.equals(POST)) {
					objectId = generateHexId(22);
					handleObjectRequest(
						method, StorageMock.DEFAULT_CONTAINER_NAME, objectId, 0, size, ctx
					);
					final Attribute<HttpResponseStatus>
						statusAttribute = channel.attr(ATTR_KEY_RESPONSE_STATUS);
					response = newEmptyResponse(statusAttribute.get());
					final int statusCode = response.status().code();
					if(statusCode < 300 && 200 <= statusCode) {
						response.headers().set(LOCATION, OBJ_PATH + "/" + objectId);
					}
				} else if(method.equals(GET)) {
					if(requestHeaders.contains(KEY_EMC_TOKEN)) {
						objectId = requestHeaders.get(KEY_EMC_TOKEN);
					}
					handleContainerList(StorageMock.DEFAULT_CONTAINER_NAME, objectId, ctx);
				}
			} else {
				handleObjectRequest(
					method, StorageMock.DEFAULT_CONTAINER_NAME, objectId, 0, size, ctx
				);
			}
		} else if(uriPath.startsWith(NS_PATH)) {
			final String uriPathParts[] = uriPath.split("/");
			final String containerName = uriPathParts.length > 3 ? uriPathParts[3] : null;
			final String objectId = uriPathParts.length > 4 ?
				uriPathParts[uriPathParts.length - 1] : null;
			handleItemRequest(method, queryParams, containerName, objectId, size, ctx);
		} else if(uriPath.startsWith(AT_PATH)) {
			setHttpResponseStatusInContext(ctx, NOT_IMPLEMENTED);
		} else if(uriPath.startsWith(ST_PATH)) {
			final String subtenantName;
			if(method.equals(PUT)) {
				subtenantName = generateHexId(0x10);
			} else {
				subtenantName = getSubtenant(requestHeaders, uriPath);
			}
			response = newEmptyResponse();
			handleContainerRequest(method, subtenantName, queryParams, ctx);
		} else {
			setHttpResponseStatusInContext(ctx, BAD_REQUEST);
		}
		if(channel.attr(ATTR_KEY_CTX_WRITE_FLAG).get()) {
			if(!localStorage.missResponse()) {
				if(response == null) {
					writeEmptyResponse(ctx);
				} else {
					writeResponse(ctx, response);
				}
			}
		}
	}
	
	private static final String KEY_EMC_UID = "x-emc-uid";
	private static final String UID_DELIMITER = "/";
	private static final String KEY_SUBTENANT_ID = "subtenantID";

	private static String getSubtenant(final HttpHeaders headers, final String uri) {
		if(uri.startsWith(STS_PATH) && uri.length() > STS_PATH.length()) {
			return uri.substring(STS_PATH.length());
		}
		if(headers.contains(KEY_EMC_UID)) {
			String uid = headers.get(KEY_EMC_UID);
			if(uid.contains(UID_DELIMITER)) {
				return uid.split(UID_DELIMITER)[0];
			}
		} else {
			LOG.debug(Markers.MSG, "The header " + KEY_EMC_UID + " is undefined" );
		}
		if(headers.contains(KEY_SUBTENANT_ID)) {
			return headers.get(KEY_SUBTENANT_ID);
		}
		return null;
	}

	@Override
	protected void handleContainerList(
		final String name, final Map<String, String> queryParams, final ChannelHandlerContext ctx
	) {
		// TODO
	}

	private static final String KEY_EMC_LIMIT = "x-emc-limit";

	private void handleContainerList(
			final String name,
			final String objectId,
			final ChannelHandlerContext ctx
	) {
		int maxCount = DEFAULT_PAGE_SIZE;
		final HttpHeaders headers = ctx.channel().attr(ATTR_KEY_REQUEST).get().headers();
		if(headers.contains(KEY_EMC_LIMIT)) {
			try {
				maxCount = Integer.parseInt(headers.get(KEY_EMC_LIMIT));
			} catch (final NumberFormatException e) {
				LOG.warn(
						Markers.ERR, "Limit header value is not a valid integer: {}",
						headers.get(KEY_EMC_LIMIT)
				);
			}
		}

		final List<T> buffer = new ArrayList<>(maxCount);
		T lastObject = null;
		try {
			lastObject = listContainer(name, objectId, buffer, maxCount);
			if(LOG.isTraceEnabled(Markers.MSG)) {
				LOG.trace(
					Markers.MSG,
					"Subtenant \"{}\": generated list of {} objects, last one is \"{}\"",
					name, buffer.size(), lastObject
				);
			}
		} catch(final ContainerMockNotFoundException e) {
			setHttpResponseStatusInContext(ctx, NOT_FOUND);
			return;
		} catch(final ContainerMockException e) {
			setHttpResponseStatusInContext(ctx, INTERNAL_SERVER_ERROR);
			LogUtil.exception(LOG, Level.WARN, e, "Subtenant \"{}\" failure", name);
			return;
		}
		Map.Entry<String, String> header = null;
		if(lastObject != null) {
			header = new AbstractMap.SimpleEntry<>(KEY_EMC_TOKEN, lastObject.getName());
		}
		final Document xml = DOM_BUILDER.newDocument();
		final Element rootElem = xml.createElement("ListObjectsResponse");
		xml.appendChild(rootElem);
		for(final T object: buffer) {
			final Element elem = xml.createElement("Object");
			appendElement(xml, elem, "ObjectID", object.getName());
			appendElement(rootElem, elem);
		}
		final ByteArrayOutputStream stream = new ByteArrayOutputStream();
		final StreamResult streamResult = new StreamResult(stream);
		try {
			TRANSFORMER_FACTORY.newTransformer().transform(new DOMSource(xml), streamResult);
		} catch (final TransformerException e) {
			setHttpResponseStatusInContext(ctx, INTERNAL_SERVER_ERROR);
			LogUtil.exception(LOG, Level.ERROR, e, "Failed to build subtenant XML listing");
			return;
		}
		if(LOG.isTraceEnabled(Markers.MSG)) {
			LOG.trace(
				Markers.MSG, "Responding the subtenant \"{}\" listing content:\n{}",
				name, new String(stream.toByteArray())
			);
		}
		final byte[] content = stream.toByteArray();
		ctx.channel().attr(ATTR_KEY_CTX_WRITE_FLAG).set(false);
		final FullHttpResponse response = new DefaultFullHttpResponse(
			HTTP_1_1, OK, Unpooled.copiedBuffer(content)
		);
		response.headers().set(CONTENT_TYPE, "application/xml");
		if(header != null) {
			response.headers().set(header.getKey(), header.getValue());
		}
		HttpUtil.setContentLength(response, content.length);
		ctx.write(response);
	}
}
