/*
 * Copyright 2012 Rewardly Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.streak.logging.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import nl.q42.bigquerylogging.LoggingFieldExporterSet;

import com.google.api.client.googleapis.extensions.appengine.auth.oauth2.AppIdentityCredential;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.appengine.api.files.FileService;
import com.google.appengine.api.files.FileServiceFactory;
import com.google.appengine.api.log.LogService.LogLevel;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TaskOptions.Builder;
import com.google.appengine.api.taskqueue.TaskOptions.Method;

@SuppressWarnings("serial")
public class LogExportCronTask extends HttpServlet {
	private static Logger log = Logger.getLogger(LogExportCronTask.class.getName());
	
	private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
	public void doGet(HttpServletRequest req, HttpServletResponse resp) {
		resp.setContentType("text/plain");
		
		String msPerTableStr = req.getParameter(AnalysisConstants.MS_PER_TABLE_PARAM);
		long msPerTable = 1000 * 60 * 60 * 24;
		if (AnalysisUtility.areParametersValid(msPerTableStr)) {
			msPerTable = Long.parseLong(msPerTableStr);
		}
		
		String msPerFileStr = req.getParameter(AnalysisConstants.MS_PER_FILE_PARAM);
		long msPerFile = 1000 * 60 * 2;
		if (AnalysisUtility.areParametersValid(msPerFileStr)) {
			msPerFile = Long.parseLong(msPerFileStr);
		}

		if (msPerTable % msPerFile != 0) {
			throw new InvalidTaskParameterException("The " + AnalysisConstants.MS_PER_FILE_PARAM + " parameter must divide the " + AnalysisConstants.MS_PER_TABLE_PARAM + " parameter.");
		}
		
		String endMsStr = req.getParameter(AnalysisConstants.END_MS_PARAM);
		long endMs = System.currentTimeMillis();
		if (AnalysisUtility.areParametersValid(endMsStr)) {
			endMs = Long.parseLong(endMsStr);
		}

		// By default look back a ways, but safely under the limit of 1000 files
		// per listing that Cloud Storage imposes
		String startMsStr = req.getParameter(AnalysisConstants.START_MS_PARAM);
		// For testing
		long startMs = endMs - msPerFile * 10;
		if (AnalysisUtility.areParametersValid(startMsStr)) {
			startMs = Long.parseLong(startMsStr);
		}
		
		String logLevel = req.getParameter(AnalysisConstants.LOG_LEVEL_PARAM);
		if (!AnalysisUtility.areParametersValid(logLevel)) {
			logLevel = getDefaultLogLevel();
		}
		
		String deleteFromCloudStorage = req.getParameter(AnalysisConstants.DELETE_FROM_CLOUD_STORAGE_PARAM);
		
		// Verify that log level is one of the enum values or ALL
	    if (!"ALL".equals(logLevel)) {
			  LogLevel.valueOf(logLevel);
	    }
		
		String bucketName = req.getParameter(AnalysisConstants.BUCKET_NAME_PARAM);
		if (!AnalysisUtility.areParametersValid(bucketName)) {
			bucketName = getDefaultBucketName();
		}
		
		String bigqueryProjectId = req.getParameter(AnalysisConstants.BIGQUERY_PROJECT_ID_PARAM);	
		if (!AnalysisUtility.areParametersValid(bigqueryProjectId)) {
			bigqueryProjectId = getDefaultBigqueryProjectId();
		}
		
		String bigqueryDatasetId = req.getParameter(AnalysisConstants.BIGQUERY_DATASET_ID_PARAM);
		if (!AnalysisUtility.areParametersValid(bigqueryDatasetId)) {
			bigqueryDatasetId = getDefaultBigqueryDatasetId();
		}
		
		String bigqueryFieldExporterSet = req.getParameter(AnalysisConstants.BIGQUERY_FIELD_EXPORTER_SET_PARAM);
		if (!AnalysisUtility.areParametersValid(bigqueryFieldExporterSet)) {
			bigqueryFieldExporterSet = getDefaultBigqueryFieldExporterSet();
		}
		// Instantiate the exporter set to detect errors before we spawn a bunch
		// of tasks.
		BigqueryFieldExporterSet exporterSet = 
				AnalysisUtility.instantiateExporterSet(bigqueryFieldExporterSet);
		String schemaHash = AnalysisUtility.computeSchemaHash(exporterSet);
		
		String queueName = req.getParameter(AnalysisConstants.QUEUE_NAME_PARAM);
		if (!AnalysisUtility.areParametersValid(queueName)) {
			queueName = getDefaultQueueName();
		}
		
		String appVersionsToExport = req.getParameter(AnalysisConstants.APPLICATION_VERSIONS_TO_EXPORT_PARAM);
		if (!AnalysisUtility.areParametersValid(appVersionsToExport)){
			appVersionsToExport = getDefaultAppVersionsToExport();
		}
		
		AppIdentityCredential credential = new AppIdentityCredential(AnalysisConstants.SCOPES);
		
		HttpRequestFactory requestFactory = HTTP_TRANSPORT.createRequestFactory(credential);
		
		try {
			List<String> urisToProcess = AnalysisUtility.fetchCloudStorageLogUris(bucketName, schemaHash, startMs, endMs, requestFactory);
			
			long lastEndMsSeen = startMs - startMs % msPerFile;
			for (String uri : urisToProcess) {
				long uriEndMs = AnalysisUtility.getEndMsFromKey(uri);
				if (uriEndMs > lastEndMsSeen) {
					lastEndMsSeen = uriEndMs;
				}
			}
			List<String> fieldNames = new ArrayList<String>();
			List<String> fieldTypes = new ArrayList<String>();
			List<BigqueryFieldExporter> fieldExporters = new ArrayList<BigqueryFieldExporter>();
			AnalysisUtility.populateSchema(exporterSet, fieldNames, fieldTypes, fieldExporters);
			
			FileService fileService = FileServiceFactory.getFileService();
			
			String schemaKey = AnalysisUtility.createSchemaKey(schemaHash);
			AnalysisUtility.writeSchema(fileService, bucketName, schemaKey, fieldNames, fieldTypes, fieldExporters);
			
			Queue taskQueue = QueueFactory.getQueue(queueName);
			
			int taskCount = 0;
			for (long currentStartMs = lastEndMsSeen; currentStartMs + msPerFile <= endMs; currentStartMs += msPerFile) {
				long tableStartMs = currentStartMs - currentStartMs % msPerTable;
				long tableEndMs = tableStartMs + msPerTable;
				String tableName = AnalysisUtility.createLogKey(schemaHash, tableStartMs, tableEndMs);
				
				TaskOptions taskOptions = Builder
					.withUrl(AnalysisUtility.getRequestBaseName(req) + "/storeLogsInCloudStorage")
					.method(Method.GET)
					.param(AnalysisConstants.START_MS_PARAM, "" + currentStartMs)
					.param(AnalysisConstants.END_MS_PARAM, "" + (currentStartMs + msPerFile))
					.param(AnalysisConstants.BUCKET_NAME_PARAM, bucketName)
					.param(AnalysisConstants.BIGQUERY_PROJECT_ID_PARAM, bigqueryProjectId)
					.param(AnalysisConstants.BIGQUERY_DATASET_ID_PARAM, bigqueryDatasetId)
					.param(AnalysisConstants.BIGQUERY_FIELD_EXPORTER_SET_PARAM, bigqueryFieldExporterSet)
					.param(AnalysisConstants.QUEUE_NAME_PARAM, queueName)
					.param(AnalysisConstants.APPLICATION_VERSIONS_TO_EXPORT_PARAM, appVersionsToExport)
					.param(AnalysisConstants.BIGQUERY_TABLE_ID_PARAM, tableName)
					.param(AnalysisConstants.SCHEMA_HASH_PARAM, schemaHash)
					.param(AnalysisConstants.LOG_LEVEL_PARAM, logLevel);
				
				if (AnalysisUtility.areParametersValid(deleteFromCloudStorage)) {
					taskOptions.param(AnalysisConstants.DELETE_FROM_CLOUD_STORAGE_PARAM, deleteFromCloudStorage);
				}
					
				taskQueue.add(taskOptions);
				taskCount += 1;
			}
			// write to response as well as logs (for feedback when running from browser)
			resp.getWriter().println("Successfully started " + taskCount + " tasks");
			log.info("Successfully started " + taskCount + " tasks");
		} catch (IOException e) {
			log.warning("Error while running cron job.");
		}
	}
	
	protected String getDefaultAppVersionsToExport() {
		if (System.getProperty("bigquerylogging.versionUrl") != null) {
			log.info("trying to GET version via url: " + System.getProperty("bigquerylogging.versionUrl"));
			
			try {
				URL url = new URL(System.getProperty("bigquerylogging.versionUrl"));
				
				InputStream is = url.openStream();
				
				String version = "";
				int ch;
				while ((ch = is.read()) != -1)
					version += ((char)ch);
					
				is.close();
				
				log.info("version to log: " + version);
				
				return version;
			} catch (MalformedURLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
			
		return System.getProperty("bigquerylogging.default.appVersionsToExport");
	}

	protected String getDefaultBucketName() {
		return System.getProperty("bigquerylogging.default.bucketName");
	}
	
	protected String getDefaultBigqueryProjectId() {
		return System.getProperty("bigquerylogging.default.bigqueryProjectId");
	}
	
	protected String getDefaultBigqueryDatasetId() {
		return System.getProperty("bigquerylogging.default.bigqueryDatasetId");
	}
	
	protected String getDefaultBigqueryFieldExporterSet() {
		return LoggingFieldExporterSet.class.getName();
	}
	
	protected String getDefaultQueueName() {
		return System.getProperty("bigquerylogging.default.queueName");
	}
	
	protected String getDefaultLogLevel() {
		return System.getProperty("bigquerylogging.default.logLevel");
	}
}
