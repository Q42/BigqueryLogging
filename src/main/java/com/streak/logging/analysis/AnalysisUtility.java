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

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.channels.Channels;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import nl.q42.bigquerylogging.BigqueryRecordFieldExporter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.json.JsonFactory;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.appengine.api.datastore.Text;
import com.google.appengine.api.files.AppEngineFile;
import com.google.appengine.api.files.FileReadChannel;
import com.google.appengine.api.files.FileService;
import com.google.appengine.api.files.FileServiceFactory;
import com.google.appengine.api.files.FileWriteChannel;
import com.google.appengine.api.files.FinalizationException;
import com.google.appengine.api.files.GSFileOptions.GSFileOptionsBuilder;
import com.google.appengine.api.files.LockException;

public class AnalysisUtility {
	private static Logger log = Logger.getLogger(AnalysisUtility.class.getName());
	
	// Only static methods
	private AnalysisUtility() {	
	}

	public static boolean areParametersValid(String... params) {
		for (String p : params) {
			if (p == null || p.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	public static String extractParameterOrThrow(HttpServletRequest req, String paramName) {
		String param = req.getParameter(paramName);
		if (!areParametersValid(param)) {
			throw new InvalidTaskParameterException("Couldn't find required parameter " + paramName);
		}
		return param;
	}

	public static String escapeAndQuoteField(String field) {
		return org.json.JSONObject.quote(field);
	}

	public static String createLogKey(String schemaHash, long startTime, long endTime) {
		return String.format("log_%s_%020d_%020d", schemaHash, startTime, endTime);
	}

	public static String createLogFilename(String schemaHash, long startTime, long endTime) {
		return createLogKey(schemaHash, startTime, endTime) + ".json";
	}
	
	public static String createSchemaKey(String schemaHash) {
		return schemaHash;
	}

	public static String createSchemaFilename(String schemaHash){
		return createSchemaKey(schemaHash) + ".schema.json";
	}
	
	public static long getEndMsFromKey(String key) {
		String numberString = key;
		if (numberString.contains("."))
			numberString = numberString.substring(0, numberString.indexOf("."));
		
		String[] keyParts = numberString.split("_");
		return Long.parseLong(keyParts[keyParts.length - 1]);
	}

	public static List<String> fetchCloudStorageLogUris(
			String bucketName,
			String schemaHash,
			long startMs,
			long endMs,
			HttpRequestFactory requestFactory) throws IOException {
		String startKey = AnalysisUtility.createLogKey(schemaHash, startMs, startMs);
		String endKey = AnalysisUtility.createLogKey(schemaHash, endMs, endMs);
		return fetchCloudStorageUris(bucketName, startKey, endKey, requestFactory);
	}

	public static List<String> fetchCloudStorageUris(String bucketName, String startKey, String endKey, HttpRequestFactory requestFactory) throws IOException {
		List<String> result = new ArrayList<String>();
		
		String bucketUri = "http://commondatastorage.googleapis.com/" + bucketName;
		HttpRequest request = requestFactory.buildGetRequest(new GenericUrl(bucketUri + "?marker=" + startKey));
		HttpResponse response = request.execute();

		try {
			Document responseDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(response.getContent());
			XPath xPath = XPathFactory.newInstance().newXPath();
			NodeList nodes = (NodeList) xPath.evaluate("//Contents/Key/text()", responseDoc, XPathConstants.NODESET);
			for (int i = 0; i < nodes.getLength(); i++) {
				String key = nodes.item(i).getNodeValue();
				if (key.compareTo(endKey) >= 0) {
					break;
				}
				if (key.endsWith(".schema.json")) {
					continue;
				}
				result.add("gs://" + bucketName + "/" + key);
			} 
		} catch (SAXException e) {
			throw new IOException("Error parsing cloud storage response", e);
		} catch (ParserConfigurationException e) {
			throw new IOException("Error configuring cloud storage parser", e);
		} catch (XPathExpressionException e) {
			throw new IOException("Error finding keys", e);
		}
		
		return result;
	}

	public static String getRequestBaseName(HttpServletRequest req) {
		String path = req.getRequestURI();
		return path.substring(0, path.lastIndexOf("/"));
	}

	public static BigqueryFieldExporterSet instantiateExporterSet(String exporterSetClassStr) {
		Class<?> exporterSetClass;
		try {
			exporterSetClass = Class.forName(exporterSetClassStr);
		} catch (ClassNotFoundException e) {
			throw new InvalidTaskParameterException("Got invalid BigqueryFieldExporterSet class name: " + exporterSetClassStr);
		}
		if (!BigqueryFieldExporterSet.class.isAssignableFrom(exporterSetClass)) {
			throw new InvalidTaskParameterException("Got bigqueryFieldExporterSet parameter " 
					+ exporterSetClassStr + " that doesn't implement BigqueryFieldExporterSet");
		}
		BigqueryFieldExporterSet exporterSet;
		try {
			exporterSet = (BigqueryFieldExporterSet)exporterSetClass.newInstance();
		} catch (InstantiationException e) {
			throw new InvalidTaskParameterException("Couldn't instantiate BigqueryFieldExporter set class " + exporterSetClassStr);
		} catch (IllegalAccessException e) {
			throw new InvalidTaskParameterException("BigqueryFieldExporter class " + exporterSetClassStr + " has no visible default constructor");
		}
		return exporterSet;
	}

	public static String computeSchemaHash(BigqueryFieldExporterSet exporterSet) {
		try {
			List<BigqueryFieldExporter> exporters = exporterSet.getExporters();
			MessageDigest md = MessageDigest.getInstance("MD5");
			for (BigqueryFieldExporter exporter : exporters) {
				for (int i = 0; i < exporter.getFieldCount(); i++) {
					md.update(exporter.getFieldName(i).getBytes("UTF-8"));
					md.update(exporter.getFieldType(i).getBytes("UTF-8"));
					if (exporter instanceof BigqueryRecordFieldExporter) {
						md.update(((BigqueryRecordFieldExporter)exporter).getRecordFields().toString().getBytes());
					}
				}
			}
			byte[] array = md.digest();
			StringBuffer sb = new StringBuffer();
			for (int i = 0; i < array.length; ++i) {
				sb.append(Integer.toHexString((array[i] & 0xFF) | 0x100).substring(1,3));
			}
			return sb.toString().substring(0, 6);
		} catch (NoSuchAlgorithmException nsae) {
			throw new RuntimeException("Couldn't find MD5 algorithm for schema hash", nsae);
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException("Couldn't get UTF-8 encoding for schema hash", e);
		}
	}

	public static void writeSchema(FileService fileService, String bucketName, String schemaKey, List<String> fieldNames, List<String> fieldTypes, List<BigqueryFieldExporter> exporters) throws IOException, FileNotFoundException, FinalizationException, LockException {
		GSFileOptionsBuilder schemaOptionsBuilder = new GSFileOptionsBuilder()
			.setBucket(bucketName)
			.setKey(createSchemaFilename(schemaKey))
			.setAcl("project-private");
		AppEngineFile schemaFile = fileService.createNewGSFile(schemaOptionsBuilder.build());
		
		FileWriteChannel schemaChannel = fileService.openWriteChannel(schemaFile, true);
		PrintWriter schemaWriter = new PrintWriter(Channels.newWriter(schemaChannel, "UTF8"));
		
		JSONArray fields = new JSONArray();
		
		for (int i = 0; i < fieldNames.size(); i++) {
			try {
				JSONObject obj = new JSONObject();
				obj.put("name", fieldNames.get(i));
				obj.put("type", fieldTypes.get(i));
				
				BigqueryFieldExporter exp = exporters.get(i);
				if (exp instanceof BigqueryRecordFieldExporter) {
					obj.put("mode", "repeated");
					obj.put("fields", ((BigqueryRecordFieldExporter)exp).getRecordFields());	
				} else {
					obj.put("mode","nullable");
				}
				
				fields.put(obj);
			} catch (JSONException e) {
				log.severe("This should not happen!");
			}
		}
		
		schemaWriter.print(fields.toString());
		schemaWriter.close();
		
		schemaChannel.closeFinally();
	}

	public static void populateSchema(BigqueryFieldExporterSet exporterSet,
			List<String> fieldNames, List<String> fieldTypes, List<BigqueryFieldExporter> fieldExporters) {
		List<BigqueryFieldExporter> exporters = exporterSet.getExporters();

		for (BigqueryFieldExporter exporter : exporters) {
			for (int i = 0; i < exporter.getFieldCount(); i++) {
				if (fieldNames.contains(exporter.getFieldName(i))) {
					throw new InvalidFieldException("BigqueryFieldExporterSet " + exporterSet.getClass().getCanonicalName() + " defines multiple fields with name " + exporter.getFieldName(i));
				}
				fieldNames.add(exporter.getFieldName(i));
				fieldTypes.add(exporter.getFieldType(i).toLowerCase().intern());
				fieldExporters.add(exporter);
			}
		}
	}
	
	public static String formatCsvValue(Object fieldValue, String type) {
		NumberFormat nf = createFixedPointFormat();
		// These strings have been interned so == works for comparison
		if ("string" == type) {
			if (fieldValue instanceof Text) {
				return AnalysisUtility.escapeAndQuoteField(((Text) fieldValue).getValue());
			}
			
			return AnalysisUtility.escapeAndQuoteField("" + fieldValue);
		}
		if ("float" == type) {
			return nf.format(fieldValue);
		}
		if ("integer" == type) {
			if (fieldValue instanceof Date) {
				return "" + ((Date) fieldValue).getTime();
			}
		}
		
		return "" + fieldValue;
	}
	
	public static String formatJsonValue(Object fieldValue, String type) {
		NumberFormat nf = createFixedPointFormat();
		// These strings have been interned so == works for comparison
		if ("string" == type) {
			if (fieldValue instanceof Text) {
				return AnalysisUtility.escapeAndQuoteField(((Text) fieldValue).getValue());
			}
			
			return AnalysisUtility.escapeAndQuoteField("" + fieldValue);
		}
		if ("float" == type) {
			return nf.format(fieldValue);
		}
		if ("integer" == type) {
			if (fieldValue instanceof Date) {
				return "" + ((Date) fieldValue).getTime();
			}
		}
		
		return "" + fieldValue;
	}
	
	private static NumberFormat fixedPoint;

	private static NumberFormat createFixedPointFormat() {
		if (fixedPoint == null) {
			// Avoid scientific notation output
			fixedPoint = NumberFormat.getInstance();
			fixedPoint.setGroupingUsed(false);
			fixedPoint.setParseIntegerOnly(false);
			fixedPoint.setMinimumFractionDigits(1);
			fixedPoint.setMaximumFractionDigits(30);
			fixedPoint.setMinimumIntegerDigits(1);
			fixedPoint.setMaximumIntegerDigits(30);
		}
		return fixedPoint;
	}
	

	public static String loadSchemaString(String schemaFileName)
			throws FileNotFoundException, LockException, IOException {
		FileService fileService = FileServiceFactory.getFileService();
		AppEngineFile schemaFile = new AppEngineFile(schemaFileName);
		FileReadChannel readChannel = fileService.openReadChannel(schemaFile, false);
		BufferedReader reader = new BufferedReader(Channels.newReader(readChannel, "UTF8"));
		String schemaLine;
		try {
			schemaLine = reader.readLine().trim();
		} catch (NullPointerException npe) {
			throw new IOException("Encountered NPE reading " + schemaFileName);
		}
		reader.close();
		readChannel.close();
		return schemaLine;
	}
	
	public static TableSchema loadSchema(String schemaString, JsonFactory factory) {
		
		
		return null;
	}

	public static String failureJson(String message) {
		return "{\"success\":false, \"message\":\"" + message + "\"}";
	}
	
	public static String successJson(String message) {
		return "{\"success\":true, \"message\":\"" + message + "\"}";
	}
	

	public static String getPreBackupName(long timestamp, String backupNamePrefix) {
		if (!AnalysisUtility.areParametersValid(backupNamePrefix)) {
			backupNamePrefix = AnalysisConstants.DEFAULT_DATASTORE_BACKUP_NAME;
		}
		return backupNamePrefix + timestamp + "_";
	}
}
