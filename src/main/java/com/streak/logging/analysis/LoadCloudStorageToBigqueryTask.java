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
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;

import com.google.api.client.googleapis.extensions.appengine.auth.oauth2.AppIdentityCredential;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson.JacksonFactory;
import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.Bigquery.Jobs.Insert;
import com.google.api.services.bigquery.model.Job;
import com.google.api.services.bigquery.model.JobConfiguration;
import com.google.api.services.bigquery.model.JobConfigurationLoad;
import com.google.api.services.bigquery.model.JobReference;
import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions.Builder;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.google.appengine.api.utils.SystemProperty;

@SuppressWarnings("serial")
public class LoadCloudStorageToBigqueryTask extends HttpServlet {
	private static Logger log = Logger
			.getLogger(LoadCloudStorageToBigqueryTask.class.toString());

	private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
	private static final JsonFactory JSON_FACTORY = new JacksonFactory();

	public void doGet(HttpServletRequest req, HttpServletResponse resp) {
		resp.setContentType("text/plain");

		String queueName = AnalysisUtility.extractParameterOrThrow(req,
				AnalysisConstants.QUEUE_NAME_PARAM);
		String bigqueryProjectId = AnalysisUtility.extractParameterOrThrow(req,
				AnalysisConstants.BIGQUERY_PROJECT_ID_PARAM);
		String bigqueryDatasetId = AnalysisUtility.extractParameterOrThrow(req,
				AnalysisConstants.BIGQUERY_DATASET_ID_PARAM);
		String bigqueryTableId = AnalysisUtility.extractParameterOrThrow(req,
				AnalysisConstants.BIGQUERY_TABLE_ID_PARAM);
		String schemaHash = AnalysisUtility.extractParameterOrThrow(req,
				AnalysisConstants.SCHEMA_HASH_PARAM);

		MemcacheService memcache = MemcacheServiceFactory
				.getMemcacheService(AnalysisConstants.MEMCACHE_NAMESPACE);
		Long nextBigQueryJobTime = (Long) memcache.increment(
				AnalysisConstants.LAST_BIGQUERY_JOB_TIME,
				AnalysisConstants.LOAD_DELAY_MS, System.currentTimeMillis());

		long currentTime = System.currentTimeMillis();

		// The task queue has waited a long time to run us. Go ahead and reset
		// the last job time
		// to prevent a race.
		if (currentTime > nextBigQueryJobTime + AnalysisConstants.LOAD_DELAY_MS
				/ 2) {
			memcache.put(AnalysisConstants.LAST_BIGQUERY_JOB_TIME, currentTime);
			nextBigQueryJobTime = currentTime + AnalysisConstants.LOAD_DELAY_MS;
		}
		if (currentTime < nextBigQueryJobTime) {
			memcache.increment(AnalysisConstants.LAST_BIGQUERY_JOB_TIME,
					-AnalysisConstants.LOAD_DELAY_MS);
			Queue taskQueue = QueueFactory.getQueue(queueName);
			taskQueue.add(Builder
					.withUrl(
							AnalysisUtility.getRequestBaseName(req)
									+ "/loadCloudStorageToBigquery?"
									+ req.getQueryString()).method(Method.GET)
					.etaMillis(nextBigQueryJobTime));
			log.info("Rate limiting BigQuery load job - will retry at "
					+ nextBigQueryJobTime);
			return;
		}

		String bucketName = AnalysisUtility.extractParameterOrThrow(req,
				AnalysisConstants.BUCKET_NAME_PARAM);

		AppIdentityCredential credential = new AppIdentityCredential(
				AnalysisConstants.SCOPES);

		HttpRequestFactory requestFactory = HTTP_TRANSPORT
				.createRequestFactory(credential);

		List<String> urisToProcess = new ArrayList<String>();

		String startMsStr = AnalysisUtility.extractParameterOrThrow(req,
				AnalysisConstants.START_MS_PARAM);
		long startMs = Long.parseLong(startMsStr);

		String endMsStr = AnalysisUtility.extractParameterOrThrow(req,
				AnalysisConstants.END_MS_PARAM);
		long endMs = Long.parseLong(endMsStr);

		try {
			urisToProcess = AnalysisUtility.fetchCloudStorageLogUris(bucketName, schemaHash,
					startMs, endMs, requestFactory);
			log.info("Got " + urisToProcess.size() + " uris to process");

			if (urisToProcess.isEmpty()) {
				log.info("No URI's to process");
				return;
			}

			for (String uri : urisToProcess) {
				log.info("URI: " + uri);
			}

			Bigquery bigquery = new Bigquery.Builder(HTTP_TRANSPORT,
					JSON_FACTORY, credential).setApplicationName(
					SystemProperty.applicationId.get()).build();

			Job job = new Job();

			JobConfiguration config = new JobConfiguration();
			JobConfigurationLoad loadConfig = new JobConfigurationLoad();

			loadConfig.setSourceUris(urisToProcess);
			loadConfig.set("allowQuotedNewlines", true);
			loadConfig.setSourceFormat("NEWLINE_DELIMITED_JSON");

			ObjectMapper mapper = new ObjectMapper();
			List<TableFieldSchema> schemaFields = mapper.readValue(
					loadSchemaString("gs://" + bucketName + "/" + schemaHash
							+ ".schema.json"),
					new TypeReference<List<TableFieldSchema>>() {
					});
			TableSchema tableSchema = new TableSchema().setFields(schemaFields);
			loadConfig.setSchema(tableSchema);

			TableReference table = new TableReference();
			table.setProjectId(bigqueryProjectId);
			table.setDatasetId(bigqueryDatasetId);
			table.setTableId(bigqueryTableId);

			loadConfig.setDestinationTable(table);

			config.setLoad(loadConfig);
			job.setConfiguration(config);
			Insert insert = bigquery.jobs().insert(bigqueryProjectId, job);

			// TODO(frew): Not sure this is necessary, but monkey-see'ing the
			// example code
			insert.setProjectId(bigqueryProjectId);
			JobReference ref = insert.execute().getJobReference();
			log.info("Successfully started job " + ref);

			String shouldDeleteString = req
					.getParameter(AnalysisConstants.DELETE_FROM_CLOUD_STORAGE_PARAM);
			boolean shouldDelete = (AnalysisUtility
					.areParametersValid(shouldDeleteString) && shouldDeleteString
					.equals("true"))
					|| System.getProperty(
							"bigquerylogging.default.deleteFromCloudStorage")
							.equals("true");

			if (shouldDelete) {
				Queue taskQueue = QueueFactory.getQueue(queueName);
				taskQueue
						.add(Builder
								.withUrl(
										AnalysisUtility.getRequestBaseName(req)
												+ "/deleteCompletedCloudStorageFilesTask")
								.method(Method.GET)
								.param(AnalysisConstants.BIGQUERY_JOB_ID_PARAM,
										ref.getJobId())
								.param(AnalysisConstants.QUEUE_NAME_PARAM,
										queueName)
								.param(AnalysisConstants.BIGQUERY_PROJECT_ID_PARAM,
										bigqueryProjectId));
			}
		} catch (IOException e) {
			log.warning("An error occurred while loading cloud storage to Bigquery. Retrying.");
			Queue taskQueue = QueueFactory.getQueue(queueName);
			taskQueue.add(Builder.withUrl(req.getRequestURL().toString()));
		}
	}

	private String loadSchemaString(String fileUri) throws IOException {
		String schemaFileName = "/gs/"
				+ fileUri.substring(fileUri.indexOf("//") + 2);

		return AnalysisUtility.loadSchemaString(schemaFileName);
	}
}
