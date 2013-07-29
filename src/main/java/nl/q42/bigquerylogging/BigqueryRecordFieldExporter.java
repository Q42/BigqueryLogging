package nl.q42.bigquerylogging;

import org.json.JSONArray;

import com.google.appengine.api.log.RequestLogs;
import com.streak.logging.analysis.BigqueryFieldExporter;

public abstract class BigqueryRecordFieldExporter implements BigqueryFieldExporter {

	@Override
	public abstract void processLog(RequestLogs log);
	
	@Override
	public abstract Object getField(String name);

	@Override
	public int getFieldCount() {
		return 1;
	}

	@Override
	public abstract String getFieldName(int i);

	@Override
	public String getFieldType(int i) {
		return "record";
	}

	public abstract JSONArray getRecordFields();
}
