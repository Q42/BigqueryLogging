package nl.q42.bigquerylogging;

import java.util.Arrays;
import java.util.List;

import com.google.appengine.api.log.RequestLogs;
import com.streak.logging.analysis.BigqueryFieldExporter;
import com.streak.logging.analysis.BigqueryFieldExporterSet;
import com.streak.logging.analysis.example.HttpTransactionFieldExporter;
import com.streak.logging.analysis.example.InstanceFieldExporter;
import com.streak.logging.analysis.example.PerformanceFieldExporter;
import com.streak.logging.analysis.example.TimestampFieldExporter;
import com.streak.logging.analysis.example.UrlFieldExporter;
import com.streak.logging.analysis.example.UserFieldExporter;
import com.streak.logging.analysis.example.VersionFieldExporter;

public class LoggingFieldExporterSet implements BigqueryFieldExporterSet {

	@Override
	public List<BigqueryFieldExporter> getExporters() {
		return Arrays.asList(
				new HttpTransactionFieldExporter(),
				new InstanceFieldExporter(),
				new PerformanceFieldExporter(),
				new TimestampFieldExporter(),
				new UrlFieldExporter(),
				new UserFieldExporter(),
				new VersionFieldExporter(),
				new LogMessageExporter());
	}

	@Override
	public boolean skipLog(RequestLogs arg0) {
		// TODO Auto-generated method stub
		return false;
	}

}
