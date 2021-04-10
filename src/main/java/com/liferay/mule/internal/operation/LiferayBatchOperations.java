/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.mule.internal.operation;

import com.fasterxml.jackson.databind.JsonNode;

import com.liferay.mule.api.BatchExportContentType;
import com.liferay.mule.internal.connection.LiferayConnection;
import com.liferay.mule.internal.error.LiferayError;
import com.liferay.mule.internal.error.LiferayResponseValidator;
import com.liferay.mule.internal.error.provider.LiferayResponseErrorProvider;
import com.liferay.mule.internal.util.JsonNodeReader;

import java.io.IOException;
import java.io.InputStream;

import java.nio.file.Files;
import java.nio.file.Paths;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.mule.runtime.api.util.MultiMap;
import org.mule.runtime.extension.api.annotation.error.Throws;
import org.mule.runtime.extension.api.annotation.param.ConfigOverride;
import org.mule.runtime.extension.api.annotation.param.Connection;
import org.mule.runtime.extension.api.annotation.param.MediaType;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Placement;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.runtime.extension.api.exception.ModuleException;
import org.mule.runtime.extension.api.runtime.operation.Result;
import org.mule.runtime.http.api.domain.entity.HttpEntity;
import org.mule.runtime.http.api.domain.message.response.HttpResponse;

/**
 * @author Matija Petanjek
 */
@Throws(LiferayResponseErrorProvider.class)
public class LiferayBatchOperations {

	@DisplayName("Batch - Import records - Create")
	@MediaType(MediaType.APPLICATION_JSON)
	public Result<String, Void> executeCreateImportTask() {
		return null;
	}

	@DisplayName("Batch - Import records - Delete")
	@MediaType(MediaType.APPLICATION_JSON)
	public Result<String, Void> executeDeleteImportTask() {
		return null;
	}

	@DisplayName("Batch - Export records")
	@MediaType(MediaType.APPLICATION_OCTET_STREAM)
	public void executeExportTask(
			@Connection LiferayConnection connection, String className,
			BatchExportContentType batchExportContentType, String directoryPath,
			String siteId, @Optional String fieldNames,
			@ConfigOverride @DisplayName("Connection Timeout") @Optional
			@Placement(order = 1, tab = Placement.ADVANCED_TAB)
			@Summary("Socket connection timeout value")
				int connectionTimeout,
			@ConfigOverride @DisplayName("Connection Timeout Unit") @Optional
			@Placement(order = 2, tab = Placement.ADVANCED_TAB)
			@Summary("Time unit to be used in the timeout configurations")
				TimeUnit connectionTimeoutTimeUnit)
		throws InterruptedException, IOException {

		long connectionTimeoutMillis = connectionTimeoutTimeUnit.toMillis(
			connectionTimeout);

		String exportTaskId = _submitExportTask(
			batchExportContentType, className, connection, fieldNames, siteId,
			connectionTimeoutMillis);

		while (true) {
			String exportTaskStatus = _getExportTaskStatus(
				connection, exportTaskId, connectionTimeoutMillis);

			if (exportTaskStatus.equalsIgnoreCase("completed")) {
				break;
			}
			else if (exportTaskStatus.equalsIgnoreCase("failed")) {
				throw new ModuleException(
					"Batch export failed", LiferayError.BATCH_EXPORT_FAILED);
			}

			Thread.sleep(1000);
		}

		Files.copy(
			_getExportTaskContentInputStream(
				connection, exportTaskId, connectionTimeoutMillis),
			Paths.get(directoryPath + "/export.zip"));
	}

	@DisplayName("Batch - Import records - Update")
	@MediaType(MediaType.APPLICATION_JSON)
	public Result<String, Void> executeUpdateImportTask() {
		return null;
	}

	private InputStream _getExportTaskContentInputStream(
		LiferayConnection connection, String exportTaskId,
		long connectionTimeout) {

		Map<String, String> pathParams = new HashMap<>();

		pathParams.put("exportTaskId", exportTaskId);

		HttpResponse httpResponse = connection.get(
			pathParams, new MultiMap<>(), _GET_EXPORT_TASK_CONTENT_ENDPOINT,
			connectionTimeout, BATCH_JAX_RS_APP_BASE);

		liferayResponseValidator.validate(httpResponse);

		HttpEntity httpEntity = httpResponse.getEntity();

		return httpEntity.getContent();
	}

	private String _getExportTaskStatus(
		LiferayConnection connection, String exportTaskId,
		long connectionTimeout) {

		Map<String, String> pathParams = new HashMap<>();

		pathParams.put("exportTaskId", exportTaskId);

		HttpResponse httpResponse = connection.get(
			pathParams, new MultiMap<>(), _GET_EXPORT_TASK_ENDPOINT,
			connectionTimeout, BATCH_JAX_RS_APP_BASE);

		liferayResponseValidator.validate(httpResponse);

		JsonNode payloadJsonNode = jsonNodeReader.fromHttpResponse(
			httpResponse);

		JsonNode exportTaskStatusJsonNode = payloadJsonNode.get(
			"executeStatus");

		return exportTaskStatusJsonNode.textValue();
	}

	private String _submitExportTask(
		BatchExportContentType batchExportContentType, String className,
		LiferayConnection connection, String fieldNames, String siteId,
		long connectionTimeout) {

		Map<String, String> pathParams = new HashMap<>();

		pathParams.put("className", className);
		pathParams.put("contentType", batchExportContentType.toString());

		MultiMap<String, String> queryParams = new MultiMap<>();

		queryParams.put("siteId", siteId);

		if (fieldNames != null) {
			queryParams.put("fieldNames", fieldNames);
		}

		HttpResponse httpResponse = connection.post(
			null, pathParams, queryParams, _SUBMIT_EXPORT_TASK_ENDPOINT,
			connectionTimeout, BATCH_JAX_RS_APP_BASE);

		JsonNode payloadJsonNode = jsonNodeReader.fromHttpResponse(
			httpResponse);

		JsonNode idJsonNode = payloadJsonNode.get("id");

		return String.valueOf(idJsonNode.longValue());
	}

	private static final String _GET_EXPORT_TASK_CONTENT_ENDPOINT =
		"/v1.0/export-task/{exportTaskId}/content";

	private static final String _GET_EXPORT_TASK_ENDPOINT =
		"/v1.0/export-task/{exportTaskId}";

	private static final String _SUBMIT_EXPORT_TASK_ENDPOINT =
		"/v1.0/export-task/{className}/{contentType}";

	private static final String BATCH_JAX_RS_APP_BASE =
		"/headless-batch-engine";

	private final JsonNodeReader jsonNodeReader = new JsonNodeReader();
	private final LiferayResponseValidator liferayResponseValidator =
		new LiferayResponseValidator();

}