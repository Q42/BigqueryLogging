package com.streak.logging.analysis;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.appengine.api.log.AppLogLine;
import com.google.appengine.api.log.LogQuery;
import com.google.appengine.api.log.LogService;
import com.google.appengine.api.log.LogServiceFactory;
import com.google.appengine.api.log.RequestLogs;

@SuppressWarnings("serial")
public class TestLogsAccessibleServlet extends HttpServlet {

	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		resp.setContentType("text/plain");
		
		String minuteHistoryStr = AnalysisUtility.extractParameterOrThrow(req, AnalysisConstants.MINUTES_HISTORY_PARAM);
		String appVersionsToExportString = AnalysisUtility.extractParameterOrThrow(req, AnalysisConstants.APPLICATION_VERSIONS_TO_EXPORT_PARAM);
		LogService ls = LogServiceFactory.getLogService();
		LogQuery lq = new LogQuery();
		
		int minutesHistory = Integer.parseInt(minuteHistoryStr);
		
		long currMillis = System.currentTimeMillis();
		lq = lq.startTimeUsec((currMillis - minutesHistory * 60 * 1000) * 1000)
				.endTimeUsec(currMillis * 1000)
				.includeAppLogs(true);

		
		List<String> appVersions = Arrays.asList(appVersionsToExportString.split(","));
		if (appVersions != null) {
			lq = lq.majorVersionIds(appVersions);
		}

		Iterable<RequestLogs> logs = ls.fetch(lq);
		
		for (RequestLogs rl : logs) {
			resp.getWriter().println(rl.getCombined());
			for (AppLogLine line : rl.getAppLogLines()) {
				resp.getWriter().print("\t");
				resp.getWriter().println(line.getLogMessage());
			}
			resp.getWriter().println();
		}
		
	}
}
