package nl.q42.bigquerylogging;

import java.io.IOException;
import java.util.logging.Logger;

import javax.servlet.http.*;

@SuppressWarnings("serial")
public class LoggingServlet extends HttpServlet {
	private static Logger log = Logger.getLogger(LoggingServlet.class.getName());
	
	public void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws IOException {
		resp.setContentType("text/plain");
		
		log.info("I logged something!");
		log.warning("WARNING!");
		log.severe("SEVERE!!!");
		
		resp.getWriter().println("Hello, world! I should have logged something by now!");
	}
}
