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

package nl.q42.bigquerylogging;

import java.util.logging.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.appengine.api.log.AppLogLine;
import com.google.appengine.api.log.RequestLogs;
import com.streak.logging.analysis.AnalysisUtility;

public class LogMessageExporter extends BigqueryRecordFieldExporter {
	private static Logger logger = Logger.getLogger(LogMessageExporter.class.getName());
	
	JSONArray logMessages = new JSONArray();
	
	@Override
	public void processLog(RequestLogs log) {
		for(AppLogLine line : log.getAppLogLines()) {
			try {
				JSONObject obj = new JSONObject();
				obj.put("level", line.getLogLevel().name());
				obj.put("message", AnalysisUtility.escapeAndQuoteField(line.getLogMessage()));
				logMessages.put(obj);
			} catch (JSONException e) {
				logger.warning(String.format("JSON parser doesn't understand either %s or %s", line.getLogLevel().name(), AnalysisUtility.escapeAndQuoteField(line.getLogMessage())));
			}
		}
	}

	@Override
	public Object getField(String name) {
		return logMessages;
	}

	@Override
	public String getFieldName(int i) {
		return "logMessage";
	}

	@Override
	public JSONArray getRecordFields() {
		JSONArray array = new JSONArray();
		
		JSONObject level = new JSONObject();
		
		try {
			level.put("name", "level");
			level.put("type", "string");
			level.put("mode", "nullable");
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		array.put(level);
		
		JSONObject message = new JSONObject();
		try {
			message.put("name", "message");
			message.put("type", "string");
			message.put("mode", "nullable");
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		array.put(message);
		
		return array;
	}

}
